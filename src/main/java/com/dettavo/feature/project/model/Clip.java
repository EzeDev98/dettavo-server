package com.dettavo.feature.project.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;

/**
 * One added file. A clip that is not in edits.timeline sits only in the Clips tray. Times inside a
 * clip are integer milliseconds on that clip's own source timeline.
 */
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Clip(
        String id,
        ClipState state,
        Instant addedAt,
        ClipSource source,
        ClipArtifacts artifacts,
        Transcript transcript,
        /* Why the uploaded file was refused ("This file has no audio track"), for its tile (guide 9); else null. */
        String failure) {

    /** Clip ids are c01, c02 and upward within a project and are never reused. */
    public static String idFor(int number) {
        return String.format("c%02d", number);
    }
}
