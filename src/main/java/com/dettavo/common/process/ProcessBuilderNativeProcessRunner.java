package com.dettavo.common.process;

import com.dettavo.common.exception.ToolFailedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a process with {@link ProcessBuilder} and an argument list, never {@code sh -c}.
 * <ul>
 *   <li>The environment is built explicitly: PATH from {@code app.tools.search-path}, plus HOME, USER, TMPDIR and LANG.</li>
 *   <li>Standard output and standard error are read continuously on virtual threads, so a full pipe never freezes the child.</li>
 *   <li>The last 200 standard-error lines are kept for ToolFailedException.</li>
 *   <li>Cancel or timeout: destroy, wait 3 seconds, then destroy forcibly, children included.</li>
 *   <li>The command and every output line go to the specification's log file.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessBuilderNativeProcessRunner implements NativeProcessRunner {

    private static final int STANDARD_ERROR_TAIL_LINES = 200;
    private static final Duration GRACEFUL_STOP = Duration.ofSeconds(3);
    private static final Duration STREAM_DRAIN_LIMIT = Duration.ofSeconds(5);
    private static final long POLL_MILLISECONDS = 100;
    private static final List<String> INHERITED_VARIABLES = List.of("HOME", "USER", "TMPDIR");

    private final ExecutablePathResolver executablePathResolver;

    @Override
    public ProcessResult run(ProcessSpecification specification, CancellationToken cancellationToken) {
        String tool = specification.toolName();
        Path logFile = specification.logFile();
        Deque<String> standardErrorTail = new ArrayDeque<>();
        Instant started = Instant.now();

        try (ProcessLog processLog = ProcessLog.open(logFile)) {
            processLog.line("$ " + String.join(" ", specification.command()));

            Process process;
            try {
                process = processBuilder(specification).start();
                process.getOutputStream().close();
            } catch (IOException e) {
                processLog.line("could not start: " + e.getMessage());
                throw new ToolFailedException(tool, "could not start: " + e.getMessage(), logFile, List.of());
            }

            Thread standardOutputReader = Thread.ofVirtual().name(tool + "-stdout").start(() ->
                    drain(process.getInputStream(), line -> {
                        processLog.line(line);
                        accept(specification.onStandardOutputLine(), line);
                    }));
            Thread standardErrorReader = Thread.ofVirtual().name(tool + "-stderr").start(() ->
                    drain(process.getErrorStream(), line -> {
                        processLog.line(line);
                        synchronized (standardErrorTail) {
                            standardErrorTail.addLast(line);
                            if (standardErrorTail.size() > STANDARD_ERROR_TAIL_LINES) {
                                standardErrorTail.removeFirst();
                            }
                        }
                        accept(specification.onStandardErrorLine(), line);
                    }));

            boolean cancelled = false;
            boolean timedOut = false;
            Instant deadline = specification.timeout() == null ? null : started.plus(specification.timeout());
            try {
                while (!process.waitFor(POLL_MILLISECONDS, TimeUnit.MILLISECONDS)) {
                    if (cancellationToken != null && cancellationToken.isCancelled()) {
                        cancelled = true;
                        stop(process);
                        break;
                    }
                    if (deadline != null && Instant.now().isAfter(deadline)) {
                        timedOut = true;
                        stop(process);
                        break;
                    }
                }
                standardOutputReader.join(STREAM_DRAIN_LIMIT);
                standardErrorReader.join(STREAM_DRAIN_LIMIT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop(process);
                processLog.line("interrupted");
                throw new ToolFailedException(tool, "interrupted", logFile, snapshot(standardErrorTail));
            }

            Duration elapsed = Duration.between(started, Instant.now());
            int exitCode = process.isAlive() ? -1 : process.exitValue();
            List<String> tail = snapshot(standardErrorTail);
            processLog.line("exit " + exitCode + " after " + elapsed.toMillis() + " ms"
                    + (cancelled ? " (cancelled)" : "") + (timedOut ? " (timed out)" : ""));

            if (timedOut) {
                throw new ToolFailedException(tool, "timed out after " + specification.timeout().toSeconds() + " s", logFile, tail);
            }
            if (!cancelled && exitCode != 0) {
                throw new ToolFailedException(tool, "exited with code " + exitCode, logFile, tail);
            }
            return new ProcessResult(exitCode, elapsed, cancelled, tail);
        }
    }

    private ProcessBuilder processBuilder(ProcessSpecification specification) {
        ProcessBuilder builder = new ProcessBuilder(specification.command());
        if (specification.workingDirectory() != null) {
            builder.directory(specification.workingDirectory().toFile());
        }
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", executablePathResolver.searchPathValue());
        environment.put("LANG", "en_US.UTF-8");
        for (String name : INHERITED_VARIABLES) {
            String value = System.getenv(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        return builder;
    }

    /** destroy(), wait 3 seconds, then destroyForcibly() on the process and every descendant. */
    private static void stop(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroy();
        try {
            if (process.waitFor(GRACEFUL_STOP.toMillis(), TimeUnit.MILLISECONDS)) {
                descendants.forEach(ProcessHandle::destroy);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void drain(InputStream stream, Consumer<String> onLine) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                onLine.accept(line);
            }
        } catch (IOException e) {
            // The stream closes when the process is destroyed; nothing more to read.
        }
    }

    private static void accept(Consumer<String> listener, String line) {
        if (listener == null) {
            return;
        }
        try {
            listener.accept(line);
        } catch (RuntimeException e) {
            log.warn("An output listener failed: {}", e.getMessage());
        }
    }

    private static List<String> snapshot(Deque<String> tail) {
        synchronized (tail) {
            return new ArrayList<>(tail);
        }
    }

    /** The per-run log file; does nothing when no file was asked for. */
    private static final class ProcessLog implements AutoCloseable {

        private final BufferedWriter writer;

        private ProcessLog(BufferedWriter writer) {
            this.writer = writer;
        }

        static ProcessLog open(Path logFile) {
            if (logFile == null) {
                return new ProcessLog(null);
            }
            try {
                Files.createDirectories(logFile.getParent());
                return new ProcessLog(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            } catch (IOException e) {
                log.warn("Cannot open process log {}: {}", logFile, e.getMessage());
                return new ProcessLog(null);
            }
        }

        synchronized void line(String text) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(text);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                // Logging must never stop the process.
            }
        }

        @Override
        public synchronized void close() {
            if (writer == null) {
                return;
            }
            try {
                writer.close();
            } catch (IOException e) {
            }
        }
    }
}
