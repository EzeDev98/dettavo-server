package com.dettavo.feature.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameProjectRequest(@NotBlank @Size(max = 120) String name) {
}
