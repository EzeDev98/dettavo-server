package com.dettavo.feature.job;

public enum JobType {
    PREPARE_MEDIA,
    TRANSCRIBE,
    EXPORT,
    /** Dev profile only: waits and reports progress, so the queue and events can be checked without media. */
    DEVELOPMENT_SLEEP;

    /** Every job but an export belongs to one clip. */
    public boolean isPerClip() {
        return this != EXPORT;
    }
}
