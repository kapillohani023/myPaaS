package io.github.kapillohani023.mypaas.jobs;

import java.time.Instant;

/**
 * Durable job intent. Payloads carry IDs and references only, never secret
 * values.
 */
public record JobSpec(
        String type,
        String payloadJson,
        String idempotencyKey,
        String dedupKey,
        Instant runAt,
        RetryPolicy retryPolicy,
        String correlationId,
        Long actorId,
        Long recurringSeconds
) {
    public static Builder of(String type, String payloadJson) {
        return new Builder(type, payloadJson);
    }

    public static final class Builder {
        private final String type;
        private final String payloadJson;
        private String idempotencyKey;
        private String dedupKey;
        private Instant runAt = Instant.now();
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private String correlationId;
        private Long actorId;
        private Long recurringSeconds;

        private Builder(String type, String payloadJson) {
            this.type = type;
            this.payloadJson = payloadJson;
        }

        public Builder idempotencyKey(String key) {
            this.idempotencyKey = key;
            return this;
        }

        public Builder dedupKey(String key) {
            this.dedupKey = key;
            return this;
        }

        public Builder runAt(Instant runAt) {
            this.runAt = runAt;
            return this;
        }

        public Builder retry(RetryPolicy policy) {
            this.retryPolicy = policy;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder actorId(Long actorId) {
            this.actorId = actorId;
            return this;
        }

        public Builder recurring(long seconds) {
            this.recurringSeconds = seconds;
            return this;
        }

        public JobSpec build() {
            return new JobSpec(type, payloadJson, idempotencyKey, dedupKey, runAt,
                    retryPolicy, correlationId, actorId, recurringSeconds);
        }
    }
}
