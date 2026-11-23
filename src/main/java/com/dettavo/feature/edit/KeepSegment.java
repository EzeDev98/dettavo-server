package com.dettavo.feature.edit;

public record KeepSegment(String clipId, long startMs, long endMs, long timelineStartMs, long editedStartMs) {
}
