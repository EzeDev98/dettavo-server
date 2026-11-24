package com.dettavo.feature.job.dto.response;

import com.dettavo.feature.project.dto.JobSummary;
import com.dettavo.feature.job.enums.JobStatus;
import com.dettavo.feature.job.enums.JobType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

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
    public static JobResponse fromSummary(UUID projectId, JobSummary summary) {
        return new JobResponse(summary.id(), projectId, summary.clipId(), summary.type(), summary.status(), null, null,
                null, null, summary.error(), summary.logFile(), null, summary.startedAt(), summary.finishedAt());
    }

    public JobSummary toSummary() {
        return new JobSummary(id, clipId, type, status, error, logFile, startedAt, finishedAt);
    }
}
