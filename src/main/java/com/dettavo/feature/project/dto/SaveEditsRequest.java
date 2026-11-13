package com.dettavo.feature.project.dto;

import com.dettavo.feature.project.model.Trim;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** The whole edit state, saved together. padMs is 0 to 200 (rule 4). */
public record SaveEditsRequest(
        @NotNull List<@NotBlank String> timeline,
        @NotNull List<@NotBlank String> removed,
        @NotNull List<@Valid @NotNull Trim> trims,
        @NotNull @Min(0) @Max(200) Integer padMs) {
}
