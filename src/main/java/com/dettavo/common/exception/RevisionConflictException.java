package com.dettavo.common.exception;

/** The client saved against a revision that is no longer the latest. */
public class RevisionConflictException extends RuntimeException {

    private final long currentRevision;

    public RevisionConflictException(long expectedRevision, long currentRevision) {
        super("The project changed since revision " + expectedRevision + "; the latest is " + currentRevision);
        this.currentRevision = currentRevision;
    }

    public long getCurrentRevision() {
        return currentRevision;
    }
}
