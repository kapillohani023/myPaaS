package io.github.kapillohani023.mypaas.jobs;

/** A handler failure that must not be retried (for example invalid deployment configuration). */
public class PermanentJobFailure extends RuntimeException {
    public PermanentJobFailure(String message) {
        super(message);
    }

    public PermanentJobFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
