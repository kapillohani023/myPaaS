package io.github.kapillohani023.mypaas.notification;

/** Closed, versioned platform-event catalogue. Events carry references, never secrets. */
public enum PlatformEventType {
    DeploymentSucceeded,
    DeploymentFailed,
    RollbackSucceeded,
    RollbackFailed,
    AlertFired,
    AlertRecovered,
    CertificateExpiring,
    PasswordResetRequested,
    EmailVerificationRequested,
    OtpRequested,
    AccountSecurityChanged
}
