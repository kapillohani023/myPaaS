package io.github.kapillohani023.mypaas.core;

public class RateLimitedException extends DomainException {
    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("rate_limited", 429, "Too many requests. Retry after " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
