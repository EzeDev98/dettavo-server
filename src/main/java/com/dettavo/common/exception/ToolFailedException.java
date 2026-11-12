package com.dettavo.common.exception;

import java.nio.file.Path;
import java.util.List;

/** A native tool exited with an error or ran past its time limit. */
public class ToolFailedException extends RuntimeException {

    private final String tool;
    private final Path logFile;
    private final List<String> standardErrorTail;

    public ToolFailedException(String tool, String message, Path logFile, List<String> standardErrorTail) {
        super(tool + ": " + message);
        this.tool = tool;
        this.logFile = logFile;
        this.standardErrorTail = standardErrorTail == null ? List.of() : List.copyOf(standardErrorTail);
    }

    public String getTool() {
        return tool;
    }

    /** May be null when the run was not logged to a file. */
    public Path getLogFile() {
        return logFile;
    }

    /** The last lines the tool wrote to standard error, oldest first. */
    public List<String> getStandardErrorTail() {
        return standardErrorTail;
    }
}
