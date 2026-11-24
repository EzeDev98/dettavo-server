package com.dettavo.feature.project.dto;

import com.dettavo.feature.project.enums.RangeCutKind;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RangeCut(@NotBlank String id, @NotBlank String clipId, @Min(0) long startMs, @Min(0) long endMs,
                       @NotNull RangeCutKind kind) {
}
