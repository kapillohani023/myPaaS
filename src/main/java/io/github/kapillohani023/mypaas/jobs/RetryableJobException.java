package io.github.kapillohani023.mypaas.jobs;

/** Retryable failure, optionally after a specific delay. */
public class RetryableJobException extends RuntimeException {
    private final long delayMillis;

    public RetryableJobException(String message, long delayMillis) {
        super(message);
        this.delayMillis = delayMillis;
    }

    public long delayMillis() {
        return delayMillis;
    }
}
