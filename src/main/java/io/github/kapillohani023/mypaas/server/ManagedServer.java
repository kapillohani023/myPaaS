package io.github.kapillohani023.mypaas.server;

public record ManagedServer(
        long id,
        String name,
        String host,
        int sshPort,
        String username,
        Long credentialId,
        boolean local,
        String os,
        ServerCapabilities capabilities,
        String hostKeyFingerprint,
        String hostKeyPublic,
        Long verifiedAt,
        long createdAt
) {
}
