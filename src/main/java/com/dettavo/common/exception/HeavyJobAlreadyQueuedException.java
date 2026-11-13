package com.dettavo.common.exception;

/** A clip may have one queued or running PREPARE_MEDIA or TRANSCRIBE job, and a project one EXPORT. */
public class HeavyJobAlreadyQueuedException extends RuntimeException {

    public HeavyJobAlreadyQueuedException(String message) {
        super(message);
    }
}
