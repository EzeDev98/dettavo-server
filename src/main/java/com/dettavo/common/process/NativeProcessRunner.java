package com.dettavo.common.process;

/**
 * Runs every child process the server starts, so safety, output draining, cancellation,
 * time limits and logging are written once.
 */
public interface NativeProcessRunner {

    /**
     * Runs the process to the end.
     *
     * @return the result of a successful or cancelled run
     * @throws com.dettavo.common.exception.ToolFailedException when the process cannot start,
     *         exits with a non-zero code, or runs past its timeout
     */
    ProcessResult run(ProcessSpecification specification, CancellationToken cancellationToken);
}
