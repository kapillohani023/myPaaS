package io.github.kapillohani023.mypaas.security;

/**
 * Capability catalogue. The sensitive baseline here cannot be removed by
 * configuration; configuration may only add sensitive actions.
 */
public enum Action {
    PROJECT_READ(Role.VIEWER, false),
    PROJECT_CREATE(Role.DEVELOPER, false),
    PROJECT_UPDATE(Role.DEVELOPER, false),
    PROJECT_DELETE(Role.DEVELOPER, true),
    DEPLOY(Role.DEVELOPER, false),
    DEPLOY_CANCEL(Role.DEVELOPER, false),
    DEPLOY_ROLLBACK(Role.DEVELOPER, false),
    LOG_READ(Role.VIEWER, false),
    FILE_READ(Role.VIEWER, false),
    FILE_UPLOAD(Role.DEVELOPER, false),
    FILE_DELETE(Role.DEVELOPER, true),
    ENV_READ(Role.DEVELOPER, false),
    ENV_WRITE(Role.DEVELOPER, true),
    DOMAIN_MANAGE(Role.DEVELOPER, false),
    CERTIFICATE_MANAGE(Role.ADMIN, true),
    CERTIFICATE_READ(Role.ADMIN, false),
    WEBHOOK_MANAGE(Role.DEVELOPER, false),
    SERVER_READ(Role.VIEWER, false),
    SERVER_CREATE(Role.ADMIN, false),
    SERVER_UPDATE(Role.ADMIN, true),
    SERVER_DELETE(Role.ADMIN, true),
    CREDENTIAL_MANAGE(Role.ADMIN, true),
    CREDENTIAL_READ(Role.ADMIN, false),
    USER_MANAGE(Role.ADMIN, true),
    USER_READ(Role.ADMIN, false),
    AUDIT_READ(Role.ADMIN, false),
    MONITORING_READ(Role.VIEWER, false),
    ALERT_MANAGE(Role.DEVELOPER, false),
    ANALYTICS_READ(Role.VIEWER, false),
    SETTINGS_UPDATE(Role.VIEWER, true),
    AUTHENTICATE(Role.VIEWER, false);

    private final Role minimumRole;
    private final boolean sensitive;

    Action(Role minimumRole, boolean sensitive) {
        this.minimumRole = minimumRole;
        this.sensitive = sensitive;
    }

    public Role minimumRole() {
        return minimumRole;
    }

    public boolean sensitive() {
        return sensitive;
    }
}
