package com.dettavo.feature.job;

/** QUEUED -> RUNNING -> SUCCEEDED, FAILED or CANCELLED; QUEUED -> CANCELLED (guide 4.7). */
public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}
