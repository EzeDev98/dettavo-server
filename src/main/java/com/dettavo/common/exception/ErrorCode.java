package com.dettavo.common.exception;

/** The {@code code} carried by every error body, so the client can react without parsing text. */
public enum ErrorCode {
    INVALID_REQUEST,
    NOT_PAIRED,
    PAIRING_KEY_INVALID,
    HOST_NOT_ALLOWED,
    ORIGIN_NOT_ALLOWED,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    UNSUPPORTED_CONTENT_TYPE,
    PROJECT_NOT_FOUND,
    REVISION_CONFLICT,
    HEAVY_JOB_ALREADY_QUEUED,
    INSUFFICIENT_DISK_SPACE,
    UNSUPPORTED_MEDIA,
    TOOL_FAILED,
    INTERNAL_ERROR
}
