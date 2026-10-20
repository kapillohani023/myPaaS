package io.github.kapillohani023.mypaas.execution;

import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.credentials.CredentialService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SSH adapter for the execution seam. Commands are never interpolated into a
 * remote shell string: a local generated script and environment file are
 * transferred, then executed by a fixed shell. Secret values travel only in the
 * 0600 environment file, never argv.
 */
@Component
public class SshCommandExecutor implements CommandExecutor {

    private final CredentialService credentials;

    public SshCommandExecutor(CredentialService credentials) {
        this.credentials = credentials;
    }

    @Override
    public CommandResult run(ExecutionSpec spec, OutputSink output, CancellationToken cancellation) {
        if (!spec.target().remote()) {
            throw new DomainException("execution_policy", 400, "SSH executor requires a remote server");
        }
        long started = System.nanoTime();
        Path localDir = null;
        try (SshSupport.MaterializedIdentity identity = SshSupport.materialize(spec.target(), credentials)) {
            localDir = Files.createTempDirectory("mypaas-remote-");
            Path dir = localDir;
            String token = UUID.randomUUID().toString();
            Path envFile = dir.resolve("env-" + token);
            Path payload = dir.resolve("payload-" + token + ".sh");
            Path wrapper = dir.resolve("wrapper-" + token + ".sh");
            List<String> exports = new ArrayList<>();
            for (Map.Entry<String, EnvBinding> entry : spec.environment().entrySet()) {
                String value = entry.getValue().type() == EnvBinding.Type.CREDENTIAL
                        ? credentials.resolveForExecution(entry.getValue().credentialId())
                        : entry.getValue().literalValue();
                exports.add("export " + entry.getKey() + "=" + SshSupport.quote(value));
            }
            Files.writeString(envFile, String.join("\n", exports) + "\n", StandardCharsets.UTF_8);

            String remoteEnv = "/tmp/mypaas-env-" + token;
            String remoteWrapper = "/tmp/mypaas-run-" + token + ".sh";
            String remotePayload = "/tmp/mypaas-payload-" + token + ".sh";
            StringBuilder wrapperText = new StringBuilder("#!/bin/bash\nset -e\n");
            wrapperText.append("trap 'rm -f ").append(remoteEnv).append(" ").append(remoteWrapper)
                    .append(" ").append(remotePayload).append("' EXIT\n");
            wrapperText.append(". ").append(remoteEnv).append("\n");
            if (spec.workingDirectory() != null && !spec.workingDirectory().isBlank()) {
                wrapperText.append("cd -- ").append(SshSupport.quote(spec.workingDirectory())).append("\n");
            }
            if (spec.script() != null) {
                Files.writeString(payload, spec.script(), StandardCharsets.UTF_8);
                wrapperText.append("exec /bin/bash ").append(SshSupport.quote(remotePayload)).append("\n");
            } else {
                wrapperText.append("exec");
                for (String arg : spec.argv()) {
                    wrapperText.append(' ').append(SshSupport.quote(arg));
                }
                wrapperText.append("\n");
            }
            Files.writeString(wrapper, wrapperText.toString(), StandardCharsets.UTF_8);

            List<String> scp = SshSupport.scpBase(spec.target(), identity);
            scp.add(envFile.toString());
            scp.add(wrapper.toString());
            if (spec.script() != null) {
                scp.add(payload.toString());
            }
            scp.add(spec.target().username() + "@" + spec.target().host() + ":/tmp/");
            int scpExit = SshSupport.run(scp, Math.max(30, spec.timeoutSeconds()), output);
            if (scpExit != 0) {
                throw new DomainException("transfer_failure", 502, "Unable to transfer execution files");
            }

            // scp strips the local file names; rename to the tokenized remote paths.
            List<String> ssh = SshSupport.sshBase(spec.target(), identity);
            String rename = "/bin/bash -c " + SshSupport.quote(
                    "mv /tmp/" + envFile.getFileName() + " " + remoteEnv + " && " +
                            "mv /tmp/" + wrapper.getFileName() + " " + remoteWrapper +
                            (spec.script() != null ? " && mv /tmp/" + payload.getFileName() + " " + remotePayload : ""));
            ssh.add(rename);
            int renameExit = SshSupport.run(ssh, 30, output);
            if (renameExit != 0) {
                throw new DomainException("transfer_failure", 502, "Unable to stage execution files");
            }

            List<String> launch = SshSupport.sshBase(spec.target(), identity);
            launch.add("/bin/bash " + remoteWrapper);
            int exit = SshSupport.run(launch, spec.timeoutSeconds(), output);
            boolean cancelled = cancellation.isCancelled();
            return new CommandResult(cancelled ? 130 : exit, false, false, cancelled,
                    Duration.ofNanos(System.nanoTime() - started));
        } catch (IOException e) {
            throw new DomainException("execution_failure", 500, "Unable to prepare remote execution", e);
        } finally {
            deleteTree(localDir);
        }
    }

    private static void deleteTree(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
