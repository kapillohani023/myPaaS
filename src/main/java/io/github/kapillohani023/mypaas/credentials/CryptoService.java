package io.github.kapillohani023.mypaas.credentials;

import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.core.DomainException;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Envelope encryption with versioned key-encryption keys (KEKs).
 *
 * <p>Each value gets a fresh AES-256 data key. The data key encrypts the value;
 * the KEK wraps the data key. Ciphertext records the KEK version, so rotation
 * re-wraps data keys without touching values. KEK files live outside the
 * database and repository.</p>
 */
@Component
public class CryptoService {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int KEY_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Path keysDir;
    private final Map<Integer, SecretKey> keks = new ConcurrentHashMap<>();
    private final Set<Integer> retired = ConcurrentHashMap.newKeySet();
    private volatile int currentVersion;

    public CryptoService(MypaasProperties properties) {
        this.keysDir = Path.of(properties.getKeysDir()).toAbsolutePath().normalize();
        loadOrCreate();
    }

    public record Sealed(byte[] ciphertext, byte[] wrappedKey, int kekVersion) {
    }

    public synchronized Sealed seal(byte[] plaintext) {
        try {
            byte[] dataKey = new byte[KEY_BYTES];
            random.nextBytes(dataKey);
            SecretKey dataKeySpec = new SecretKeySpec(dataKey, "AES");
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, dataKeySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] ciphertext = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            byte[] wrappedKey = wrap(dataKey, currentVersion);
            Arrays.fill(dataKey, (byte) 0);
            return new Sealed(ciphertext, wrappedKey, currentVersion);
        } catch (GeneralSecurityException e) {
            throw new DomainException("crypto_failure", 500, "Unable to encrypt value", e);
        }
    }

    public byte[] open(Sealed sealed) {
        try {
            byte[] dataKey = unwrap(sealed.wrappedKey(), sealed.kekVersion());
            ByteBuffer buffer = ByteBuffer.wrap(sealed.ciphertext());
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(encrypted);
            Arrays.fill(dataKey, (byte) 0);
            return plaintext;
        } catch (GeneralSecurityException e) {
            throw new DomainException("crypto_failure", 500, "Unable to decrypt value", e);
        }
    }

    /** Re-wraps a data key from one KEK version to another without decrypting the value. */
    public byte[] rewrap(byte[] wrappedKey, int fromVersion, int toVersion) {
        byte[] dataKey = unwrap(wrappedKey, fromVersion);
        try {
            return wrap(dataKey, toVersion);
        } catch (GeneralSecurityException e) {
            throw new DomainException("crypto_failure", 500, "Unable to re-wrap data key", e);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    public synchronized int rotateEncryptionKey() {
        int next = currentVersion + 1;
        byte[] material = new byte[KEY_BYTES];
        random.nextBytes(material);
        writeKey(next, material);
        keks.put(next, new SecretKeySpec(material, "AES"));
        currentVersion = next;
        return next;
    }

    /** Retires a version. Retired material is unavailable for future decrypts. */
    public synchronized void retireKey(int version) {
        if (version == currentVersion) {
            throw new DomainException("key_in_use", 409, "Cannot retire the active encryption key version");
        }
        if (!Files.exists(keyPath(version))) {
            retired.add(version);
            return;
        }
        try {
            Path retiredPath = keysDir.resolve("retired-kek-v" + version + ".key");
            Files.move(keyPath(version), retiredPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            keks.remove(version);
            retired.add(version);
        } catch (IOException e) {
            throw new DomainException("key_rotation_failure", 500, "Unable to retire encryption key", e);
        }
    }

    public int currentKekVersion() {
        return currentVersion;
    }

    public boolean isRetired(int version) {
        return retired.contains(version);
    }

    public boolean hasKey(int version) {
        return keks.containsKey(version);
    }

    private byte[] wrap(byte[] dataKey, int version) throws GeneralSecurityException {
        SecretKey kek = keks.get(version);
        if (kek == null) {
            throw new DomainException("kek_missing", 500,
                    "Encryption key version " + version + " is not available");
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] wrapped = cipher.doFinal(dataKey);
        return ByteBuffer.allocate(iv.length + wrapped.length).put(iv).put(wrapped).array();
    }

    private byte[] unwrap(byte[] wrappedKey, int version) {
        SecretKey kek = keks.get(version);
        if (kek == null) {
            throw new DomainException("kek_missing", 500,
                    "Encryption key version " + version + " is not available");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(wrappedKey);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] wrapped = new byte[buffer.remaining()];
            buffer.get(wrapped);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(wrapped);
        } catch (GeneralSecurityException e) {
            throw new DomainException("crypto_failure", 500, "Unable to unwrap data key", e);
        }
    }

    private void loadOrCreate() {
        try {
            Files.createDirectories(keysDir);
            applyOwnerOnlyPermissions(keysDir);
            try (Stream<Path> files = Files.list(keysDir)) {
                files.filter(p -> p.getFileName().toString().matches("kek-v\\d+\\.key")).forEach(path -> {
                    String name = path.getFileName().toString();
                    int version = Integer.parseInt(name.substring("kek-v".length(), name.length() - 4));
                    try {
                        byte[] material = Files.readAllBytes(path);
                        if (material.length != KEY_BYTES) {
                            throw new IllegalStateException("Invalid KEK length in " + path);
                        }
                        keks.put(version, new SecretKeySpec(material, "AES"));
                    } catch (IOException e) {
                        throw new IllegalStateException("Unable to read encryption key " + path, e);
                    }
                });
            }
            currentVersion = keks.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (currentVersion == 0) {
                int version = 1;
                byte[] material = new byte[KEY_BYTES];
                random.nextBytes(material);
                writeKey(version, material);
                keks.put(version, new SecretKeySpec(material, "AES"));
                currentVersion = version;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize encryption keys", e);
        }
    }

    private void writeKey(int version, byte[] material) {
        try {
            Path path = keyPath(version);
            Files.write(path, material);
            applyOwnerOnlyPermissions(path);
        } catch (IOException e) {
            throw new DomainException("key_rotation_failure", 500, "Unable to persist encryption key", e);
        }
    }

    private Path keyPath(int version) {
        return keysDir.resolve("kek-v" + version + ".key");
    }

    private static void applyOwnerOnlyPermissions(Path path) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                        Files.isDirectory(path) ? "rwx------" : "rw-------"));
            }
        } catch (IOException ignored) {
            // Non-POSIX file systems (for example Windows development) rely on ACLs.
        }
    }
}
