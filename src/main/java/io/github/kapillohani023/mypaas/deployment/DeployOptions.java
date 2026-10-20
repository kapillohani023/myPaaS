package io.github.kapillohani023.mypaas.deployment;

/** Optional ref override, force flag and client idempotency key. */
public record DeployOptions(String ref, boolean force, String idempotencyKey) {

    public static DeployOptions none() {
        return new DeployOptions(null, false, null);
    }

    public DeployOptions {
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 160)) {
            throw new IllegalArgumentException("Idempotency key must be 1-160 characters");
        }
    }
}
