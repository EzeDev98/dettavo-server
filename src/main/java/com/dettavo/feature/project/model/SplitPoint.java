package com.dettavo.feature.project.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Where a clip was split on the timeline, on the clip's own source. Splits cut nothing: they divide a clip into
 * pieces that can be selected and deleted one by one, and are kept so the pieces are still there when the project opens.
 */
public record SplitPoint(@NotBlank String clipId, @Min(0) long atMs) {
}
