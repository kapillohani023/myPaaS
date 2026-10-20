package io.github.kapillohani023.mypaas.storage;

public record TransferSpec(
        TransferTarget target,
        FileSource source,
        String destination,
        String owner,
        String mode,
        String checksumSha256,
        long sizeBytes,
        boolean overwrite,
        String correlationId
) {
}
