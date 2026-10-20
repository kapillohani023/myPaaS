package io.github.kapillohani023.mypaas.jobs;

public record RetryPolicy(int maxAttempts, long initialBackoffMillis, long maxBackoffMillis) {

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 2_000, 60_000);
    }

    public long backoffFor(int attempt) {
        long delay = initialBackoffMillis * (1L << Math.min(Math.max(0, attempt - 1), 10));
        return Math.min(delay, maxBackoffMillis);
    }
}
