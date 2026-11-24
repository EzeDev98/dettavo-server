package com.dettavo.feature.job.web;

import com.dettavo.common.exception.ClipNotFoundException;
import com.dettavo.config.ApiPaths;
import com.dettavo.feature.project.service.ProjectStore;
import com.dettavo.feature.job.dto.request.Job;
import com.dettavo.feature.job.dto.response.JobResponse;
import com.dettavo.feature.job.service.JobService;
import com.dettavo.feature.job.enums.JobType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Development-only sleep jobs, to check the queue, cancelling and events before there is any media. */
@Profile("dev")
@RestController
@RequiredArgsConstructor
@Tag(name = "Development", description = "Dev profile only")
public class DevelopmentJobController {

    private static final int MAXIMUM_SECONDS = 600;

    private final JobService jobService;
    private final ProjectStore projectStore;

    @PostMapping(ApiPaths.BASE + "/dev/projects/{projectId}/clips/{clipId}/sleep")
    @Operation(summary = "Queue a sleep job on a clip", description = "Waits the given seconds (1 to 600), reporting progress every 100 ms.")
    public ResponseEntity<JobResponse> sleep(@PathVariable UUID projectId,
                                             @PathVariable String clipId,
                                             @RequestParam(defaultValue = "30") int seconds) {
        projectStore.load(projectId).findClip(clipId).orElseThrow(() -> new ClipNotFoundException(projectId, clipId));
        int steps = Math.clamp(seconds, 1, MAXIMUM_SECONDS) * 10;
        Job job = jobService.submit(JobType.DEVELOPMENT_SLEEP, projectId, clipId, context -> {
            for (int step = 1; step <= steps && !context.isCancelled(); step++) {
                Thread.sleep(100);
                context.progress((double) step / steps, "Sleeping");
            }
        });
        return ResponseEntity.accepted().body(job.snapshot());
    }
}
