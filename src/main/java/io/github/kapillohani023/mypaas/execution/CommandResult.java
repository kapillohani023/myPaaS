package io.github.kapillohani023.mypaas.execution;

import java.time.Duration;

public record CommandResult(
        int exitCode,
        boolean truncated,
        boolean timedOut,
        boolean cancelled,
        Duration duration
) {
    public boolean success() {
        return exitCode == 0 && !timedOut && !cancelled;
    }
}
