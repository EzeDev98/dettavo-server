package com.dettavo.feature.project.web;

import com.dettavo.config.ApiPaths;
import com.dettavo.feature.project.service.ClipService;
import com.dettavo.feature.project.service.Revisions;
import com.dettavo.feature.project.dto.AddClipRequest;
import com.dettavo.feature.project.dto.Clip;
import com.dettavo.feature.project.dto.Project;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Clips", description = "Add and delete the clips of a project")
public class ClipController {

    private final ClipService clipService;

    @PostMapping(ApiPaths.PROJECT_CLIPS)
    @Operation(summary = "Add a clip", description = "Registers a file from {fileName, sizeBytes} before its upload. 413 if the disk lacks "
            + "sizeBytes x 1.5 plus the reserve. 201 with the clip, c01 upward; it starts EMPTY, in the tray.")
    public ResponseEntity<Clip> add(@PathVariable UUID projectId, @Valid @RequestBody AddClipRequest request) {
        Clip clip = clipService.add(projectId, request);
        return ResponseEntity.created(URI.create(ApiPaths.PROJECTS + "/" + projectId + "/clips/" + clip.id())).body(clip);
    }

    @DeleteMapping(ApiPaths.PROJECT_CLIP)
    @Operation(summary = "Delete a clip", description = "Cancels the clip's jobs, deletes its folder, takes it off the timeline and drops its edits. "
            + "204; the ETag holds the edits revision, which goes up when the edits changed.")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable String clipId) {
        Project project = clipService.delete(projectId, clipId);
        return ResponseEntity.noContent().eTag(Revisions.tag(project)).build();
    }
}
