package com.dettavo.feature.project.dto;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One card on the home page: name, clip count, timeline length (the unedited length of the clips on
 * the timeline) and the clip whose thumbnail the card shows, preferring timeline clips.
 */
public record ProjectSummaryResponse(
        UUID id,
        String name,
        int clipCount,
        long timelineDurationMs,
        String thumbnailClipId,
        Instant updatedAt) {

    public static ProjectSummaryResponse from(Project project) {
        Map<String, Clip> clips = project.clips().stream()
                .collect(Collectors.toMap(Clip::id, Function.identity(), (first, second) -> first));
        long timelineDurationMs = project.edits().timeline().stream()
                .map(clips::get)
                .filter(Objects::nonNull)
                .mapToLong(ProjectSummaryResponse::durationOf)
                .sum();
        String thumbnailClipId = Stream.concat(
                        project.edits().timeline().stream().map(clips::get).filter(Objects::nonNull),
                        project.clips().stream())
                .filter(clip -> clip.artifacts() != null && clip.artifacts().thumbnail() != null)
                .map(Clip::id)
                .findFirst()
                .orElse(null);
        return new ProjectSummaryResponse(project.id(), project.name(), project.clips().size(),
                timelineDurationMs, thumbnailClipId, project.updatedAt());
    }

    private static long durationOf(Clip clip) {
        return clip.source() == null || clip.source().durationMs() == null ? 0 : clip.source().durationMs();
    }
}
