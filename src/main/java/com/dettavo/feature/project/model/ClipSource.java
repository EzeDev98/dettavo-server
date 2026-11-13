package com.dettavo.feature.project.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/**
 * The uploaded file. When the clip is added only fileName and bytes are known; the probe (Step 8b)
 * fills in the rest.
 */
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClipSource(
        String fileName,
        long bytes,
        Long durationMs,
        Boolean hasVideo,
        String container,
        String videoCodec,
        String audioCodec,
        Integer width,
        Integer height,
        Double fps,
        Integer rotation,
        Integer sampleRate,
        Integer channels) {
}
