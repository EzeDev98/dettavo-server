package com.dettavo.feature.project;

import com.dettavo.config.ApiPaths;
import com.dettavo.feature.project.dto.CreateProjectRequest;
import com.dettavo.feature.project.dto.ProjectSummaryResponse;
import com.dettavo.feature.project.dto.RenameProjectRequest;
import com.dettavo.feature.project.dto.SaveEditsRequest;
import com.dettavo.feature.project.dto.SaveEditsResponse;
import com.dettavo.feature.project.model.Project;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Create, list, open, rename and delete projects, and save their edits")
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping(ApiPaths.PROJECTS)
    @Operation(summary = "List projects", description = "Home page cards, most recently changed first.")
    public List<ProjectSummaryResponse> list() {
        return projectService.list();
    }

    @PostMapping(ApiPaths.PROJECTS)
    @Operation(summary = "Create a project", description = "An empty project from {name}. Clips are added afterwards. 201 with the project.")
    public ResponseEntity<Project> create(@Valid @RequestBody CreateProjectRequest request) {
        Project project = projectService.create(request.name());
        return ResponseEntity.created(URI.create(ApiPaths.PROJECTS + "/" + project.id()))
                .eTag(Revisions.tag(project))
                .body(project);
    }

    @GetMapping(ApiPaths.PROJECT)
    @Operation(summary = "Open a project", description = "The full project.json. The ETag holds the edits revision for the next save.")
    public ResponseEntity<Project> get(@PathVariable UUID projectId) {
        Project project = projectService.get(projectId);
        return ResponseEntity.ok().eTag(Revisions.tag(project)).body(project);
    }

    @PatchMapping(ApiPaths.PROJECT)
    @Operation(summary = "Rename a project")
    public ResponseEntity<Project> rename(@PathVariable UUID projectId, @Valid @RequestBody RenameProjectRequest request) {
        Project project = projectService.rename(projectId, request.name());
        return ResponseEntity.ok().eTag(Revisions.tag(project)).body(project);
    }

    @DeleteMapping(ApiPaths.PROJECT)
    @Operation(summary = "Delete a project", description = "Deletes the project's folder with its clips and exports. 204.")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId) {
        projectService.delete(projectId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(ApiPaths.PROJECT_EDITS)
    @Operation(summary = "Save edits", description = "Saves {timeline, removed, trims, padMs}. If-Match must hold the current revision: "
            + "409 REVISION_CONFLICT if another window saved first, 400 for a timeline or token that names an unknown clip.")
    public ResponseEntity<SaveEditsResponse> saveEdits(@PathVariable UUID projectId,
                                                       @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                       @Valid @RequestBody SaveEditsRequest request) {
        Project project = projectService.saveEdits(projectId, Revisions.parseIfMatch(ifMatch), request);
        return ResponseEntity.ok()
                .eTag(Revisions.tag(project))
                .body(new SaveEditsResponse(project.edits().revision()));
    }
}
