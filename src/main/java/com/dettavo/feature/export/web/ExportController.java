package com.dettavo.feature.export.web;

import com.dettavo.config.ApiPaths;
import com.dettavo.feature.export.dto.response.ExportFileResponse;
import com.dettavo.feature.export.dto.request.ExportRequest;
import com.dettavo.feature.job.dto.response.JobResponse;
import com.dettavo.feature.export.service.ExportFileService;
import com.dettavo.feature.export.service.ExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Export", description = "Export the edited timeline as one file, then list, download and reveal exports")
public class ExportController {

    private static final Map<String, MediaType> TYPES = Map.of(
            "mp4", MediaType.parseMediaType("video/mp4"),
            "mp3", MediaType.parseMediaType("audio/mpeg"),
            "wav", MediaType.parseMediaType("audio/wav"));

    private final ExportService exportService;
    private final ExportFileService exportFileService;

    @PostMapping(ApiPaths.PROJECT_EXPORT)
    @Operation(summary = "Export the timeline", description = "Starts EXPORT from the saved edits with {format: MP4, MP3 or WAV, "
            + "quality: STANDARD or HIGH}. 202 with the job; 409 while a timeline clip is not ready or an export is already "
            + "queued; 400 when nothing is left to export.")
    public ResponseEntity<JobResponse> export(@PathVariable UUID projectId, @Valid @RequestBody ExportRequest request) {
        return ResponseEntity.accepted().body(exportService.start(projectId, request));
    }

    @GetMapping(ApiPaths.PROJECT_EXPORTS)
    @Operation(summary = "List finished exports", description = "Newest first: file, bytes, durationMs, createdAt.")
    public List<ExportFileResponse> list(@PathVariable UUID projectId) {
        return exportFileService.list(projectId);
    }

    @GetMapping(ApiPaths.PROJECT_EXPORT_FILE)
    @Operation(summary = "Download an export", description = "With Range support; the file name is in Content-Disposition.")
    public ResponseEntity<Resource> download(@PathVariable UUID projectId, @PathVariable String fileName) {
        Path file = exportFileService.file(projectId, fileName);
        String name = file.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1);
        return ResponseEntity.ok()
                .contentType(TYPES.getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file));
    }

    @PostMapping(ApiPaths.PROJECT_EXPORT_REVEAL)
    @Operation(summary = "Show an export in Finder", description = "Runs /usr/bin/open -R on the file. 204.")
    public ResponseEntity<Void> reveal(@PathVariable UUID projectId, @PathVariable String fileName) {
        exportFileService.reveal(projectId, fileName);
        return ResponseEntity.noContent().build();
    }
}
