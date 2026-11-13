package com.dettavo.common.exception;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(UUID jobId) {
        super("Job " + jobId + " does not exist");
    }
}
