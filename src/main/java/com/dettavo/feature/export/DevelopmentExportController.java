package com.dettavo.feature.export;

import com.dettavo.config.ApiPaths;
import com.dettavo.feature.export.dto.ExportRequest;
import com.dettavo.feature.job.JobResponse;
import com.dettavo.feature.project.ProjectStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Development only (Build Plan 17a and 17e): the export plan, and an export whose graph is deliberately short. */
@Profile("dev")
@RestController
@RequiredArgsConstructor
@Tag(name = "Development", description = "Dev profile only")
public class DevelopmentExportController {

    private final ProjectStore projectStore;
    private final ExportPlanner exportPlanner;
    private final FilterGraphBuilder filterGraphBuilder;
    private final ExportService exportService;

    @GetMapping(ApiPaths.PROJECT_EXPORT_PLAN)
    @Operation(summary = "A project's export plan", description = "Inputs, keep segments snapped to the output frame rate "
            + "(with their frame numbers), the output picture and the planned edited length.")
    public ExportPlan plan(@PathVariable UUID projectId, @RequestParam(defaultValue = "MP4") ExportFormat format) {
        return exportPlanner.plan(projectStore.load(projectId), format);
    }

    @GetMapping(value = ApiPaths.PROJECT_EXPORT_PLAN + "/filtergraph", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "The filter graph an export would write", description = "Plain text, as in export-<jobId>.filtergraph.")
    public String filterGraph(@PathVariable UUID projectId, @RequestParam(defaultValue = "MP4") ExportFormat format) {
        return filterGraphBuilder.build(exportPlanner.plan(projectStore.load(projectId), format));
    }

    @PostMapping(ApiPaths.BASE + "/dev/projects/{projectId}/export-broken")
    @Operation(summary = "Export with a broken graph", description = "Leaves the last segment out of the graph, so the job must "
            + "fail with \"Export length does not match the plan\".")
    public ResponseEntity<JobResponse> brokenExport(@PathVariable UUID projectId, @Valid @RequestBody ExportRequest request) {
        return ResponseEntity.accepted().body(exportService.start(projectId, request, true));
    }
}
