package com.dettavo.feature.media;

import com.dettavo.common.file.WorkspaceCleanup;
import com.dettavo.common.file.WorkspacePaths;
import com.dettavo.feature.project.ProjectStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Every path inside a clip's folder (guide 4.4): source/<sanitized-name>, which is never modified, and the
 * rebuildable cache/. Outputs are written to a .partial file and renamed only when complete.
 */
@Component
@RequiredArgsConstructor
public class ClipFiles {

    public static final String SOURCE_FOLDER = "source";
    public static final String CACHE_FOLDER = "cache";
    public static final String LOGS_FOLDER = "logs";

    public static final String AUDIO_16K = CACHE_FOLDER + "/audio16k.wav";
    public static final String THUMBNAIL = CACHE_FOLDER + "/thumb.jpg";
    public static final String PROXY_VIDEO = CACHE_FOLDER + "/proxy.mp4";
    public static final String PROXY_AUDIO = CACHE_FOLDER + "/proxy.m4a";

    private final ProjectStore projectStore;
    private final WorkspacePaths workspacePaths;

    public Path sourceDirectory(UUID projectId, String clipId) {
        return workspacePaths.resolveInside(projectStore.clipDirectory(projectId, clipId), SOURCE_FOLDER);
    }

    /** The uploaded file, if the upload finished. */
    public Optional<Path> source(UUID projectId, String clipId) {
        Path directory = sourceDirectory(projectId, clipId);
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().endsWith(WorkspaceCleanup.PARTIAL_SUFFIX))
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + directory, e);
        }
    }

    public Path cacheDirectory(UUID projectId, String clipId) {
        return workspacePaths.resolveInside(projectStore.clipDirectory(projectId, clipId), CACHE_FOLDER);
    }

    /** A cache file named in the clip's artifacts, such as cache/proxy.mp4. */
    public Path artifact(UUID projectId, String clipId, String relativePath) {
        return workspacePaths.resolveInside(projectStore.clipDirectory(projectId, clipId), relativePath);
    }

    /** projects/<id>/logs/<jobId>.log, which gets every command a job runs and all its output. */
    public Path jobLog(UUID projectId, UUID jobId) {
        Path logs = workspacePaths.resolveInside(workspacePaths.projectDirectory(projectId), LOGS_FOLDER);
        createDirectories(logs);
        return workspacePaths.resolveInside(logs, jobId + ".log");
    }

    public static Path partialOf(Path target) {
        return target.resolveSibling(target.getFileName() + WorkspaceCleanup.PARTIAL_SUFFIX);
    }

    public static void commit(Path partial, Path target) {
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot move " + partial + " to " + target, e);
        }
    }

    public static void discard(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Removed on the next startup.
        }
    }

    public static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + directory, e);
        }
    }
}
