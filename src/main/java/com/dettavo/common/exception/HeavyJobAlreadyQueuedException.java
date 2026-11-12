package com.dettavo.common.exception;

import java.util.UUID;

/** A project may have only one queued or running job. */
public class HeavyJobAlreadyQueuedException extends RuntimeException {

    public HeavyJobAlreadyQueuedException(UUID projectId) {
        super("Project " + projectId + " already has a queued or running job");
    }
}
