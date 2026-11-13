package com.dettavo.common.exception;

/** The clip is not in a state that allows this, such as an upload to a clip that already has its file. */
public class ClipStateConflictException extends RuntimeException {

    public ClipStateConflictException(String message) {
        super(message);
    }
}
