package io.github.kapillohani023.mypaas.routing;

import java.util.List;

public record CertificateMetadata(
        long id,
        List<String> hostnames,
        long chainFileId,
        long keyFileId,
        long notBefore,
        long notAfter,
        long uploadedAt
) {
}
