package io.github.kapillohani023.mypaas.ratelimit;

import io.github.kapillohani023.mypaas.core.RateLimitedException;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test adapter and fallback. */
public class InMemoryRateLimiter implements RateLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void acquire(RateLimitKey key, RateLimitPolicy policy) {
        long now = clock.millis();
        long windowMillis = Math.max(1, policy.windowSeconds()) * 1000L;
        long windowStart = (now / windowMillis) * windowMillis;
        String bucketKey = key.bucket() + ":" + key.identity() + ":" + windowStart;
        Window window = windows.computeIfAbsent(bucketKey, k -> new Window(windowStart));
        synchronized (window) {
            if (window.count >= policy.limit()) {
                throw new RateLimitedException(Math.max(1, (windowStart + windowMillis - now) / 1000));
            }
            window.count++;
        }
    }

    public void purgeExpired(long before) {
        windows.entrySet().removeIf(entry -> entry.getValue().windowStart < before);
    }

    private static final class Window {
        private final long windowStart;
        private int count;

        private Window(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
