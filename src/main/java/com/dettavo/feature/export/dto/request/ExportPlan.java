package com.dettavo.feature.export.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportPlan(UUID projectId, List<Input> inputs, List<Segment> segments, Video video, long editedMs) {

    public ExportPlan {
        inputs = List.copyOf(inputs);
        segments = List.copyOf(segments);
    }

    /** One FFmpeg input: a timeline clip's original file. */
    public record Input(String clipId, String source, boolean hasVideo) {
    }

    /**
     * A kept stretch of input inputIndex, in seconds of that clip's own time. With video, startFrame and endFrame
     * are the frame boundaries of the output rate it was snapped to.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Segment(String clipId, int inputIndex, double startSeconds, double endSeconds, Long startFrame, Long endFrame) {

        public double durationSeconds() {
            return endSeconds - startSeconds;
        }
    }

    /** The output picture: the first video clip's upright size, rounded down to even numbers, and its frame rate. */
    public record Video(int width, int height, double framesPerSecond) {
    }

    public ExportPlan withoutLastSegment() {
        List<Segment> shortened = new ArrayList<>(segments);
        Segment last = shortened.removeLast();
        if (shortened.isEmpty()) {
            double middle = last.startSeconds() + last.durationSeconds() / 2;
            shortened.add(new Segment(last.clipId(), last.inputIndex(), last.startSeconds(), middle, null, null));
        }
        return new ExportPlan(projectId, inputs, shortened, video, editedMs);
    }
}
