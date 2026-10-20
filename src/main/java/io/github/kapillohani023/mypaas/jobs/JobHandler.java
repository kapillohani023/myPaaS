package io.github.kapillohani023.mypaas.jobs;

/** A handler must be idempotent: delivery is at-least-once. */
public interface JobHandler {
    void handle(Job job, JobLease lease);
}
