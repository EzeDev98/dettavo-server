package com.dettavo.common.process;

import com.dettavo.config.ApplicationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Finds a program's absolute path: the configured path when one is set, otherwise the first
 * match in {@code app.tools.search-path}. Processes started outside a terminal often get a
 * short PATH, so the server never relies on the inherited one.
 */
@Component
public class ExecutablePathResolver {

    private final List<String> searchPath;

    public ExecutablePathResolver(ApplicationProperties properties) {
        this.searchPath = List.copyOf(properties.tools().searchPath());
    }

    public Optional<Path> resolve(String configuredPath, String executableName) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path configured = Path.of(configuredPath.trim()).toAbsolutePath().normalize();
            return Files.isRegularFile(configured) && Files.isExecutable(configured) ? Optional.of(configured) : Optional.empty();
        }
        for (String directory : searchPath) {
            Path candidate = Path.of(directory.trim(), executableName);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    /** The PATH given to every child process. */
    public String searchPathValue() {
        return String.join(":", searchPath);
    }
}
