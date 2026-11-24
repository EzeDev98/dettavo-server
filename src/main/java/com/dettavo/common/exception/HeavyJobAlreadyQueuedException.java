package com.dettavo.common.exception;

public class HeavyJobAlreadyQueuedException extends RuntimeException {

    public HeavyJobAlreadyQueuedException(String message) {
        super(message);
    }
}
