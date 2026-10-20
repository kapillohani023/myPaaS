package io.github.kapillohani023.mypaas.jobs;

/** The only asynchronous execution mechanism. */
public interface JobScheduler {

    long schedule(JobSpec spec);

    void cancel(long jobId);

    JobStatus status(long jobId);
}
