package com.dettavo.common.exception;

public class InsufficientDiskSpaceException extends RuntimeException {

    public InsufficientDiskSpaceException(long requiredBytes, long availableBytes) {
        super("Not enough disk space: " + requiredBytes + " bytes needed, " + availableBytes + " available");
    }
}
