package com.dettavo.feature.media;

import com.dettavo.common.exception.ClipNotFoundException;
import com.dettavo.common.exception.MediaNotFoundException;
import com.dettavo.config.ApiPaths;
import com.dettavo.feature.job.JobResponse;
import com.dettavo.feature.media.dto.FilmstripResponse;
import com.dettavo.feature.project.ProjectStore;
import com.dettavo.feature.project.model.Clip;
import com.dettavo.feature.project.model.ClipArtifacts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Function;

@RestController
@RequiredArgsConstructor
@Tag(name = "Media", description = "Uploading, preparing and serving a clip's media")
public class MediaController {

    private static final MediaType VIDEO_MP4 = MediaType.parseMediaType("video/mp4");
    private static final MediaType AUDIO_MP4 = MediaType.parseMediaType("audio/mp4");

    private final ProjectStore projectStore;
    private final ClipFiles clipFiles;
    private final ClipPreparationService clipPreparationService;
    private final TimelineMediaService timelineMediaService;

    @PostMapping(ApiPaths.CLIP_PREPARE)
    @Operation(summary = "Rebuild a clip's cache", description = "Deletes cache/ and runs PREPARE_MEDIA again; the transcript is kept. "
            + "202 with the job; 409 if the clip has no file yet or already has a job.")
    public ResponseEntity<JobResponse> prepare(@PathVariable UUID projectId, @PathVariable String clipId) {
        return ResponseEntity.accepted().body(clipPreparationService.rebuild(projectId, clipId));
    }

    @GetMapping(ApiPaths.CLIP_PROXY)
    @Operation(summary = "A clip's playback copy", description = "proxy.mp4, or proxy.m4a for an audio clip, with Range support for seeking.")
    public ResponseEntity<Resource> proxy(@PathVariable UUID projectId, @PathVariable String clipId, WebRequest request) {
        Path file = artifactFile(projectId, clipId, ClipArtifacts::proxy, "playback copy");
        MediaType type = file.getFileName().toString().endsWith(".m4a") ? AUDIO_MP4 : VIDEO_MP4;
        return serve(file, type, request);
    }

    @GetMapping(ApiPaths.CLIP_THUMBNAIL)
    @Operation(summary = "A video clip's thumbnail", description = "thumb.jpg, revalidated by ETag.")
    public ResponseEntity<Resource> thumbnail(@PathVariable UUID projectId, @PathVariable String clipId, WebRequest request) {
        return serve(artifactFile(projectId, clipId, ClipArtifacts::thumbnail, "thumbnail"), MediaType.IMAGE_JPEG, request);
    }

    @GetMapping(ApiPaths.CLIP_LEVELS)
    @Operation(summary = "A clip's audio level", description = "levels.bin: one byte per frame of 1000 / app.peaks.per-second ms, "
            + "the RMS level from -60 dB (0) to 0 dB (255). Drawn as the timeline's waveform and used to find silences.")
    public ResponseEntity<Resource> levels(@PathVariable UUID projectId, @PathVariable String clipId, WebRequest request) {
        return serve(timelineMediaService.levels(projectId, clipId), MediaType.APPLICATION_OCTET_STREAM, request);
    }

    @GetMapping(ApiPaths.CLIP_FILMSTRIP)
    @Operation(summary = "A video clip's filmstrip layout", description = "Starts making the filmstrip if it is not there yet; "
            + "status is BUILDING until the image can be fetched, then READY.")
    public FilmstripResponse filmstrip(@PathVariable UUID projectId, @PathVariable String clipId) {
        return timelineMediaService.filmstrip(projectId, clipId);
    }

    @GetMapping(ApiPaths.CLIP_FILMSTRIP_IMAGE)
    @Operation(summary = "A video clip's filmstrip image", description = "filmstrip.jpg, revalidated by ETag.")
    public ResponseEntity<Resource> filmstripImage(@PathVariable UUID projectId, @PathVariable String clipId, WebRequest request) {
        return serve(timelineMediaService.filmstripImage(projectId, clipId), MediaType.IMAGE_JPEG, request);
    }

    private Path artifactFile(UUID projectId, String clipId, Function<ClipArtifacts, String> pick, String what) {
        Clip clip = projectStore.load(projectId).findClip(clipId)
                .orElseThrow(() -> new ClipNotFoundException(projectId, clipId));
        String relativePath = clip.artifacts() == null ? null : pick.apply(clip.artifacts());
        if (relativePath == null) {
            throw new MediaNotFoundException("Clip " + clipId + " has no " + what + " yet");
        }
        Path file = clipFiles.artifact(projectId, clipId, relativePath);
        if (!Files.isRegularFile(file)) {
            throw new MediaNotFoundException("The " + what + " of clip " + clipId + " is missing; prepare the clip again");
        }
        return file;
    }

    /** Spring answers a Range header on a Resource body with 206 and Content-Range. */
    private static ResponseEntity<Resource> serve(Path file, MediaType type, WebRequest request) {
        String eTag = eTagOf(file);
        if (request.checkNotModified(eTag)) {
            return null;
        }
        return ResponseEntity.ok()
                .eTag(eTag)
                .cacheControl(CacheControl.noCache())
                .contentType(type)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(new FileSystemResource(file));
    }

    private static String eTagOf(Path file) {
        try {
            return Files.size(file) + "-" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
