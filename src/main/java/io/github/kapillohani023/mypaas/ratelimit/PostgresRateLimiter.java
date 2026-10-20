package io.github.kapillohani023.mypaas.ratelimit;

import io.github.kapillohani023.mypaas.core.RateLimitedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/** Persistent, keyed, time-bounded limiter. State survives restart. */
@Component
public class PostgresRateLimiter implements RateLimiter {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PostgresRateLimiter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void acquire(RateLimitKey key, RateLimitPolicy policy) {
        long now = clock.millis();
        long windowMillis = Math.max(1, policy.windowSeconds()) * 1000L;
        long windowStart = (now / windowMillis) * windowMillis;
        String bucketKey = key.bucket() + ":" + key.identity() + ":" + windowStart;
        for (int attempt = 0; attempt < 3; attempt++) {
            List<Integer> counts = jdbc.queryForList(
                    "select count from rate_limits where bucket_key = ?", Integer.class, bucketKey);
            if (counts.isEmpty()) {
                try {
                    jdbc.update("insert into rate_limits (bucket_key, window_start, count) values (?, ?, 1)",
                            bucketKey, windowStart);
                    return;
                } catch (org.springframework.dao.DuplicateKeyException race) {
                    continue;
                }
            }
            if (counts.getFirst() >= policy.limit()) {
                long retryAfter = Math.max(1, (windowStart + windowMillis - now) / 1000);
                throw new RateLimitedException(retryAfter);
            }
            jdbc.update("update rate_limits set count = count + 1 where bucket_key = ?", bucketKey);
            return;
        }
        throw new RateLimitedException(Math.max(1, policy.windowSeconds()));
    }

    public void purgeExpired(long olderThanMillis) {
        jdbc.update("delete from rate_limits where window_start < ?", olderThanMillis);
    }
}
