package io.github.kapillohani023.mypaas.execution;

/** A managed server connection resolved by the execution adapter. */
public record ExecutionTarget(
        long serverId,
        String host,
        int port,
        String username,
        boolean local,
        Long credentialId,
        String hostKeyPublic,
        String allowedRoot
) {
    public boolean remote() {
        return !local;
    }
}
