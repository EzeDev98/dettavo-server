package com.dettavo.common.exception;

/** A clip's proxy or thumbnail has not been made yet, or its cache was cleared. */
public class MediaNotFoundException extends RuntimeException {

    public MediaNotFoundException(String message) {
        super(message);
    }
}
