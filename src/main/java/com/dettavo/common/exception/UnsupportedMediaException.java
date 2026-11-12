package com.dettavo.common.exception;

/** The file is not audio or video FFmpeg can read. */
public class UnsupportedMediaException extends RuntimeException {

    public UnsupportedMediaException(String message) {
        super(message);
    }
}
