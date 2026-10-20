package io.github.kapillohani023.mypaas.storage;

import io.github.kapillohani023.mypaas.core.ConflictException;
import io.github.kapillohani023.mypaas.core.DomainException;
import org.springframework.stereotype.Component;
import io.github.kapillohani023.mypaas.execution.CancellationToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Local adapter for FileTransfer. Writes atomically, verifies checksum, applies mode. */
@Component
public class LocalFileTransfer implements FileTransfer {

    @Override
    public boolean supports(TransferTarget target) {
        return target.local();
    }

    @Override
    public TransferReceipt put(TransferSpec spec, CancellationToken cancellation) {
        if (!spec.target().local()) {
            throw new DomainException("transfer_policy", 400, "Local transfer requires a local server");
        }
        Path destination = Path.of(spec.destination()).toAbsolutePath().normalize();
        Path root = Path.of(spec.target().allowedRoot()).toAbsolutePath().normalize();
        if (!destination.startsWith(root)) {
            throw new DomainException("destination_rejected", 422, "Destination is outside the allowed root");
        }
        try {
            Files.createDirectories(destination.getParent());
            if (!spec.overwrite() && Files.exists(destination)) {
                throw new ConflictException("destination_exists", "Destination already exists: " + destination);
            }
            Path temp = Files.createTempFile(destination.getParent(), ".mypaas-", ".tmp");
            cancellation.throwIfCancelled();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = spec.source().open();
                 var out = new java.security.DigestOutputStream(Files.newOutputStream(temp), digest)) {
                in.transferTo(out);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(spec.checksumSha256())) {
                Files.deleteIfExists(temp);
                throw new DomainException("checksum_mismatch", 502, "Transferred content checksum mismatch");
            }
            applyMode(temp, spec.mode());
            try {
                if (spec.owner() != null && !spec.owner().isBlank()) {
                    Files.setOwner(temp, temp.getFileSystem().getUserPrincipalLookupService()
                            .lookupPrincipalByName(spec.owner()));
                }
            } catch (IOException | UnsupportedOperationException ignored) {
                // Owner changes are best-effort on non-POSIX hosts; mode remains enforced where supported.
            }
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return new TransferReceipt(destination.toString(), actual, spec.sizeBytes());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new DomainException("transfer_failure", 500, "Unable to transfer file", e);
        }
    }

    static void applyMode(Path path, String mode) {
        if (mode == null || !mode.matches("[0-7]{4}")) {
            throw new DomainException("mode_invalid", 422, "File mode must be four octal digits");
        }
        try {
            if (Files.getFileStore(path).supportsFileAttributeView(
                    java.nio.file.attribute.PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(path, octal(mode));
            }
        } catch (IOException ignored) {
            // Windows development.
        }
    }

    static Set<PosixFilePermission> octal(String mode) {
        StringBuilder symbolic = new StringBuilder();
        int[] bits = {0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001};
        String[] flags = {"r", "w", "x", "r", "w", "x", "r", "w", "x"};
        int value = Integer.parseInt(mode, 8);
        for (int i = 0; i < bits.length; i++) {
            if ((value & bits[i]) != 0) {
                symbolic.append(flags[i]);
            }
        }
        return PosixFilePermissions.fromString(symbolic.toString());
    }
}
