package com.dettavo.feature.export.service;

import com.dettavo.common.exception.ClipStateConflictException;
import com.dettavo.common.exception.MediaNotFoundException;
import com.dettavo.feature.edit.dto.EditPlan;
import com.dettavo.feature.edit.service.EditPlanner;
import com.dettavo.feature.edit.dto.KeepSegment;
import com.dettavo.feature.media.service.ClipFiles;
import com.dettavo.feature.project.dto.Clip;
import com.dettavo.feature.project.dto.ClipSource;
import com.dettavo.feature.project.enums.ClipState;
import com.dettavo.feature.project.dto.Project;
import com.dettavo.feature.export.enums.ExportFormat;
import com.dettavo.feature.export.dto.request.ExportPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ExportPlanner {

    public static final String NOTHING_LEFT = "Nothing left to export";
    public static final String WAIT_FOR_CLIPS = "Wait for every clip on the timeline to finish";
    public static final String NO_VIDEO = "An MP4 export needs a video clip on the timeline";

    private static final double DEFAULT_FRAMES_PER_SECOND = 30;

    private final EditPlanner editPlanner;
    private final ClipFiles clipFiles;

    public ExportPlan plan(Project project, ExportFormat format) {
        Map<String, Clip> clips = project.clips().stream()
                .collect(Collectors.toMap(Clip::id, Function.identity(), (first, second) -> first));
        List<Clip> timelineClips = new LinkedHashSet<>(project.edits().timeline()).stream()
                .map(clips::get)
                .filter(Objects::nonNull)
                .toList();
        if (timelineClips.isEmpty()) {
            throw new IllegalArgumentException(NOTHING_LEFT);
        }
        if (timelineClips.stream().anyMatch(clip -> clip.state() != ClipState.READY)) {
            throw new ClipStateConflictException(WAIT_FOR_CLIPS);
        }

        ExportPlan.Video video = format.hasVideo() ? outputVideo(timelineClips) : null;
        EditPlan editPlan = editPlanner.plan(project);

        List<ExportPlan.Input> inputs = new ArrayList<>();
        Map<String, Integer> inputIndexes = new HashMap<>();
        List<ExportPlan.Segment> segments = new ArrayList<>();
        for (KeepSegment keep : editPlan.keepSegments()) {
            ExportPlan.Segment segment = snapped(keep, video, inputIndexes.computeIfAbsent(keep.clipId(), clipId -> {
                inputs.add(inputOf(project, clips.get(clipId)));
                return inputs.size() - 1;
            }));
            if (segment.durationSeconds() > 0) {
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException(NOTHING_LEFT);
        }

        long editedMs = Math.round(segments.stream().mapToDouble(ExportPlan.Segment::durationSeconds).sum() * 1000);
        return new ExportPlan(project.id(), inputs, segments, video, editedMs);
    }

    private ExportPlan.Input inputOf(Project project, Clip clip) {
        String source = clipFiles.source(project.id(), clip.id())
                .orElseThrow(() -> new MediaNotFoundException("The original file of clip " + clip.id() + " is missing"))
                .toString();
        return new ExportPlan.Input(clip.id(), source, Boolean.TRUE.equals(clip.source().hasVideo()));
    }

    private static ExportPlan.Segment snapped(KeepSegment keep, ExportPlan.Video video, int inputIndex) {
        if (video == null) {
            return new ExportPlan.Segment(keep.clipId(), inputIndex, keep.startMs() / 1000.0, keep.endMs() / 1000.0, null, null);
        }
        double rate = video.framesPerSecond();
        long startFrame = Math.round(keep.startMs() * rate / 1000.0);
        long endFrame = Math.round(keep.endMs() * rate / 1000.0);
        return new ExportPlan.Segment(keep.clipId(), inputIndex, startFrame / rate, endFrame / rate, startFrame, endFrame);
    }

    private static ExportPlan.Video outputVideo(List<Clip> timelineClips) {
        ClipSource source = timelineClips.stream()
                .map(Clip::source)
                .filter(candidate -> Boolean.TRUE.equals(candidate.hasVideo()) && candidate.width() != null && candidate.height() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(NO_VIDEO));
        boolean sideways = source.rotation() != null && Math.abs(source.rotation()) % 180 == 90;
        int width = sideways ? source.height() : source.width();
        int height = sideways ? source.width() : source.height();
        double rate = source.fps() != null && source.fps() > 0 ? source.fps() : DEFAULT_FRAMES_PER_SECOND;
        // yuv420p needs even dimensions.
        return new ExportPlan.Video(width - width % 2, height - height % 2, rate);
    }
}
