package com.dettavo.common.exception;

import java.util.UUID;

public class ProjectNotFoundException extends RuntimeException {

    public ProjectNotFoundException(UUID projectId) {
        super("Project " + projectId + " does not exist");
    }
}
