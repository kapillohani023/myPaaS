package io.github.kapillohani023.mypaas.project;

import io.github.kapillohani023.mypaas.core.ValidationException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Static, pre-deployment validation owned by the project module. */
public final class ProjectValidator {

    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{1,39}");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern USER = Pattern.compile("[a-z_][a-z0-9_-]{0,31}");
    private static final Pattern ENV = Pattern.compile("[A-Z_][A-Z0-9_]{0,127}");

    private ProjectValidator() {
    }

    public static List<String> validate(String slug, ProjectConfiguration config, String managedNamespace) {
        List<String> errors = new ArrayList<>();
        if (slug == null || !SLUG.matcher(slug).matches()) {
            errors.add("Project slug must be 2-40 lowercase letters, digits or hyphens");
        }
        if (config == null) {
            errors.add("Project configuration is required");
            return errors;
        }
        if (config.gitUrl() == null || config.gitUrl().isBlank()) {
            errors.add("Git URL is required");
        } else if (!(config.gitUrl().startsWith("https://") || config.gitUrl().startsWith("ssh://")
                || config.gitUrl().startsWith("git://") || config.gitUrl().matches("^[\\w.-]+@[\\w.-]+:.+$"))) {
            errors.add("Git URL must use https, ssh, git, or scp-like syntax");
        }
        if (config.gitRef() == null || config.gitRef().isBlank()) {
            errors.add("Git ref is required");
        }
        if (config.subdomain() == null || !LABEL.matcher(config.subdomain()).matches()) {
            errors.add("Subdomain must be a valid DNS label");
        }
        if (config.runtimeUser() == null || !USER.matcher(config.runtimeUser()).matches()
                || config.runtimeUser().equals("root")) {
            errors.add("Runtime user must be a non-root OS user");
        }
        if (config.workingDirectory() == null || !config.workingDirectory().startsWith("/")
                || config.workingDirectory().contains("..")) {
            errors.add("Working directory must be an absolute path without '..'");
        }
        if (config.internalPort() < 1 || config.internalPort() > 65535) {
            errors.add("Internal port must be between 1 and 65535");
        }
        if (config.buildScript() != null && config.buildScript().length() > 20_000) {
            errors.add("Build script is too large");
        }
        if (config.prepareScript() != null && config.prepareScript().length() > 20_000) {
            errors.add("Prepare script is too large");
        }
        if (config.startCommand() == null || config.startCommand().isBlank()) {
            errors.add("Start command is required");
        } else if (config.startCommand().length() > 2_000) {
            errors.add("Start command is too large");
        }

        Set<String> destinations = new HashSet<>();
        for (ProjectConfiguration.FileReference reference : config.fileReferences()) {
            if (reference.fileId() <= 0) {
                errors.add("File references must name a stored file");
            }
            if (reference.destination() == null || !reference.destination().startsWith("/")
                    || reference.destination().contains("..")) {
                errors.add("File destinations must be absolute paths without '..'");
            } else if (!destinations.add(reference.destination())) {
                errors.add("Duplicate file destination: " + reference.destination());
            }
            String directory = config.workingDirectory();
            if (directory != null && reference.destination() != null
                    && !reference.destination().startsWith(directory + "/")
                    && !reference.destination().startsWith("/etc/mypaas/")) {
                errors.add("File destination must be inside the working directory: " + reference.destination());
            }
        }

        Set<String> environmentNames = new HashSet<>();
        for (ProjectConfiguration.EnvironmentEntry entry : config.environment()) {
            if (entry.name() == null || !ENV.matcher(entry.name()).matches()) {
                errors.add("Environment names must match [A-Z_][A-Z0-9_]*");
            } else if (!environmentNames.add(entry.name())) {
                errors.add("Duplicate environment entry: " + entry.name());
            }
            if (entry.credentialId() == null && (entry.literalValue() == null || entry.literalValue().length() > 10_000)) {
                errors.add("Environment entry " + entry.name() + " needs a literal value or credential reference");
            }
        }

        ProjectConfiguration.HealthCheck health = config.healthCheck();
        if (health != null) {
            if (health.path() == null || !health.path().startsWith("/")) {
                errors.add("Health-check path must start with '/'");
            }
            if (health.expectedStatus() < 200 || health.expectedStatus() > 599) {
                errors.add("Health-check expected status must be a valid HTTP status");
            }
            if (health.timeoutSeconds() < 1 || health.timeoutSeconds() > 600) {
                errors.add("Health-check timeout must be 1-600 seconds");
            }
            if (health.retries() < 1 || health.retries() > 20) {
                errors.add("Health-check retries must be 1-20");
            }
        } else {
            errors.add("Health check configuration is required");
        }

        for (String hostname : config.hostnames()) {
            if (!hostname.equals(managedNamespace) && !hostname.endsWith("." + managedNamespace)) {
                errors.add("Hostname " + hostname + " is outside the managed namespace " + managedNamespace);
            }
        }
        if (config.toggles() == null) {
            errors.add("Feature toggles are required");
        }
        if (config.logRetentionDays() < 1 || config.logRetentionDays() > 365) {
            errors.add("Log retention must be 1-365 days");
        }
        if (config.analyticsRetentionDays() < 1 || config.analyticsRetentionDays() > 365) {
            errors.add("Analytics retention must be 1-365 days");
        }
        return errors;
    }

    public static void requireValid(String slug, ProjectConfiguration config, String managedNamespace) {
        List<String> errors = validate(slug, config, managedNamespace);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }
}
