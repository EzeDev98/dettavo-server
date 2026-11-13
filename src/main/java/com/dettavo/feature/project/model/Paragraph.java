package com.dettavo.feature.project.model;

/** A run of words shown as one paragraph in the transcript pane. */
public record Paragraph(String id, String firstWordId, String lastWordId) {
}
