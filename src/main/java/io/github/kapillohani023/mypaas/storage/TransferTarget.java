package io.github.kapillohani023.mypaas.storage;

/** A managed-server destination resolved from the server record by the caller. */
public record TransferTarget(
        long serverId,
        String host,
        int port,
        String username,
        boolean local,
        Long credentialId,
        String hostKeyPublic,
        String allowedRoot
) {
}
