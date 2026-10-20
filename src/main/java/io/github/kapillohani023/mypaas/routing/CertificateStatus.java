package io.github.kapillohani023.mypaas.routing;

public record CertificateStatus(
        long id,
        java.util.List<String> hostnames,
        long notBefore,
        long notAfter,
        boolean expired,
        int daysRemaining
) {
}
