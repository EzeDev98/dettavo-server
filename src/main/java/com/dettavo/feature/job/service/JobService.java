package com.dettavo.feature.job.service;

import com.dettavo.common.exception.HeavyJobAlreadyQueuedException;
import com.dettavo.common.exception.JobNotFoundException;
import com.dettavo.config.ApplicationProperties;
import com.dettavo.feature.project.service.ProjectStore;
import com.dettavo.feature.project.dto.JobSummary;
import com.dettavo.feature.project.dto.Project;
import com.dettavo.feature.job.dto.request.Job;
import com.dettavo.feature.job.dto.request.JobContext;
import com.dettavo.feature.job.event.JobEventBroadcaster;
import com.dettavo.feature.job.dto.response.JobResponse;
import com.dettavo.feature.job.enums.JobStatus;
import com.dettavo.feature.job.enums.JobType;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class JobService {

    static final int SUMMARIES_KEPT = 20;
    static final String INTERRUPTED_BY_RESTART = "Interrupted by restart";
    private static final int FINISHED_JOBS_KEPT_IN_MEMORY = 200;

    private final ProjectStore projectStore;
    private final JobEventBroadcaster broadcaster;
    private final ExecutorService heavyExecutor;
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    /** Jobs waiting to run, in queue order. Guarded by this. */
    private final List<Job> waiting = new ArrayList<>();
    private volatile boolean shuttingDown;

    public JobService(ProjectStore projectStore, JobEventBroadcaster broadcaster, ApplicationProperties properties) {
        this.projectStore = projectStore;
        this.broadcaster = broadcaster;
        this.heavyExecutor = Executors.newFixedThreadPool(properties.jobs().maxConcurrentHeavy(),
                Thread.ofPlatform().name("heavy-job-", 1).factory());
    }

    /** Queues a job. 409 if the clip already has a queued or running job, or the project an export. */
    public Job submit(JobType type, UUID projectId, String clipId, JobWork work) {
        return submit(type, projectId, clipId, work, null);
    }

    /** Queues a clip's next job from inside its running job, such as TRANSCRIBE at the end of PREPARE_MEDIA. */
    public Job submitFollowUp(JobContext running, JobType type, JobWork work) {
        return submit(type, running.projectId(), running.clipId(), work, running.jobId());
    }

    private Job submit(JobType type, UUID projectId, String clipId, JobWork work, UUID runningJobId) {
        Job job;
        synchronized (this) {
            requireNoActiveJob(type, projectId, clipId, runningJobId);
            job = new Job(projectId, clipId, type);
            jobs.put(job.id(), job);
            waiting.add(job);
            renumberQueue();
            job.attach(heavyExecutor.submit(() -> run(job, work)));
        }
        log.info("Queued {} job {} for project {} clip {}", type, job.id(), projectId, clipId);
        broadcaster.changedNow(job);
        forgetOldJobs();
        return job;
    }

    public JobResponse get(UUID jobId) {
        return find(jobId).snapshot();
    }

    /** A queued job is cancelled at once; a running one is asked to stop and ends CANCELLED shortly after. */
    public JobResponse cancel(UUID jobId) {
        Job job = find(jobId);
        boolean wasQueued;
        synchronized (this) {
            wasQueued = job.cancelIfQueued();
            if (wasQueued) {
                waiting.remove(job);
                renumberQueue();
            }
        }
        if (wasQueued) {
            log.info("Cancelled queued job {}", jobId);
            broadcaster.changedNow(job);
            record(job);
        } else if (job.status() == JobStatus.RUNNING) {
            log.info("Cancelling running job {}", jobId);
            job.cancellationToken().cancel();
            job.stage("Cancelling");
            broadcaster.changedNow(job);
        }
        return job.snapshot();
    }

    public void cancelClipJobs(UUID projectId, String clipId) {
        jobs.values().stream()
                .filter(job -> job.projectId().equals(projectId) && clipId.equals(job.clipId()))
                .filter(job -> job.status() == JobStatus.QUEUED || job.status() == JobStatus.RUNNING)
                .map(Job::id)
                .toList()
                .forEach(this::cancel);
    }

    /** Every job of a project: the summaries in project.json, overlaid with the jobs in memory. */
    public List<JobResponse> projectJobs(UUID projectId) {
        Project project = projectStore.load(projectId);
        Map<UUID, JobResponse> byId = new LinkedHashMap<>();
        project.jobs().forEach(summary -> byId.put(summary.id(), JobResponse.fromSummary(projectId, summary)));
        jobs.values().stream()
                .filter(job -> job.projectId().equals(projectId))
                .map(Job::snapshot)
                .sorted(Comparator.comparing(JobResponse::createdAt))
                .forEach(job -> byId.put(job.id(), job));
        return List.copyOf(byId.values());
    }

    @EventListener(ApplicationStartedEvent.class)
    public void recoverInterruptedJobs() {
        for (Project project : projectStore.list()) {
            if (project.jobs().stream().noneMatch(summary -> summary.status().isActive())) {
                continue;
            }
            projectStore.update(project.id(), current -> current.toBuilder()
                    .jobs(current.jobs().stream()
                            .map(summary -> summary.status().isActive()
                                    ? summary.interrupted(INTERRUPTED_BY_RESTART, ProjectStore.now())
                                    : summary)
                            .toList())
                    .build());
            log.warn("Project {}: marked jobs interrupted by the restart as FAILED", project.id());
        }
    }

    /** Stopping the server leaves a running job RUNNING in project.json, so the next start marks it interrupted. */
    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        heavyExecutor.shutdownNow();
    }

    private void run(Job job, JobWork work) {
        synchronized (this) {
            if (!job.start()) {
                return;
            }
            waiting.remove(job);
            renumberQueue();
        }
        log.info("Started {} job {}", job.type(), job.id());
        broadcaster.changedNow(job);
        record(job);

        JobStatus outcome;
        String error = null;
        try {
            work.run(new JobContext(job, broadcaster::changed));
            outcome = job.cancellationToken().isCancelled() ? JobStatus.CANCELLED : JobStatus.SUCCEEDED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (shuttingDown) {
                return;
            }
            outcome = JobStatus.CANCELLED;
        } catch (Exception e) {
            if (job.cancellationToken().isCancelled()) {
                outcome = JobStatus.CANCELLED;
            } else {
                outcome = JobStatus.FAILED;
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("{} job {} failed: {}", job.type(), job.id(), error);
            }
        }
        if (shuttingDown) {
            return;
        }
        job.finish(outcome, error);
        log.info("{} job {} ended {}", job.type(), job.id(), outcome);
        broadcaster.changedNow(job);
        record(job);
    }

    private void requireNoActiveJob(JobType type, UUID projectId, String clipId, UUID runningJobId) {
        for (Job job : jobs.values()) {
            if (!job.projectId().equals(projectId) || !job.status().isActive() || job.id().equals(runningJobId)) {
                continue;
            }
            if (type.isPerClip() && job.type().isPerClip() && Objects.equals(job.clipId(), clipId)) {
                throw new HeavyJobAlreadyQueuedException("Clip " + clipId + " already has a queued or running job");
            }
            if (type == JobType.EXPORT && job.type() == JobType.EXPORT) {
                throw new HeavyJobAlreadyQueuedException("This project already has an export queued or running");
            }
        }
    }

    /** Waiting jobs are numbered 1 upward; with one job running, a job's number is how many jobs are ahead of it. */
    private void renumberQueue() {
        for (int index = 0; index < waiting.size(); index++) {
            Job job = waiting.get(index);
            job.queuePosition(index + 1);
            broadcaster.changed(job);
        }
    }

    private void record(Job job) {
        JobSummary summary = job.snapshot().toSummary();
        try {
            projectStore.update(job.projectId(), project -> project.toBuilder()
                    .jobs(withSummary(project.jobs(), summary))
                    .build());
        } catch (RuntimeException e) {
            log.warn("Could not record job {} in project {}: {}", job.id(), job.projectId(), e.getMessage());
        }
    }

    static List<JobSummary> withSummary(List<JobSummary> summaries, JobSummary summary) {
        List<JobSummary> next = new ArrayList<>(summaries.stream().filter(each -> !each.id().equals(summary.id())).toList());
        next.add(summary);
        return next.size() > SUMMARIES_KEPT ? List.copyOf(next.subList(next.size() - SUMMARIES_KEPT, next.size())) : next;
    }

    private Job find(UUID jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            throw new JobNotFoundException(jobId);
        }
        return job;
    }

    private void forgetOldJobs() {
        if (jobs.size() <= FINISHED_JOBS_KEPT_IN_MEMORY) {
            return;
        }
        jobs.values().stream()
                .filter(job -> !job.status().isActive())
                .sorted(Comparator.comparing(job -> job.snapshot().createdAt()))
                .limit(jobs.size() - FINISHED_JOBS_KEPT_IN_MEMORY)
                .toList()
                .forEach(job -> jobs.remove(job.id()));
    }
}
