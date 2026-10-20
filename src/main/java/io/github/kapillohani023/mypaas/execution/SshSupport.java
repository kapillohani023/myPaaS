package io.github.kapillohani023.mypaas.execution;

import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.credentials.CredentialService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared plumbing for the SSH adapters. Pins the managed server's host key,
 * materializes the credential-referenced private key with 0600 permissions, and
 * always cleans both up.
 */
public final class SshSupport {

    private SshSupport() {
    }

    public static final class MaterializedIdentity implements AutoCloseable {
        private final Path dir;
        private final Path privateKey;
        private final Path knownHosts;

        MaterializedIdentity(Path dir, Path privateKey, Path knownHosts) {
            this.dir = dir;
            this.privateKey = privateKey;
            this.knownHosts = knownHosts;
        }

        public Path privateKey() {
            return privateKey;
        }

        public Path knownHosts() {
            return knownHosts;
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(privateKey);
                Files.deleteIfExists(knownHosts);
                Files.deleteIfExists(dir);
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }

    public static MaterializedIdentity materialize(ExecutionTarget target, CredentialService credentials) {
        if (target.local()) {
            throw new DomainException("execution_policy", 400, "SSH adapter requires a remote server");
        }
        if (target.hostKeyPublic() == null || target.hostKeyPublic().isBlank()) {
            throw new DomainException("host_key_unpinned", 409,
                    "Server host key is not pinned; re-verify the server before deploying");
        }
        if (target.credentialId() == null) {
            throw new DomainException("credential_missing", 422, "Server has no SSH credential reference");
        }
        try {
            Path dir = Files.createTempDirectory("mypaas-ssh-");
            Path key = dir.resolve("identity");
            String secret = credentials.resolveForExecution(target.credentialId());
            Files.writeString(key, secret.endsWith("\n") ? secret : secret + "\n", StandardCharsets.UTF_8);
            if (Files.getFileStore(key).supportsFileAttributeView(
                    java.nio.file.attribute.PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
            }
            Path knownHosts = dir.resolve("known_hosts");
            String hostPattern = target.port() == 22 ? target.host() : "[" + target.host() + "]:" + target.port();
            Files.writeString(knownHosts, hostPattern + " " + target.hostKeyPublic().trim() + "\n",
                    StandardCharsets.UTF_8);
            return new MaterializedIdentity(dir, key, knownHosts);
        } catch (IOException e) {
            throw new DomainException("ssh_setup_failure", 500, "Unable to prepare SSH identity", e);
        }
    }

    public static List<String> sshBase(ExecutionTarget target, MaterializedIdentity identity) {
        List<String> argv = new ArrayList<>();
        argv.add("ssh");
        argv.add("-p");
        argv.add(Integer.toString(target.port()));
        argv.add("-i");
        argv.add(identity.privateKey().toString());
        argv.add("-o");
        argv.add("BatchMode=yes");
        argv.add("-o");
        argv.add("StrictHostKeyChecking=yes");
        argv.add("-o");
        argv.add("UserKnownHostsFile=" + identity.knownHosts());
        argv.add("-o");
        argv.add("ConnectTimeout=10");
        argv.add(target.username() + "@" + target.host());
        return argv;
    }

    public static List<String> scpBase(ExecutionTarget target, MaterializedIdentity identity) {
        List<String> argv = new ArrayList<>();
        argv.add("scp");
        argv.add("-P");
        argv.add(Integer.toString(target.port()));
        argv.add("-i");
        argv.add(identity.privateKey().toString());
        argv.add("-o");
        argv.add("BatchMode=yes");
        argv.add("-o");
        argv.add("StrictHostKeyChecking=yes");
        argv.add("-o");
        argv.add("UserKnownHostsFile=" + identity.knownHosts());
        argv.add("-o");
        argv.add("ConnectTimeout=10");
        return argv;
    }

    public record ProcessOutput(int exitCode, String output) {
    }

    public static ProcessOutput runCapture(List<String> argv, int timeoutSeconds, OutputSink sink) {
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            StringBuilder captured = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var in = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
                        captured.append(text);
                        if (sink != null) {
                            sink.line(OutputSink.Stream.SYSTEM, text.stripTrailing());
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();
            boolean exited = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!exited) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new DomainException("timeout", 504, "SSH command timed out");
            }
            reader.join(2000);
            return new ProcessOutput(process.exitValue(), captured.toString());
        } catch (IOException e) {
            throw new DomainException("ssh_failure", 502, "Unable to run SSH command", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException("execution_interrupted", 500, "SSH command interrupted", e);
        }
    }

    public static int run(List<String> argv, int timeoutSeconds, OutputSink sink) {
        return runCapture(argv, timeoutSeconds, sink).exitCode();
    }

    /** Quotes a value for a generated script. Values are never interpolated into a shell command from a request. */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
