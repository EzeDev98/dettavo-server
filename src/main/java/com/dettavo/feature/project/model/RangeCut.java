package com.dettavo.feature.project.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A stretch of one clip cut by time rather than by words (guide 4.9, rule 9): a piece deleted on the timeline
 * (MANUAL), or a silence found in the audio level (SILENCE). startMs and endMs are on the clip's own source and are
 * cut exactly, with no padding. id is the client's, so the stretch can be restored on its own.
 */
public record RangeCut(@NotBlank String id, @NotBlank String clipId, @Min(0) long startMs, @Min(0) long endMs,
                       @NotNull RangeCutKind kind) {
}
