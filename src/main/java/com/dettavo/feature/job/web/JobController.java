package com.dettavo.feature.job.web;

import com.dettavo.config.ApiPaths;
import com.dettavo.feature.job.dto.response.JobResponse;
import com.dettavo.feature.job.service.JobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Jobs", description = "Job state and cancelling")
public class JobController {

    private final JobService jobService;

    @GetMapping(ApiPaths.JOB)
    @Operation(summary = "Job state")
    public JobResponse get(@PathVariable UUID jobId) {
        return jobService.get(jobId);
    }

    @PostMapping(ApiPaths.JOB_CANCEL)
    @Operation(summary = "Cancel a job", description = "A queued job ends CANCELLED at once; a running one is asked to stop and "
            + "ends CANCELLED shortly after. A finished job is returned unchanged.")
    public JobResponse cancel(@PathVariable UUID jobId) {
        return jobService.cancel(jobId);
    }
}
