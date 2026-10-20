package io.github.kapillohani023.mypaas.execution;

import java.util.Map;

/**
 * Everything a caller must know to run a command safely.
 *
 * @param target          managed server connection (resolved by the adapter)
 * @param argv            token list; never a shell-concatenated string
 * @param workingDirectory absolute working directory
 * @param environment     literal values or credential references
 * @param timeoutSeconds  hard timeout; the adapter kills the process group
 * @param maxOutputBytes  output cap; exceeding it truncates, never buffers unbounded
 * @param correlationId   trace identifier
 * @param script          optional script body; executed from a generated file, never interpolated
 */
public record ExecutionSpec(
        ExecutionTarget target,
        java.util.List<String> argv,
        String workingDirectory,
        Map<String, EnvBinding> environment,
        int timeoutSeconds,
        long maxOutputBytes,
        String correlationId,
        String script
) {
    public static Builder command(ExecutionTarget target, String... argv) {
        return new Builder(target, java.util.List.of(argv));
    }

    public static Builder script(ExecutionTarget target, String script) {
        return new Builder(target, java.util.List.of("/bin/bash", "-e"))
                .withScript(script);
    }

    public static final class Builder {
        private final ExecutionTarget target;
        private final java.util.List<String> argv;
        private String workingDirectory;
        private final Map<String, EnvBinding> environment = new java.util.LinkedHashMap<>();
        private int timeoutSeconds = 120;
        private long maxOutputBytes = 2L * 1024 * 1024;
        private String correlationId = "exec";
        private String script;

        private Builder(ExecutionTarget target, java.util.List<String> argv) {
            this.target = target;
            this.argv = argv;
        }

        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder env(String name, String literalValue) {
            environment.put(name, EnvBinding.literal(literalValue));
            return this;
        }

        public Builder credentialEnv(String name, long credentialId) {
            environment.put(name, EnvBinding.credential(credentialId));
            return this;
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder maxOutputBytes(long maxOutputBytes) {
            this.maxOutputBytes = maxOutputBytes;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder withScript(String script) {
            this.script = script;
            return this;
        }

        public ExecutionSpec build() {
            return new ExecutionSpec(target, argv, workingDirectory, Map.copyOf(environment),
                    timeoutSeconds, maxOutputBytes, correlationId, script);
        }
    }
}
