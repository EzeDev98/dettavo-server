package com.dettavo.common.file;

import com.dettavo.config.ApplicationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The workspace on disk (~/Dettavo by default). Creates its folders on startup and resolves
 * every path from it, so no path built from an id or a name can point outside the workspace.
 */
@Slf4j
@Component
public class WorkspacePaths {

    private static final String PROJECTS = "projects";
    private static final String MODELS = "models";
    private static final String LOGS = "logs";

    private final Path root;

    public WorkspacePaths(ApplicationProperties properties) {
        this.root = properties.workspaceDir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve(PROJECTS));
            Files.createDirectories(root.resolve(MODELS));
            Files.createDirectories(root.resolve(LOGS));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the workspace at " + root, e);
        }
        log.info("Workspace ready at {}", root);
    }

    public Path root() {
        return root;
    }

    public Path projectsDirectory() {
        return root.resolve(PROJECTS);
    }

    public Path modelsDirectory() {
        return root.resolve(MODELS);
    }

    public Path logsDirectory() {
        return root.resolve(LOGS);
    }

    /** The folder of one project. The id is a UUID, so it can never contain a path separator. */
    public Path projectDirectory(UUID projectId) {
        return requireInsideWorkspace(projectsDirectory().resolve(projectId.toString()));
    }

    /** Resolves {@code parts} under {@code base} and refuses any result outside the workspace. */
    public Path resolveInside(Path base, String... parts) {
        Path path = base;
        for (String part : parts) {
            path = path.resolve(part);
        }
        return requireInsideWorkspace(path);
    }

    public Path requireInsideWorkspace(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside the workspace: " + path);
        }
        return normalized;
    }
}
