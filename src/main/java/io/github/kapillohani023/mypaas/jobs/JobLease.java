package io.github.kapillohani023.mypaas.jobs;

/** Lease passed to handlers so long-running work can extend its claim. */
public interface JobLease {
    void heartbeat();

    int attempts();

    boolean cancelRequested();

    io.github.kapillohani023.mypaas.execution.CancellationToken cancellation();
}
