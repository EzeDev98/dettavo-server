package com.dettavo.feature.project.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SaveEditsRequest(
        @NotNull List<@NotBlank String> timeline,
        @NotNull List<@NotBlank String> removed,
        @NotNull List<@Valid @NotNull Trim> trims,
        @NotNull @Min(0) @Max(200) Integer padMs,
        List<@Valid @NotNull RangeCut> rangeCuts,
        List<@Valid @NotNull SplitPoint> splits) {

    public SaveEditsRequest {
        rangeCuts = rangeCuts == null ? List.of() : rangeCuts;
        splits = splits == null ? List.of() : splits;
    }
}
