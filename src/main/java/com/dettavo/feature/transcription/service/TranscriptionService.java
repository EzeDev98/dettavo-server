package com.dettavo.feature.transcription.service;

import com.dettavo.common.exception.ClipNotFoundException;
import com.dettavo.common.exception.ClipStateConflictException;
import com.dettavo.config.ApplicationProperties;
import com.dettavo.feature.job.dto.request.JobContext;
import com.dettavo.feature.job.dto.response.JobResponse;
import com.dettavo.feature.job.service.JobService;
import com.dettavo.feature.job.enums.JobStatus;
import com.dettavo.feature.job.enums.JobType;
import com.dettavo.feature.media.service.ClipFiles;
import com.dettavo.feature.project.service.ProjectStore;
import com.dettavo.feature.project.dto.Clip;
import com.dettavo.feature.project.enums.ClipState;
import com.dettavo.feature.project.dto.JobSummary;
import com.dettavo.feature.project.dto.Project;
import com.dettavo.feature.project.dto.Transcript;
import com.dettavo.feature.project.dto.Word;
import com.dettavo.feature.transcription.dto.TimedWord;
import com.dettavo.feature.job.dto.request.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptionService {

    public static final String ENGINE = "whisper.cpp";
    private static final double FINISHING = 0.99;

    private final JobService jobService;
    private final ProjectStore projectStore;
    private final ClipFiles clipFiles;
    private final TranscriptionFiles transcriptionFiles;
    private final ChunkPlanner chunkPlanner;
    private final WhisperRunner whisperRunner;
    private final WhisperJsonParser whisperJsonParser;
    private final TranscriptNormalizer transcriptNormalizer;
    private final PauseRefiner pauseRefiner;
    private final ParagraphBuilder paragraphBuilder;
    private final ApplicationProperties properties;

    /** Called at the end of a successful PREPARE_MEDIA for a clip that has no transcript yet. */
    public void queueAfter(JobContext preparation) {
        UUID projectId = preparation.projectId();
        String clipId = preparation.clipId();
        jobService.submitFollowUp(preparation, JobType.TRANSCRIBE, context -> transcribe(context, projectId, clipId));
    }

    public JobResponse retry(UUID projectId, String clipId) {
        Clip clip = projectStore.load(projectId).findClip(clipId)
                .orElseThrow(() -> new ClipNotFoundException(projectId, clipId));
        if (clip.transcript() != null) {
            throw new ClipStateConflictException("Clip " + clipId + " is already transcribed; transcribing it again is not in v1");
        }
        if (!prepared(projectId, clip)) {
            throw new ClipStateConflictException("Clip " + clipId + " is not prepared; prepare it again first");
        }
        if (clip.state() != ClipState.PREPARED && clip.state() != ClipState.FAILED) {
            throw new ClipStateConflictException("Clip " + clipId + " is " + clip.state() + "; only a prepared or failed clip can be transcribed");
        }
        return queue(projectId, clipId).snapshot();
    }

    /**
     * After a restart, a clip that was transcribing, or was prepared but never transcribed, is queued again and
     * resumes from its finished chunks. A clip whose last transcription was cancelled is left as it is.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeAfterRestart() {
        for (Project project : projectStore.list()) {
            for (Clip clip : project.clips()) {
                if (!shouldResume(project, clip)) {
                    continue;
                }
                try {
                    projectStore.updateClip(project.id(), clip.id(), current -> current.toBuilder().state(ClipState.PREPARED).build());
                    queue(project.id(), clip.id());
                    log.info("Project {}: clip {} queued to transcribe after the restart", project.id(), clip.id());
                } catch (RuntimeException e) {
                    log.warn("Project {}: could not queue clip {} to transcribe: {}", project.id(), clip.id(), e.getMessage());
                }
            }
        }
    }

    private Job queue(UUID projectId, String clipId) {
        return jobService.submit(JobType.TRANSCRIBE, projectId, clipId, context -> transcribe(context, projectId, clipId));
    }

    private void transcribe(JobContext context, UUID projectId, String clipId) {
        Path logFile = clipFiles.jobLog(projectId, context.jobId());
        context.logFile(logFile);
        boolean finished = false;
        try {
            finished = run(context, projectId, clipId, logFile);
        } finally {
            // A server shutdown interrupts the job: the clip stays TRANSCRIBING and resumes on the next start.
            if (!finished && !Thread.currentThread().isInterrupted()) {
                setState(projectId, clipId, context.isCancelled() ? ClipState.PREPARED : ClipState.FAILED);
            }
        }
    }

    private boolean run(JobContext context, UUID projectId, String clipId, Path logFile) {
        Project project = projectStore.load(projectId);
        Clip clip = project.findClip(clipId).orElseThrow(() -> new ClipNotFoundException(projectId, clipId));
        if (clip.transcript() != null) {
            return true;
        }
        projectStore.updateClip(projectId, clipId, current -> current.toBuilder().state(ClipState.TRANSCRIBING).build());

        Path audio = transcriptionFiles.audio16k(projectId, clipId);
        WavAudio wav = WavAudio.read(audio);
        List<ChunkPlanner.Chunk> chunks = chunkPlanner.plan(wav.durationMs());
        int total = chunks.size();
        boolean estimating = false;
        List<TimedWord> joined = new ArrayList<>();

        for (int index = 0; index < total; index++) {
            ChunkPlanner.Chunk chunk = chunks.get(index);
            Path json = transcriptionFiles.chunkJson(projectId, clipId, chunk);
            String stage = total == 1 ? "Transcribing" : "Transcribing part " + chunk.number() + " of " + total;
            if (Files.isRegularFile(json)) {
                log.info("Project {}: clip {} reuses {}", projectId, clipId, json.getFileName());
            } else {
                if (!estimating) {
                    context.restartEstimate();
                    estimating = true;
                }
                context.progress((double) index / total, stage);
                if (!transcribeChunk(context, audio, wav, chunk, json, logFile, index, total, stage)) {
                    return false;
                }
            }
            context.progress((double) (index + 1) / total, stage);
            joined.addAll(transcriptNormalizer.chunkWords(whisperJsonParser.parse(json), chunk, whisperRunner.usesDtw()));
        }
        if (context.isCancelled()) {
            return false;
        }

        context.progress(FINISHING, "Finishing the transcript");
        List<Word> words = TranscriptNormalizer.withIds(pauseRefiner.refine(transcriptNormalizer.finish(joined), audio, wav));
        Transcript transcript = Transcript.builder()
                .engine(ENGINE)
                .model(whisperRunner.modelName())
                .language(properties.transcription().language())
                .generatedAt(ProjectStore.now())
                .words(words)
                .paragraphs(paragraphBuilder.build(words))
                .build();
        projectStore.updateClip(projectId, clipId, current -> current.toBuilder().transcript(transcript).state(ClipState.READY).build());
        log.info("Project {}: clip {} transcribed, {} words in {} paragraphs", projectId, clipId,
                transcript.words().size(), transcript.paragraphs().size());
        return true;
    }

    /** Cuts one chunk WAV, runs whisper on it and deletes the WAV. Returns false if cancelled. */
    private boolean transcribeChunk(JobContext context, Path audio, WavAudio wav, ChunkPlanner.Chunk chunk, Path json,
                                    Path logFile, int index, int total, String stage) {
        Path chunkAudio = transcriptionFiles.chunkAudio(context.projectId(), context.clipId(), chunk);
        wav.writeSlice(audio, chunk.startMs(), chunk.endMs(), chunkAudio);
        try {
            return whisperRunner.transcribe(chunkAudio, json, logFile, context.cancellationToken(),
                    fraction -> context.progress((index + fraction) / total, stage));
        } finally {
            ClipFiles.discard(chunkAudio);
        }
    }

    private boolean prepared(UUID projectId, Clip clip) {
        return clip.artifacts() != null && clip.artifacts().audio16k() != null
                && Files.isRegularFile(transcriptionFiles.audio16k(projectId, clip.id()));
    }

    private boolean shouldResume(Project project, Clip clip) {
        if (clip.transcript() != null || !prepared(project.id(), clip)) {
            return false;
        }
        if (clip.state() == ClipState.TRANSCRIBING) {
            return true;
        }
        return clip.state() == ClipState.PREPARED && !lastTranscriptionCancelled(project, clip.id());
    }

    private static boolean lastTranscriptionCancelled(Project project, String clipId) {
        JobSummary last = null;
        for (JobSummary summary : project.jobs()) {
            if (clipId.equals(summary.clipId())) {
                last = summary;
            }
        }
        return last != null && last.type() == JobType.TRANSCRIBE && last.status() == JobStatus.CANCELLED;
    }

    private void setState(UUID projectId, String clipId, ClipState state) {
        try {
            projectStore.updateClip(projectId, clipId, current -> current.toBuilder().state(state).build());
        } catch (RuntimeException e) {
            log.warn("Could not mark clip {} of project {} {}: {}", clipId, projectId, state, e.getMessage());
        }
    }
}
