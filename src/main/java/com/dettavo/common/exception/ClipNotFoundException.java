package com.dettavo.common.exception;

import java.util.UUID;

public class ClipNotFoundException extends RuntimeException {

    public ClipNotFoundException(UUID projectId, String clipId) {
        super("Clip " + clipId + " does not exist in project " + projectId);
    }
}
