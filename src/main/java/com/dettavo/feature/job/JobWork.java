package com.dettavo.feature.job;

/** What a job does. It reports progress through the context and stops soon after the context is cancelled. */
@FunctionalInterface
public interface JobWork {

    void run(JobContext context) throws Exception;
}
