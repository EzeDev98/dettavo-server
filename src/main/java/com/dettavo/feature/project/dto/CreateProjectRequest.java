package com.dettavo.feature.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Creates an empty project; clips are added afterwards. */
public record CreateProjectRequest(@NotBlank @Size(max = 120) String name) {
}
