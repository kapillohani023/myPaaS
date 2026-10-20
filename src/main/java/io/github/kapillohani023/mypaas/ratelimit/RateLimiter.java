package io.github.kapillohani023.mypaas.ratelimit;

import io.github.kapillohani023.mypaas.core.RateLimitedException;

public interface RateLimiter {

    void acquire(RateLimitKey key, RateLimitPolicy policy);

    record RateLimitKey(String bucket, String identity) {
        public static RateLimitKey of(String bucket, String identity) {
            return new RateLimitKey(bucket, identity);
        }
    }

    record RateLimitPolicy(int limit, long windowSeconds) {
        public static RateLimitPolicy of(int limit, long windowSeconds) {
            return new RateLimitPolicy(limit, windowSeconds);
        }
    }
}
