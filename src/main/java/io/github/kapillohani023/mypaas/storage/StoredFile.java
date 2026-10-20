package io.github.kapillohani023.mypaas.storage;

/** Metadata for a stored file. The storage key is internal and never exposed by read APIs. */
public record StoredFile(
        long id,
        String storageKey,
        String originalName,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        boolean sensitive,
        Long projectId,
        Long certificateId,
        long createdAt,
        Long createdBy
) {
}
