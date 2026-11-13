package com.dettavo.feature.edit;

/**
 * One stretch of a timeline clip that is kept (rule 5). startMs and endMs are on the clip's own source,
 * timelineStartMs on the unedited timeline, and editedStartMs is where it starts in the edited result (rule 6).
 */
public record KeepSegment(String clipId, long startMs, long endMs, long timelineStartMs, long editedStartMs) {
}
