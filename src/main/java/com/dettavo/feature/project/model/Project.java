package com.dettavo.feature.project.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One project, exactly as stored in projects/<id>/project.json (guide 4.9). It holds everything that
 * cannot be rebuilt: the clips with their sources and transcripts, the timeline and the edits.
 * A project has no state of its own.
 */
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Project(
        int schemaVersion,
        UUID id,
        String name,
        Instant createdAt,
        Instant updatedAt,
        int nextClipNumber,
        List<Clip> clips,
        Edits edits,
        /* The last 20 job summaries; any left RUNNING are marked FAILED on startup. */
        List<JobSummary> jobs) {

    public static final int SCHEMA_VERSION = 2;

    public Project {
        clips = clips == null ? List.of() : List.copyOf(clips);
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }

    public Optional<Clip> findClip(String clipId) {
        return clips.stream().filter(clip -> clip.id().equals(clipId)).findFirst();
    }
}
