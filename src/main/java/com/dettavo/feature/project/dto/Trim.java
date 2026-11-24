package com.dettavo.feature.project.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record Trim(@NotBlank String pauseId, @Min(0) int keepMs) {
}
