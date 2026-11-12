package com.dettavo.common.process;

import java.time.Duration;
import java.util.List;

/**
 * How a process ended. A non-zero exit or a timeout is thrown as ToolFailedException instead,
 * so a returned result is either a success or a cancellation.
 *
 * @param standardErrorTail the last 200 lines the process wrote to standard error, oldest first
 */
public record ProcessResult(int exitCode, Duration elapsed, boolean cancelled, List<String> standardErrorTail) {
}
