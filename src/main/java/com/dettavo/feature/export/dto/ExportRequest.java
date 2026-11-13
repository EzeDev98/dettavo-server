package com.dettavo.feature.export.dto;

import com.dettavo.feature.export.ExportFormat;
import com.dettavo.feature.export.ExportQuality;
import jakarta.validation.constraints.NotNull;

/** POST /projects/{id}/export: what to write and how well. */
public record ExportRequest(@NotNull ExportFormat format, @NotNull ExportQuality quality) {
}
