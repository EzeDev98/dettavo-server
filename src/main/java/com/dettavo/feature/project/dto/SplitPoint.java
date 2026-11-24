package com.dettavo.feature.project.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SplitPoint(@NotBlank String clipId, @Min(0) long atMs) {
}
