package io.github.kapillohani023.mypaas.storage;

import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.credentials.CryptoService;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;

/**
 * Local filesystem adapter. Non-sensitive files are written atomically with
 * checksums verified upstream; sensitive files use the credentials module's
 * envelope encryption, so only data keys need re-wrapping on rotation.
 */
@Component
public class LocalStorageProvider implements StorageProvider {

    private static final int MAGIC = 0x4D504B31; // MPK1

    private final Path root;
    private final CryptoService crypto;
    private final SecureRandom random = new SecureRandom();

    public LocalStorageProvider(io.github.kapillohani023.mypaas.config.MypaasProperties properties,
                                CryptoService crypto) {
        this.root = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
        this.crypto = crypto;
        try {
            Files.createDirectories(root);
            Files.createDirectories(root.resolve(".tmp"));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize storage root", e);
        }
    }

    @Override
    public void put(String storageKey, Path source, boolean sensitive) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = root.resolve(".tmp").resolve(storageKey.replace("/", "-") + "-" + random.nextLong());
            if (sensitive) {
                byte[] plaintext = Files.readAllBytes(source);
                CryptoService.Sealed sealed = crypto.seal(plaintext);
                try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                    out.writeInt(MAGIC);
                    out.writeInt(sealed.kekVersion());
                    out.writeInt(sealed.wrappedKey().length);
                    out.write(sealed.wrappedKey());
                    out.writeInt(sealed.ciphertext().length);
                    out.write(sealed.ciphertext());
                }
                java.util.Arrays.fill(plaintext, (byte) 0);
            } else {
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DomainException("storage_failure", 500, "Unable to write stored file", e);
        }
    }

    @Override
    public InputStream open(String storageKey, boolean sensitive) {
        Path path = resolve(storageKey);
        try {
            if (!sensitive) {
                return Files.newInputStream(path);
            }
            try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
                if (in.readInt() != MAGIC) {
                    throw new DomainException("storage_corrupt", 500, "Stored file header is invalid");
                }
                int version = in.readInt();
                byte[] wrapped = new byte[in.readInt()];
                in.readFully(wrapped);
                byte[] ciphertext = new byte[in.readInt()];
                in.readFully(ciphertext);
                byte[] plaintext = crypto.open(new CryptoService.Sealed(ciphertext, wrapped, version));
                return new ByteArrayInputStream(plaintext);
            }
        } catch (IOException e) {
            throw new DomainException("storage_failure", 500, "Unable to read stored file", e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new DomainException("storage_failure", 500, "Unable to delete stored file", e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.exists(resolve(storageKey));
    }

    @Override
    public void rewrap(String storageKey) {
        Path path = resolve(storageKey);
        try {
            int version;
            byte[] wrapped;
            byte[] ciphertext;
            try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
                if (in.readInt() != MAGIC) {
                    throw new DomainException("storage_corrupt", 500, "Stored file header is invalid");
                }
                version = in.readInt();
                wrapped = new byte[in.readInt()];
                in.readFully(wrapped);
                ciphertext = new byte[in.readInt()];
                in.readFully(ciphertext);
            }
            int current = crypto.currentKekVersion();
            if (version >= current) {
                return;
            }
            byte[] rewrapped = crypto.rewrap(wrapped, version, current);
            Path tmp = path.resolveSibling(path.getFileName() + ".rewrap");
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(MAGIC);
                out.writeInt(current);
                out.writeInt(rewrapped.length);
                out.write(rewrapped);
                out.writeInt(ciphertext.length);
                out.write(ciphertext);
            }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DomainException("storage_failure", 500, "Unable to rotate stored-file key", e);
        }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || !storageKey.matches("[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f-]{36}")) {
            throw new DomainException("storage_key_invalid", 500, "Invalid storage key");
        }
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw new DomainException("storage_key_invalid", 500, "Invalid storage key");
        }
        return path;
    }
}
