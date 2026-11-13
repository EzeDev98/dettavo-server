package com.dettavo.feature.export.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** One finished export: its file name, size, length (null if it cannot be read) and when it was written. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportFileResponse(String file, long bytes, Long durationMs, Instant createdAt) {
}
