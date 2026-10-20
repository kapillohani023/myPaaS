package io.github.kapillohani023.mypaas.jobs;

/** A job handler bound to one job type. */
public interface TypedJobHandler extends JobHandler {
    String type();
}
