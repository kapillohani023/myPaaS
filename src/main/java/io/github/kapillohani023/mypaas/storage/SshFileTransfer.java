package io.github.kapillohani023.mypaas.storage;

import io.github.kapillohani023.mypaas.core.DomainException;
import org.springframework.stereotype.Component;
import io.github.kapillohani023.mypaas.credentials.CredentialService;
import io.github.kapillohani023.mypaas.execution.CancellationToken;
import io.github.kapillohani023.mypaas.execution.SshSupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * SSH adapter for FileTransfer. Content travels over scp (never argv), the
 * remote checksum is verified, permissions are applied, then the file is moved
 * into place atomically. The server's host key is pinned.
 */
@Component
public class SshFileTransfer implements FileTransfer {

    private final CredentialService credentials;

    public SshFileTransfer(CredentialService credentials) {
        this.credentials = credentials;
    }

    @Override
    public boolean supports(TransferTarget target) {
        return !target.local();
    }

    @Override
    public TransferReceipt put(TransferSpec spec, CancellationToken cancellation) {
        if (spec.target().local()) {
            throw new DomainException("transfer_policy", 400, "SSH transfer requires a remote server");
        }
        try (SshSupport.MaterializedIdentity identity =
                     SshSupport.materialize(toExecutionTarget(spec.target()), credentials)) {
            Path local = Files.createTempFile("mypaas-transfer-", ".tmp");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = spec.source().open();
                     OutputStream out = new DigestOutputStream(Files.newOutputStream(local), digest)) {
                    in.transferTo(out);
                }
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!actual.equals(spec.checksumSha256())) {
                    throw new DomainException("checksum_mismatch", 502, "Source checksum mismatch");
                }
                cancellation.throwIfCancelled();

                String destination = spec.destination();
                String remoteTemp = destination + ".mypaas-" + UUID.randomUUID();
                String remoteDir = destination.substring(0, destination.lastIndexOf('/') + 1);
                String remoteName = local.getFileName().toString();

                List<String> prepare = new ArrayList<>(SshSupport.sshBase(
                        toExecutionTarget(spec.target()), identity));
                prepare.add("/bin/bash -c " + SshSupport.quote("mkdir -p " + SshSupport.quote(remoteDir)));
                if (SshSupport.run(prepare, 60, null) != 0) {
                    throw new DomainException("transfer_failure", 502, "Unable to create destination directory");
                }

                List<String> scp = SshSupport.scpBase(toExecutionTarget(spec.target()), identity);
                scp.add(local.toString());
                scp.add(spec.target().username() + "@" + spec.target().host() + ":" + remoteDir);
                if (SshSupport.run(scp, 120, null) != 0) {
                    throw new DomainException("transfer_failure", 502, "SCP upload failed");
                }

                String move = "cd " + SshSupport.quote(remoteDir) + " && " +
                        "mv " + SshSupport.quote(remoteName) + " " + SshSupport.quote(remoteTemp) + " && " +
                        "chmod " + SshSupport.quote(spec.mode()) + " " + SshSupport.quote(remoteTemp) +
                        (spec.owner() == null || spec.owner().isBlank() ? "" :
                                " && chown " + SshSupport.quote(spec.owner()) + " " + SshSupport.quote(remoteTemp)) +
                        " && sha256sum " + SshSupport.quote(remoteTemp);
                List<String> verify = SshSupport.sshBase(toExecutionTarget(spec.target()), identity);
                verify.add("/bin/bash -c " + SshSupport.quote(move));
                SshSupport.ProcessOutput output = SshSupport.runCapture(verify, 120, null);
                if (output.exitCode() != 0 || !output.output().contains(actual)) {
                    cleanup(identity, spec.target(), remoteTemp);
                    throw new DomainException("checksum_mismatch", 502, "Remote checksum verification failed");
                }

                String rename = "mv -f " + SshSupport.quote(remoteTemp) + " " + SshSupport.quote(destination);
                List<String> commit = SshSupport.sshBase(toExecutionTarget(spec.target()), identity);
                commit.add("/bin/bash -c " + SshSupport.quote(rename));
                if (SshSupport.run(commit, 60, null) != 0) {
                    cleanup(identity, spec.target(), remoteTemp);
                    throw new DomainException("transfer_failure", 502, "Unable to move transferred file");
                }
                return new TransferReceipt(destination, actual, spec.sizeBytes());
            } finally {
                Files.deleteIfExists(local);
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new DomainException("transfer_failure", 500, "Unable to transfer file", e);
        }
    }

    private void cleanup(SshSupport.MaterializedIdentity identity, TransferTarget target, String remoteTemp) {
        try {
            List<String> cleanup = new ArrayList<>(SshSupport.sshBase(toExecutionTarget(target), identity));
            cleanup.add("rm -f " + SshSupport.quote(remoteTemp));
            SshSupport.run(cleanup, 20, null);
        } catch (RuntimeException ignored) {
            // Best effort.
        }
    }

    private static io.github.kapillohani023.mypaas.execution.ExecutionTarget toExecutionTarget(TransferTarget target) {
        return new io.github.kapillohani023.mypaas.execution.ExecutionTarget(
                target.serverId(), target.host(), target.port(), target.username(),
                target.local(), target.credentialId(), target.hostKeyPublic(), target.allowedRoot());
    }
}
