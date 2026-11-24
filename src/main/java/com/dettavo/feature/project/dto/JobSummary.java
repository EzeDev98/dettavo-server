package com.dettavo.feature.project.dto;

import com.dettavo.feature.job.enums.JobStatus;
import com.dettavo.feature.job.enums.JobType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/** A job as remembered in project.json, so a restart still shows how the last jobs ended. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobSummary(
        UUID id,
        String clipId,
        JobType type,
        JobStatus status,
        String error,
        String logFile,
        Instant startedAt,
        Instant finishedAt) {

    /** A job the server was running when it stopped. */
    public JobSummary interrupted(String reason, Instant at) {
        return new JobSummary(id, clipId, type, JobStatus.FAILED, reason, logFile, startedAt, at);
    }
}
