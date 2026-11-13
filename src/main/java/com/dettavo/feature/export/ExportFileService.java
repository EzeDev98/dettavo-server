package com.dettavo.feature.export;

import com.dettavo.common.exception.UnsupportedMediaException;
import com.dettavo.common.process.CancellationToken;
import com.dettavo.common.process.NativeProcessRunner;
import com.dettavo.common.process.ProcessSpecification;
import com.dettavo.feature.export.dto.ExportFileResponse;
import com.dettavo.feature.media.MediaProbeService;
import com.dettavo.feature.project.ProjectStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Finished exports (Build Plan 17g): the list with each file's length, the file to download, and Show in Finder. */
@Service
@RequiredArgsConstructor
public class ExportFileService {

    private static final String OPEN = "/usr/bin/open";
    private static final Duration REVEAL_TIMEOUT = Duration.ofSeconds(10);

    private final ProjectStore projectStore;
    private final ExportFiles exportFiles;
    private final MediaProbeService mediaProbeService;
    private final NativeProcessRunner nativeProcessRunner;

    public List<ExportFileResponse> list(UUID projectId) {
        projectStore.load(projectId);
        return exportFiles.list(projectId).stream()
                .map(file -> new ExportFileResponse(file.getFileName().toString(), size(file), durationMs(file), ExportFiles.createdAt(file)))
                .toList();
    }

    public Path file(UUID projectId, String fileName) {
        projectStore.load(projectId);
        return exportFiles.existing(projectId, fileName);
    }

    /** open -R selects the file in a Finder window. The path is resolved inside the workspace first. */
    public void reveal(UUID projectId, String fileName) {
        Path file = file(projectId, fileName);
        nativeProcessRunner.run(ProcessSpecification.of(List.of(OPEN, "-R", file.toString()), REVEAL_TIMEOUT), CancellationToken.none());
    }

    private Long durationMs(Path file) {
        try {
            return mediaProbeService.probe(file, null, CancellationToken.none()).durationMs();
        } catch (UnsupportedMediaException e) {
            return null;
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
