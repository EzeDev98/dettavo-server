package com.dettavo.feature.export.service;

import com.dettavo.common.exception.UnsupportedMediaException;
import com.dettavo.common.process.CancellationToken;
import com.dettavo.feature.export.dto.request.ExportRequest;
import com.dettavo.feature.job.dto.request.JobContext;
import com.dettavo.feature.job.dto.response.JobResponse;
import com.dettavo.feature.job.service.JobService;
import com.dettavo.feature.job.enums.JobType;
import com.dettavo.feature.media.service.ClipFiles;
import com.dettavo.feature.media.service.FfmpegRunner;
import com.dettavo.feature.media.service.MediaProbeService;
import com.dettavo.feature.project.service.ProjectStore;
import com.dettavo.feature.project.dto.ClipSource;
import com.dettavo.feature.project.dto.Project;
import com.dettavo.feature.export.enums.ExportFormat;
import com.dettavo.feature.export.dto.request.ExportPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    public static final String LENGTH_MISMATCH = "Export length does not match the plan";
    private static final String CHECKING = "Checking the export";
    private static final long TOLERANCE_MS = 100;
    private static final double TOLERANCE_FRACTION = 0.005;

    private final ProjectStore projectStore;
    private final ExportPlanner exportPlanner;
    private final FilterGraphBuilder filterGraphBuilder;
    private final ExportEncoder exportEncoder;
    private final ExportFiles exportFiles;
    private final FfmpegRunner ffmpegRunner;
    private final MediaProbeService mediaProbeService;
    private final ClipFiles clipFiles;
    private final JobService jobService;

    /** POST /projects/{id}/export. 409 while a timeline clip is not READY; 400 when nothing is left to export. */
    public JobResponse start(UUID projectId, ExportRequest request) {
        return start(projectId, request, false);
    }

    /** brokenForTest (development only): the graph leaves out the last segment, so the final check must fail. */
    public JobResponse start(UUID projectId, ExportRequest request, boolean brokenForTest) {
        Project project = projectStore.load(projectId);
        ExportPlan plan = exportPlanner.plan(project, request.format());
        ExportPlan graphPlan = brokenForTest ? plan.withoutLastSegment() : plan;
        return jobService.submit(JobType.EXPORT, projectId, null,
                context -> export(context, project.name(), plan, graphPlan, request)).snapshot();
    }

    private void export(JobContext context, String projectName, ExportPlan plan, ExportPlan graphPlan, ExportRequest request) {
        UUID projectId = plan.projectId();
        Path logFile = clipFiles.jobLog(projectId, context.jobId());
        context.logFile(logFile);
        Path graphFile = exportFiles.graphFile(projectId, context.jobId());
        Path target = exportFiles.newExport(projectId, projectName, request.format());
        Path partial = ClipFiles.partialOf(target);
        String stage = "Encoding " + request.format().label();
        context.progress(0, stage);

        try {
            Files.writeString(graphFile, filterGraphBuilder.build(graphPlan));
            boolean finished = ffmpegRunner.runToPartial(arguments(plan, graphFile, request), partial, plan.editedMs(),
                    logFile, context.cancellationToken(), fraction -> context.progress(fraction, stage));
            if (!finished) {
                return;
            }
            context.progress(1.0, CHECKING);
            checkLength(partial, plan, request.format(), logFile, context.cancellationToken());
            ClipFiles.commit(partial, target);
            log.info("Project {}: exported {} ({} ms)", projectId, target.getFileName(), plan.editedMs());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + graphFile, e);
        } finally {
            ClipFiles.discard(graphFile);
            ClipFiles.discard(partial);
        }
    }

    // ffmpeg -nostdin -hide_banner -y -i <clip> ... <graph flag> <graph file> -map [vout] -map [aout] <encoder> -f <fmt>
    private List<String> arguments(ExportPlan plan, Path graphFile, ExportRequest request) {
        List<String> arguments = new ArrayList<>();
        plan.inputs().forEach(input -> arguments.addAll(List.of("-i", input.source())));
        arguments.addAll(List.of(ffmpegRunner.filterGraphFlag(), graphFile.toString()));
        if (plan.video() != null) {
            arguments.addAll(List.of("-map", "[vout]"));
        }
        arguments.addAll(List.of("-map", "[aout]"));
        arguments.addAll(exportEncoder.options(request.format(), request.quality(), plan.video()));
        return arguments;
    }

    private void checkLength(Path partial, ExportPlan plan, ExportFormat format, Path logFile, CancellationToken token) {
        ClipSource probed;
        try {
            probed = mediaProbeService.probe(partial, logFile, token);
        } catch (UnsupportedMediaException e) {
            log.warn("Export check: {} could not be read: {}", partial.getFileName(), e.getMessage());
            throw new IllegalStateException(LENGTH_MISMATCH);
        }
        long toleranceMs = Math.max(TOLERANCE_MS, Math.round(plan.editedMs() * TOLERANCE_FRACTION));
        boolean videoMissing = format.hasVideo() && !Boolean.TRUE.equals(probed.hasVideo());
        Long actualMs = probed.durationMs();
        if (videoMissing || actualMs == null || Math.abs(actualMs - plan.editedMs()) > toleranceMs) {
            log.warn("Export check failed: planned {} ms, got {} ms, video missing: {}", plan.editedMs(), actualMs, videoMissing);
            throw new IllegalStateException(LENGTH_MISMATCH);
        }
    }
}
