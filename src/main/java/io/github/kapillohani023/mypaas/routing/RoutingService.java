package io.github.kapillohani023.mypaas.routing;

public interface RoutingService {

    HostnameAssignment claim(long projectId, String hostname);

    void unclaim(String hostname);

    long registerCertificate(CertificateUpload upload);

    void selectCertificate(long projectId, String hostname, long certificateId);

    /** Idempotent: applies desired routing from the latest revision. */
    void converge(long projectId);

    /** Applies routing from the pinned configuration revision used by a deployment. */
    void converge(long projectId, long revisionId);

    CertificateStatus certificateStatus(long certificateId);
}
