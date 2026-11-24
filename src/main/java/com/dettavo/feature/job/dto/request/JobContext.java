package com.dettavo.feature.job.dto.request;

import com.dettavo.common.process.CancellationToken;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

/** What a job's work sees: which clip it is for, its cancellation token, and where to report progress. */
public final class JobContext {

    private final Job job;
    private final Consumer<Job> onChange;

    public JobContext(Job job, Consumer<Job> onChange) {
        this.job = job;
        this.onChange = onChange;
    }

    public UUID jobId() {
        return job.id();
    }

    public UUID projectId() {
        return job.projectId();
    }

    public String clipId() {
        return job.clipId();
    }

    public CancellationToken cancellationToken() {
        return job.cancellationToken();
    }

    public boolean isCancelled() {
        return job.cancellationToken().isCancelled();
    }

    /** fraction runs from 0 to 1; stage is a short label such as "Extracting audio" (null keeps the last one). */
    public void progress(double fraction, String stage) {
        job.progress(fraction, stage);
        onChange.accept(job);
    }

    /** The ETA is measured from here on: work done before this point, such as reused chunks, took no time. */
    public void restartEstimate() {
        job.restartEstimate();
    }

    public void logFile(Path logFile) {
        job.logFile(logFile.toString());
        onChange.accept(job);
    }
}
