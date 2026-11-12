package com.dettavo.common.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * One native process to run: an absolute program path plus its arguments (never a shell
 * string), where to run it, how long it may take, who receives each output line, and the log
 * file that gets the command and every line of output.
 *
 * @param workingDirectory    null to use the server's own working directory
 * @param timeout             null for no limit
 * @param onStandardOutputLine null when nobody needs the lines as they arrive
 * @param onStandardErrorLine  null when nobody needs the lines as they arrive
 * @param logFile             null to skip the log file
 */
public record ProcessSpecification(List<String> command,
                                   Path workingDirectory,
                                   Duration timeout,
                                   Consumer<String> onStandardOutputLine,
                                   Consumer<String> onStandardErrorLine,
                                   Path logFile) {

    public ProcessSpecification {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("A process needs a command");
        }
        command = List.copyOf(command);
    }

    /** A short run with no listeners and no log file, such as {@code ffmpeg -version}. */
    public static ProcessSpecification of(List<String> command, Duration timeout) {
        return new ProcessSpecification(command, null, timeout, null, null, null);
    }

    /** The program's file name, used in errors and logs. */
    public String toolName() {
        return Path.of(command.getFirst()).getFileName().toString();
    }
}
