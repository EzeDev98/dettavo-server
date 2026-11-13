package com.dettavo.feature.project.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** A pause shortened to keepMs, centred in the pause (rule 3). */
public record Trim(@NotBlank String pauseId, @Min(0) int keepMs) {
}
