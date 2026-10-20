package io.github.kapillohani023.mypaas.project;

import java.util.List;

/**
 * Immutable, deployment-relevant project configuration. Secrets are represented
 * only by credential references. Every save creates a new revision; deployments
 * pin the revision they used.
 */
public record ProjectConfiguration(
        String gitUrl,
        String gitRef,
        String subdomain,
        String runtimeUser,
        String workingDirectory,
        int internalPort,
        String buildScript,
        String prepareScript,
        String startCommand,
        List<FileReference> fileReferences,
        List<EnvironmentEntry> environment,
        HealthCheck healthCheck,
        List<String> hostnames,
        Toggles toggles,
        int logRetentionDays,
        int analyticsRetentionDays
) {
    public ProjectConfiguration {
        fileReferences = fileReferences == null ? List.of() : List.copyOf(fileReferences);
        environment = environment == null ? List.of() : List.copyOf(environment);
        hostnames = hostnames == null ? List.of() : List.copyOf(hostnames);
    }

    public record Toggles(
            boolean enableApplication,
            boolean enableHttps,
            boolean autoDeploy,
            boolean accessLogs,
            boolean analytics
    ) {
    }

    public record FileReference(long fileId, String destination) {
    }

    public record EnvironmentEntry(String name, String literalValue, Long credentialId) {
        public boolean secret() {
            return credentialId != null;
        }

        public static EnvironmentEntry literal(String name, String value) {
            return new EnvironmentEntry(name, value, null);
        }

        public static EnvironmentEntry secret(String name, long credentialId) {
            return new EnvironmentEntry(name, null, credentialId);
        }
    }

    public record HealthCheck(String path, int expectedStatus, int timeoutSeconds,
                              int retries, int intervalSeconds) {
        public static HealthCheck defaults() {
            return new HealthCheck("/", 200, 30, 3, 5);
        }
    }

    public ProjectConfiguration withToggles(Toggles newToggles) {
        return new ProjectConfiguration(gitUrl, gitRef, subdomain, runtimeUser, workingDirectory,
                internalPort, buildScript, prepareScript, startCommand, fileReferences, environment,
                healthCheck, hostnames, newToggles, logRetentionDays, analyticsRetentionDays);
    }

    public String appHost() {
        return subdomain;
    }
}
