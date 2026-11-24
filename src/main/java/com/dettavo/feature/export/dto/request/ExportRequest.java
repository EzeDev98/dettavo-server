package com.dettavo.feature.export.dto.request;

import com.dettavo.feature.export.enums.ExportFormat;
import com.dettavo.feature.export.enums.ExportQuality;
import jakarta.validation.constraints.NotNull;

/** POST /projects/{id}/export: what to write and how well. */
public record ExportRequest(@NotNull ExportFormat format, @NotNull ExportQuality quality) {
}
