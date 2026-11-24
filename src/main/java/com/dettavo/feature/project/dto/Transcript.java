package com.dettavo.feature.project.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

/** A clip's words and paragraphs, with the engine and model that produced them. */
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Transcript(
        String engine,
        String model,
        String language,
        Instant generatedAt,
        List<Word> words,
        List<Paragraph> paragraphs) {

    public Transcript {
        words = words == null ? List.of() : List.copyOf(words);
        paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
    }
}
