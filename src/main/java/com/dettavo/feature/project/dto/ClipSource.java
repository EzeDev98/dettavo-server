package com.dettavo.feature.project.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

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
