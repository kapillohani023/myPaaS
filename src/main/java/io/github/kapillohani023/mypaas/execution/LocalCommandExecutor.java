package io.github.kapillohani023.mypaas.execution;

import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.credentials.CredentialService;
import io.github.kapillohani023.mypaas.credentials.SecretRegistry;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local process adapter for the execution seam. Secrets arrive as credential
 * references and are resolved here, registered for redaction, and injected into
 * the process environment (never argv). Scripts are materialized to a temporary
 * file and executed by a fixed shell.
 */
@Component
public class LocalCommandExecutor implements CommandExecutor {

    private final CredentialService credentials;
    private final SecretRegistry secretRegistry;

    public LocalCommandExecutor(CredentialService credentials, SecretRegistry secretRegistry) {
        this.credentials = credentials;
        this.secretRegistry = secretRegistry;
    }

    @Override
    public CommandResult run(ExecutionSpec spec, OutputSink output, CancellationToken cancellation) {
        if (!spec.target().local()) {
            throw new DomainException("execution_policy", 400, "Local executor cannot target a remote server");
        }
        long started = System.nanoTime();
        Path scriptFile = null;
        try {
            List<String> argv = new ArrayList<>(spec.argv());
            if (spec.script() != null) {
                Path dir = Files.createTempDirectory("mypaas-script-");
                scriptFile = dir.resolve("run.sh");
                Files.writeString(scriptFile, spec.script(), StandardCharsets.UTF_8);
                applyOwnerOnly(scriptFile);
                argv = List.of("/bin/bash", "-e", scriptFile.toString());
            }

            ProcessBuilder builder = new ProcessBuilder(argv);
            if (spec.workingDirectory() != null && !spec.workingDirectory().isBlank()) {
                builder.directory(Path.of(spec.workingDirectory()).toFile());
            }
            Map<String, String> env = builder.environment();
            for (Map.Entry<String, EnvBinding> entry : spec.environment().entrySet()) {
                EnvBinding binding = entry.getValue();
                if (binding.type() == EnvBinding.Type.CREDENTIAL) {
                    env.put(entry.getKey(), credentials.resolveForExecution(binding.credentialId()));
                } else {
                    env.put(entry.getKey(), binding.literalValue());
                }
            }
            env.put("MYPASS_CORRELATION_ID", spec.correlationId());
            builder.redirectErrorStream(false);

            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new DomainException("launch_failure", 500, "Unable to launch command", e);
            }

            AtomicLong produced = new AtomicLong();
            boolean[] truncated = {false};
            Thread stdout = reader(process.getInputStream(), OutputSink.Stream.STDOUT, output,
                    spec.maxOutputBytes(), produced, truncated);
            Thread stderr = reader(process.getErrorStream(), OutputSink.Stream.STDERR, output,
                    spec.maxOutputBytes(), produced, truncated);

            boolean timedOut = false;
            boolean cancelled = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(Math.max(1, spec.timeoutSeconds())).toNanos();
            while (true) {
                if (process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    break;
                }
                if (cancellation.isCancelled()) {
                    cancelled = true;
                    kill(process);
                    break;
                }
                if (System.nanoTime() > deadline) {
                    timedOut = true;
                    kill(process);
                    break;
                }
            }
            process.waitFor();
            stdout.join(2000);
            stderr.join(2000);

            return new CommandResult(process.exitValue(), truncated[0], timedOut, cancelled,
                    Duration.ofNanos(System.nanoTime() - started));
        } catch (IOException e) {
            throw new DomainException("execution_failure", 500, "Command execution failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException("execution_interrupted", 500, "Command execution was interrupted", e);
        } finally {
            deleteQuietly(scriptFile);
        }
    }

    private Thread reader(InputStream stream, OutputSink.Stream kind, OutputSink output,
                          long maxBytes, AtomicLong produced, boolean[] truncated) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    long count = produced.addAndGet(line.length() + 1L);
                    if (count > maxBytes) {
                        if (!truncated[0]) {
                            truncated[0] = true;
                            output.line(OutputSink.Stream.SYSTEM, "output truncated at " + maxBytes + " bytes");
                        }
                        continue;
                    }
                    output.line(kind, line);
                }
            } catch (IOException ignored) {
                // Process was killed or the stream closed.
            }
        }, "mypaas-exec-" + kind.name().toLowerCase());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void applyOwnerOnly(Path path) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException ignored) {
            // Windows development: ACL inheritance applies.
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }
}
