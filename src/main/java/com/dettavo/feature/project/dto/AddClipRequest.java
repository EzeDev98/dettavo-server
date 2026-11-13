package com.dettavo.feature.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Registers a file before its upload, so the disk space can be checked first. */
public record AddClipRequest(@NotBlank @Size(max = 255) String fileName, @Positive long sizeBytes) {
}
