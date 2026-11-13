package com.dettavo.feature.job;

import com.dettavo.feature.project.model.JobSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * A job as the API and the event streams show it (guide 4.7). progress runs from 0 to 1; queuePosition is
 * the job's place among the waiting jobs, 1 first, and is absent once it runs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobResponse(
        UUID id,
        UUID projectId,
        String clipId,
        JobType type,
        JobStatus status,
        Double progress,
        String stage,
        Long etaSeconds,
        Integer queuePosition,
        String error,
        String logFile,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    /** A finished or interrupted job known only from project.json. */
    static JobResponse fromSummary(UUID projectId, JobSummary summary) {
        return new JobResponse(summary.id(), projectId, summary.clipId(), summary.type(), summary.status(), null, null,
                null, null, summary.error(), summary.logFile(), null, summary.startedAt(), summary.finishedAt());
    }

    JobSummary toSummary() {
        return new JobSummary(id, clipId, type, status, error, logFile, startedAt, finishedAt);
    }
}
