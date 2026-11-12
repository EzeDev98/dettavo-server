package com.dettavo.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into a ProblemDetail (RFC 9457) body with an extra {@code code}.
 * Spring's own errors (unknown path, bad JSON, failed validation, wrong method) keep their
 * status and gain a code here too.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String CODE = "code";

    @ExceptionHandler(ProjectNotFoundException.class)
    public ProblemDetail handleProjectNotFound(ProjectNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.PROJECT_NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(RevisionConflictException.class)
    public ProblemDetail handleRevisionConflict(RevisionConflictException exception) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, ErrorCode.REVISION_CONFLICT, exception.getMessage());
        problem.setProperty("currentRevision", exception.getCurrentRevision());
        return problem;
    }

    @ExceptionHandler(HeavyJobAlreadyQueuedException.class)
    public ProblemDetail handleHeavyJobAlreadyQueued(HeavyJobAlreadyQueuedException exception) {
        return problem(HttpStatus.CONFLICT, ErrorCode.HEAVY_JOB_ALREADY_QUEUED, exception.getMessage());
    }

    @ExceptionHandler(InsufficientDiskSpaceException.class)
    public ProblemDetail handleInsufficientDiskSpace(InsufficientDiskSpaceException exception) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.INSUFFICIENT_DISK_SPACE, exception.getMessage());
    }

    @ExceptionHandler(UnsupportedMediaException.class)
    public ProblemDetail handleUnsupportedMedia(UnsupportedMediaException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.UNSUPPORTED_MEDIA, exception.getMessage());
    }

    @ExceptionHandler(ToolFailedException.class)
    public ProblemDetail handleToolFailed(ToolFailedException exception) {
        log.error("Tool failed: {}", exception.getMessage());
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.TOOL_FAILED, exception.getMessage());
        problem.setProperty("tool", exception.getTool());
        if (exception.getLogFile() != null) {
            problem.setProperty("logFile", exception.getLogFile().toString());
        }
        return problem;
    }

    @ExceptionHandler(PairingKeyInvalidException.class)
    public ProblemDetail handlePairingKeyInvalid(PairingKeyInvalidException exception) {
        return problem(HttpStatus.UNAUTHORIZED, ErrorCode.PAIRING_KEY_INVALID, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unexpected error", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Unexpected error; see the server log");
    }

    /** Adds a code to the ProblemDetail Spring builds for its own exceptions. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey(CODE))) {
            problem.setProperty(CODE, codeFor(statusCode).name());
        }
        return super.handleExceptionInternal(exception, body, headers, statusCode, request);
    }

    public static ProblemDetail problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty(CODE, code.name());
        return problem;
    }

    private static ErrorCode codeFor(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400 -> ErrorCode.INVALID_REQUEST;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_CONTENT_TYPE;
            default -> statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
        };
    }
}
