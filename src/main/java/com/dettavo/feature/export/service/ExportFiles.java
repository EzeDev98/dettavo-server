package com.dettavo.feature.export.service;

import com.dettavo.common.exception.MediaNotFoundException;
import com.dettavo.common.file.FileNameSanitizer;
import com.dettavo.common.file.WorkspaceCleanup;
import com.dettavo.common.file.WorkspacePaths;
import com.dettavo.feature.media.service.ClipFiles;
import com.dettavo.feature.export.enums.ExportFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class ExportFiles {

    static final String EXPORTS_FOLDER = "exports";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String PARTIAL = ".partial";

    private final WorkspacePaths workspacePaths;

    public Path directory(UUID projectId) {
        return workspacePaths.resolveInside(workspacePaths.projectDirectory(projectId), EXPORTS_FOLDER);
    }

    public Path graphFile(UUID projectId, UUID jobId) {
        String name = "export-" + jobId + WorkspaceCleanup.FILTER_GRAPH_SUFFIX;
        return workspacePaths.resolveInside(workspacePaths.projectDirectory(projectId), name);
    }

    /** A new export's path, named after the project with the time, so an export never overwrites an earlier one. */
    public Path newExport(UUID projectId, String projectName, ExportFormat format) {
        Path directory = directory(projectId);
        ClipFiles.createDirectories(directory);
        String name = FileNameSanitizer.sanitize(projectName + "-edited-" + LocalDateTime.now().format(STAMP) + "." + format.extension());
        return workspacePaths.resolveInside(directory, name);
    }

    /** Finished exports, newest first. Files still being written (.partial) are left out. */
    public List<Path> list(UUID projectId) {
        Path directory = directory(projectId);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().endsWith(PARTIAL))
                    .sorted(Comparator.comparing(ExportFiles::createdAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + directory, e);
        }
    }

    /** A finished export by its file name, which must be a plain name inside the exports folder. */
    public Path existing(UUID projectId, String fileName) {
        if (fileName == null || !fileName.equals(FileNameSanitizer.sanitize(fileName)) || fileName.endsWith(PARTIAL)) {
            throw new MediaNotFoundException("No export named " + fileName);
        }
        Path file = workspacePaths.resolveInside(directory(projectId), fileName);
        if (!Files.isRegularFile(file)) {
            throw new MediaNotFoundException("No export named " + fileName);
        }
        return file;
    }

    public static Instant createdAt(Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class).creationTime().toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
