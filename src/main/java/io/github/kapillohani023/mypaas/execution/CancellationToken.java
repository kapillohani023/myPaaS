package io.github.kapillohani023.mypaas.execution;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation shared by executors and transfers. */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancelledException();
        }
    }

    public static CancellationToken none() {
        return new CancellationToken();
    }

    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Operation cancelled");
        }
    }
}
