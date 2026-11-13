package com.dettavo.feature.job;

import com.dettavo.config.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Job events", description = "Server-sent events for one job or every job of a project")
public class JobEventsController {

    private final JobService jobService;
    private final JobEventBroadcaster broadcaster;

    @GetMapping(path = ApiPaths.PROJECT_EVENTS, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Every job of a project", description = "Opens with every job of the project, then sends \"job\" events "
            + "as they change. One stream feeds all the clip tiles and the export dialog.")
    public SseEmitter projectEvents(@PathVariable UUID projectId) {
        return broadcaster.subscribeToProject(projectId, jobService.projectJobs(projectId));
    }

    @GetMapping(path = ApiPaths.JOB_EVENTS, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "One job", description = "Opens with the job's state, then sends \"job\" events as it changes.")
    public SseEmitter jobEvents(@PathVariable UUID jobId) {
        return broadcaster.subscribeToJob(jobService.get(jobId));
    }
}
