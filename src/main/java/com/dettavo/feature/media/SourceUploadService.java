package com.dettavo.feature.media;

import com.dettavo.common.exception.ClipStateConflictException;
import com.dettavo.common.exception.UnsupportedMediaException;
import com.dettavo.common.file.FileNameSanitizer;
import com.dettavo.common.file.WorkspacePaths;
import com.dettavo.common.process.CancellationToken;
import com.dettavo.feature.project.ProjectStore;
import com.dettavo.feature.project.model.Clip;
import com.dettavo.feature.project.model.ClipState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The streamed upload (guide 5.1): the raw request body goes to source/<sanitized-name>.partial through a
 * 1 MB buffer, is counted against Content-Length, renamed, probed, and then PREPARE_MEDIA is queued. If the
 * upload stops, the .partial file is deleted and the clip returns to EMPTY ("Upload stopped", Try again).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceUploadService {

    private static final int BUFFER_BYTES = 1024 * 1024;

    private final ProjectStore projectStore;
    private final ClipFiles clipFiles;
    private final WorkspacePaths workspacePaths;
    private final MediaProbeService mediaProbeService;
    private final ClipPreparationService clipPreparationService;

    public Clip receive(UUID projectId, String clipId, String fileName, long contentLength, InputStream body) {
        if (contentLength <= 0) {
            throw new IllegalArgumentException("The upload needs a Content-Length header with the file's size");
        }
        Clip clip = projectStore.updateClip(projectId, clipId, current -> {
            if (current.state() != ClipState.EMPTY) {
                throw new ClipStateConflictException("Clip " + clipId + " is " + current.state() + "; only an empty clip can receive its file");
            }
            return current.toBuilder().state(ClipState.UPLOADING).build();
        });

        String storedName = FileNameSanitizer.sanitize(fileName == null || fileName.isBlank() ? clip.source().fileName() : fileName);
        Path directory = clipFiles.sourceDirectory(projectId, clipId);
        projectStore.deleteRecursively(directory);
        ClipFiles.createDirectories(directory);
        Path target = workspacePaths.resolveInside(directory, storedName);
        Path partial = ClipFiles.partialOf(target);

        long received;
        try (OutputStream output = Files.newOutputStream(partial)) {
            received = copy(body, output);
        } catch (IOException e) {
            abandon(projectId, clipId, partial);
            throw new UncheckedIOException("The upload of clip " + clipId + " stopped", e);
        }
        if (received != contentLength) {
            abandon(projectId, clipId, partial);
            throw new IllegalArgumentException("The upload stopped after " + received + " of " + contentLength + " bytes");
        }
        ClipFiles.commit(partial, target);

        try {
            mediaProbeService.probe(target, null, CancellationToken.none());
        } catch (UnsupportedMediaException e) {
            projectStore.updateClip(projectId, clipId, current -> current.toBuilder().state(ClipState.FAILED).build());
            throw e;
        }

        long bytes = received;
        Clip uploaded = projectStore.updateClip(projectId, clipId, current -> current.toBuilder()
                .state(ClipState.PREPARING)
                .source(current.source().toBuilder().bytes(bytes).build())
                .build());
        clipPreparationService.queue(projectId, clipId);
        log.info("Project {}: clip {} uploaded ({} bytes as {})", projectId, clipId, bytes, storedName);
        return uploaded;
    }

    private void abandon(UUID projectId, String clipId, Path partial) {
        ClipFiles.discard(partial);
        try {
            projectStore.updateClip(projectId, clipId, current -> current.toBuilder().state(ClipState.EMPTY).build());
        } catch (RuntimeException e) {
            log.warn("Could not reset clip {} of project {} after a stopped upload: {}", clipId, projectId, e.getMessage());
        }
        log.warn("Project {}: upload of clip {} stopped", projectId, clipId);
    }

    private static long copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }
}
