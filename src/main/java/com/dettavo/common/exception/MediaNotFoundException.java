package com.dettavo.common.exception;

public class MediaNotFoundException extends RuntimeException {

    public MediaNotFoundException(String message) {
        super(message);
    }
}
