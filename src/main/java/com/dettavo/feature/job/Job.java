package com.dettavo.feature.job;

import com.dettavo.common.process.CancellationToken;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * One job in memory. It is changed by its own thread and by request threads, so every change and every
 * read of its state is synchronized; readers get a {@link JobResponse} snapshot.
 */
public class Job {

    /** Below this much progress the estimate is too noisy to show. */
    private static final double MINIMUM_PROGRESS_FOR_ESTIMATE = 0.02;

    private final UUID id = UUID.randomUUID();
    private final UUID projectId;
    private final String clipId;
    private final JobType type;
    private final Instant createdAt = now();
    private final CancellationToken cancellationToken = new CancellationToken();

    private JobStatus status = JobStatus.QUEUED;
    private double progress;
    private String stage = "Waiting";
    private Long etaSeconds;
    private Integer queuePosition;
    private String error;
    private String logFile;
    private Instant startedAt;
    private Instant finishedAt;
    private Future<?> future;
    /** Where the ETA is measured from; see restartEstimate. */
    private Instant estimateFrom;
    private double estimateBase;

    Job(UUID projectId, String clipId, JobType type) {
        this.projectId = projectId;
        this.clipId = clipId;
        this.type = type;
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public String clipId() {
        return clipId;
    }

    public JobType type() {
        return type;
    }

    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    public synchronized JobStatus status() {
        return status;
    }

    synchronized void attach(Future<?> future) {
        this.future = future;
    }

    synchronized void queuePosition(Integer queuePosition) {
        this.queuePosition = queuePosition;
    }

    /** QUEUED -> RUNNING. False if the job was cancelled while it waited. */
    synchronized boolean start() {
        if (status != JobStatus.QUEUED) {
            return false;
        }
        status = JobStatus.RUNNING;
        startedAt = now();
        queuePosition = null;
        stage = "Starting";
        return true;
    }

    /** QUEUED -> CANCELLED. False if the job has already started. */
    synchronized boolean cancelIfQueued() {
        if (status != JobStatus.QUEUED) {
            return false;
        }
        status = JobStatus.CANCELLED;
        stage = "Cancelled";
        queuePosition = null;
        finishedAt = now();
        if (future != null) {
            future.cancel(false);
        }
        return true;
    }

    synchronized void progress(double fraction, String stage) {
        progress = Math.clamp(fraction, 0.0, 1.0);
        if (stage != null) {
            this.stage = stage;
        }
        etaSeconds = estimateSecondsLeft();
    }

    synchronized void stage(String stage) {
        this.stage = stage;
    }

    /** Measures the ETA from now on, for a job that skipped work already done, such as finished chunks. */
    synchronized void restartEstimate() {
        estimateFrom = Instant.now();
        estimateBase = progress;
    }

    synchronized void logFile(String logFile) {
        this.logFile = logFile;
    }

    synchronized void finish(JobStatus outcome, String error) {
        status = outcome;
        this.error = error;
        finishedAt = now();
        etaSeconds = null;
        if (outcome == JobStatus.SUCCEEDED) {
            progress = 1.0;
        }
        stage = switch (outcome) {
            case SUCCEEDED -> "Done";
            case CANCELLED -> "Cancelled";
            default -> "Failed";
        };
    }

    public synchronized JobResponse snapshot() {
        return new JobResponse(id, projectId, clipId, type, status, progress, stage, etaSeconds, queuePosition,
                error, logFile, createdAt, startedAt, finishedAt);
    }

    private Long estimateSecondsLeft() {
        Instant from = estimateFrom != null ? estimateFrom : startedAt;
        double done = progress - estimateBase;
        if (from == null || done < MINIMUM_PROGRESS_FOR_ESTIMATE || progress >= 1.0) {
            return null;
        }
        double elapsedSeconds = Duration.between(from, Instant.now()).toMillis() / 1000.0;
        return Math.round(elapsedSeconds * (1 - progress) / done);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
