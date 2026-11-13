package com.dettavo.common.exception;

public class InsufficientDiskSpaceException extends RuntimeException {

    private final long requiredBytes;
    private final long availableBytes;

    public InsufficientDiskSpaceException(long requiredBytes, long availableBytes) {
        super("Not enough disk space: " + requiredBytes + " bytes needed, " + availableBytes + " available");
        this.requiredBytes = requiredBytes;
        this.availableBytes = availableBytes;
    }

    public long getRequiredBytes() {
        return requiredBytes;
    }

    public long getAvailableBytes() {
        return availableBytes;
    }
}
