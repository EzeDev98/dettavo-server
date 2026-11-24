package com.dettavo.feature.media.service;

import com.dettavo.common.exception.ClipNotFoundException;
import com.dettavo.common.exception.ClipStateConflictException;
import com.dettavo.common.exception.UnsupportedMediaException;
import com.dettavo.common.process.CancellationToken;
import com.dettavo.feature.job.dto.request.JobContext;
import com.dettavo.feature.job.dto.response.JobResponse;
import com.dettavo.feature.job.service.JobService;
import com.dettavo.feature.job.enums.JobType;
import com.dettavo.feature.project.service.ProjectStore;
import com.dettavo.feature.project.dto.Clip;
import com.dettavo.feature.project.dto.ClipArtifacts;
import com.dettavo.feature.project.dto.ClipSource;
import com.dettavo.feature.project.enums.ClipState;
import com.dettavo.feature.project.dto.Project;
import com.dettavo.feature.transcription.service.TranscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.DoubleConsumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClipPreparationService {

    private static final double PROBE_SHARE = 0.02;
    private static final double THUMBNAIL_SHARE = 0.03;
    private static final double VIDEO_AUDIO_SHARE = 0.25;
    private static final double AUDIO_ONLY_AUDIO_SHARE = 0.40;

    private final JobService jobService;
    private final ProjectStore projectStore;
    private final ClipFiles clipFiles;
    private final MediaProbeService mediaProbeService;
    private final AudioExtractionService audioExtractionService;
    private final ThumbnailService thumbnailService;
    private final ProxyService proxyService;
    private final TranscriptionService transcriptionService;

    /** Queues PREPARE_MEDIA for a clip whose upload has finished. 409 if the clip already has a job. */
    public JobResponse queue(UUID projectId, String clipId) {
        return jobService.submit(JobType.PREPARE_MEDIA, projectId, clipId, context -> prepare(context, projectId, clipId)).snapshot();
    }

    /** POST .../prepare: rebuilds the clip's cache. 409 if the clip has no uploaded file yet. */
    public JobResponse rebuild(UUID projectId, String clipId) {
        Clip clip = projectStore.load(projectId).findClip(clipId)
                .orElseThrow(() -> new ClipNotFoundException(projectId, clipId));
        if (clip.state() == ClipState.EMPTY || clip.state() == ClipState.UPLOADING || clipFiles.source(projectId, clipId).isEmpty()) {
            throw new ClipStateConflictException("Clip " + clipId + " has no uploaded file to prepare yet");
        }
        return queue(projectId, clipId);
    }

    /** After a restart nothing is uploading or preparing: an interrupted upload starts again, a preparation fails. */
    @EventListener(ApplicationStartedEvent.class)
    public void recoverInterruptedClips() {
        for (Project project : projectStore.list()) {
            boolean interrupted = project.clips().stream()
                    .anyMatch(clip -> clip.state() == ClipState.UPLOADING || clip.state() == ClipState.PREPARING);
            if (!interrupted) {
                continue;
            }
            projectStore.update(project.id(), current -> current.toBuilder()
                    .clips(current.clips().stream().map(ClipPreparationService::afterRestart).toList())
                    .build());
            log.warn("Project {}: clips interrupted by the restart reset", project.id());
        }
    }

    private void prepare(JobContext context, UUID projectId, String clipId) {
        Path logFile = clipFiles.jobLog(projectId, context.jobId());
        context.logFile(logFile);
        boolean finished = false;
        try {
            finished = runStages(context, projectId, clipId, logFile);
        } finally {
            if (!finished) {
                setState(projectId, clipId, ClipState.FAILED);
            }
        }
    }

    private boolean runStages(JobContext context, UUID projectId, String clipId, Path logFile) {
        CancellationToken token = context.cancellationToken();
        Path source = clipFiles.source(projectId, clipId)
                .orElseThrow(() -> new UnsupportedMediaException("The uploaded file is missing. Delete the clip and add the file again."));

        projectStore.deleteRecursively(clipFiles.cacheDirectory(projectId, clipId));
        Clip clip = projectStore.updateClip(projectId, clipId,
                current -> current.toBuilder().state(ClipState.PREPARING).artifacts(null).failure(null).build());

        context.progress(0, "Reading the file");
        ClipSource probed = mediaProbeService.probe(source, logFile, token);
        ClipSource source0 = probed.toBuilder().fileName(clip.source().fileName()).bytes(clip.source().bytes()).build();
        projectStore.updateClip(projectId, clipId, current -> current.toBuilder().source(source0).build());
        if (token.isCancelled()) {
            return false;
        }

        boolean video = Boolean.TRUE.equals(source0.hasVideo());
        long durationMs = source0.durationMs();
        ClipArtifacts.ClipArtifactsBuilder artifacts = ClipArtifacts.builder();

        double audioShare = video ? VIDEO_AUDIO_SHARE : AUDIO_ONLY_AUDIO_SHARE;
        if (!audioExtractionService.extract(source, clipFiles.artifact(projectId, clipId, ClipFiles.AUDIO_16K), durationMs,
                logFile, token, stage(context, PROBE_SHARE, audioShare, "Extracting audio"))) {
            return false;
        }
        artifacts.audio16k(ClipFiles.AUDIO_16K);

        double proxyStart = PROBE_SHARE + audioShare;
        if (video) {
            context.progress(proxyStart, "Making a thumbnail");
            if (!thumbnailService.capture(source, clipFiles.artifact(projectId, clipId, ClipFiles.THUMBNAIL), durationMs, logFile, token)) {
                return false;
            }
            artifacts.thumbnail(ClipFiles.THUMBNAIL);
            proxyStart += THUMBNAIL_SHARE;
        }

        String proxy = video ? ClipFiles.PROXY_VIDEO : ClipFiles.PROXY_AUDIO;
        if (!proxyService.build(source, clipFiles.artifact(projectId, clipId, proxy), video, durationMs, logFile, token,
                stage(context, proxyStart, 1 - proxyStart, "Making the playback copy"))) {
            return false;
        }
        artifacts.proxy(proxy);

        ClipArtifacts built = artifacts.build();
        Clip prepared = projectStore.updateClip(projectId, clipId, current -> current.toBuilder()
                .artifacts(built)
                .state(current.transcript() == null ? ClipState.PREPARED : ClipState.READY)
                .build());
        context.progress(1, "Prepared");
        log.info("Project {}: clip {} is {}", projectId, clipId, prepared.state());
        if (prepared.transcript() == null) {
            transcriptionService.queueAfter(context);
        }
        return true;
    }

    /** Reports one stage's 0..1 progress as its share of the whole job. */
    private static DoubleConsumer stage(JobContext context, double start, double share, String label) {
        context.progress(start, label);
        return fraction -> context.progress(start + share * fraction, label);
    }

    private void setState(UUID projectId, String clipId, ClipState state) {
        try {
            projectStore.updateClip(projectId, clipId, current -> current.toBuilder().state(state).build());
        } catch (RuntimeException e) {
            log.warn("Could not mark clip {} of project {} {}: {}", clipId, projectId, state, e.getMessage());
        }
    }

    private static Clip afterRestart(Clip clip) {
        return switch (clip.state()) {
            case UPLOADING -> clip.toBuilder().state(ClipState.EMPTY).build();
            case PREPARING -> clip.toBuilder().state(ClipState.FAILED).build();
            default -> clip;
        };
    }
}
