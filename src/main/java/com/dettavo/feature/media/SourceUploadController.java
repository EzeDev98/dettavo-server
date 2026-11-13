package com.dettavo.feature.media;

import com.dettavo.config.ApiPaths;
import com.dettavo.feature.project.model.Clip;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Media", description = "Uploading, preparing and serving a clip's media")
public class SourceUploadController {

    public static final String FILE_NAME_HEADER = "X-File-Name";

    private final SourceUploadService sourceUploadService;

    @PutMapping(ApiPaths.CLIP_SOURCE)
    @Operation(summary = "Upload a clip's file", description = "The raw file as the body, with Content-Length and X-File-Name "
            + "(URI-encoded). 202 with the clip, then PREPARE_MEDIA runs. 409 unless the clip is EMPTY; 422 for a file with no audio.")
    public ResponseEntity<Clip> upload(@PathVariable UUID projectId,
                                       @PathVariable String clipId,
                                       @RequestHeader(value = FILE_NAME_HEADER, required = false) String fileName,
                                       HttpServletRequest request) throws IOException {
        Clip clip = sourceUploadService.receive(projectId, clipId, decode(fileName),
                request.getContentLengthLong(), request.getInputStream());
        return ResponseEntity.accepted().body(clip);
    }

    /** The browser sends the name with encodeURIComponent, because a header can only carry ASCII. */
    private static String decode(String fileName) {
        if (fileName == null) {
            return null;
        }
        try {
            return URLDecoder.decode(fileName, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return fileName;
        }
    }
}
