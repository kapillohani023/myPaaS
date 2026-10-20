package io.github.kapillohani023.mypaas.routing;

import java.util.List;

/** Certificate upload: chain and key are stored as sensitive files; only metadata is returned. */
public record CertificateUpload(String chainPem, String privateKeyPem, List<String> hostnames) {
}
