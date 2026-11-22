package com.dettavo.feature.edit;

import com.dettavo.config.ApiPaths;
import com.dettavo.feature.project.ProjectStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Profile("dev")
@RestController
@RequiredArgsConstructor
@Tag(name = "Development", description = "Dev profile only")
public class DevelopmentEditController {

    private final ProjectStore projectStore;
    private final EditPlanner editPlanner;

    @GetMapping(ApiPaths.PROJECT_EDIT_PLAN)
    @Operation(summary = "A project's edit plan", description = "EditPlanner on the saved edits: cuts, keep segments, edited "
            + "length, length cut and timeline length. The browser's dettavoEdit.compare(id) should match it exactly.")
    public EditPlan editPlan(@PathVariable UUID projectId) {
        return editPlanner.plan(projectStore.load(projectId));
    }
}
