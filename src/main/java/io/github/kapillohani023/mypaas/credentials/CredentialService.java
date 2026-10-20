package io.github.kapillohani023.mypaas.credentials;

import io.github.kapillohani023.mypaas.core.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Credential-reference lifecycle and envelope-encryption rotation.
 *
 * <p>Secret values are never returned by read methods. Only execution adapters
 * call {@link #resolveForExecution(long)}; the resolved value is registered with
 * {@link SecretRegistry} so logs can redact it.</p>
 */
@Service
public class CredentialService {

    public record Credential(long id, String name, Long projectId, int kekVersion,
                             long createdAt, Long rotatedAt, Long revokedAt) {
        public boolean revoked() {
            return revokedAt != null;
        }
    }

    private static final RowMapper<Credential> MAPPER = (rs, rowNum) -> new Credential(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getObject("project_id", Long.class),
            rs.getInt("kek_version"),
            rs.getLong("created_at"),
            rs.getObject("rotated_at", Long.class),
            rs.getObject("revoked_at", Long.class));

    private final JdbcTemplate jdbc;
    private final CryptoService crypto;
    private final SecretRegistry secretRegistry;
    private final Clock clock;

    public CredentialService(JdbcTemplate jdbc, CryptoService crypto, SecretRegistry secretRegistry, Clock clock) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.secretRegistry = secretRegistry;
        this.clock = clock;
    }

    @Transactional
    public Credential store(String name, Long projectId, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Secret value is required");
        }
        CryptoService.Sealed sealed = crypto.seal(secret.getBytes(StandardCharsets.UTF_8));
        KeyHolder keys = new GeneratedKeyHolder();
        long now = clock.millis();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into credentials (name, project_id, ciphertext, wrapped_key, kek_version, created_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setObject(2, projectId);
            ps.setBytes(3, sealed.ciphertext());
            ps.setBytes(4, sealed.wrappedKey());
            ps.setInt(5, sealed.kekVersion());
            ps.setLong(6, now);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        secretRegistry.register(secret);
        return find(id).orElseThrow();
    }

    public Optional<Credential> find(long id) {
        return jdbc.query("select * from credentials where id = ?", MAPPER, id).stream().findFirst();
    }

    public List<Credential> list(Long projectId) {
        if (projectId == null) {
            return jdbc.query("select * from credentials order by id", MAPPER);
        }
        return jdbc.query("select * from credentials where project_id = ? or project_id is null order by id",
                MAPPER, projectId);
    }

    @Transactional
    public Credential rotate(long id, String newSecret) {
        Credential credential = find(id).orElseThrow(() -> new NotFoundException("Credential"));
        if (credential.revoked()) {
            throw new NotFoundException("Credential");
        }
        CryptoService.Sealed sealed = crypto.seal(newSecret.getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                        update credentials set ciphertext = ?, wrapped_key = ?, kek_version = ?, rotated_at = ?
                        where id = ?
                        """,
                sealed.ciphertext(), sealed.wrappedKey(), sealed.kekVersion(), clock.millis(), id);
        secretRegistry.register(newSecret);
        return find(id).orElseThrow();
    }

    @Transactional
    public void revoke(long id) {
        int changed = jdbc.update("update credentials set revoked_at = ? where id = ? and revoked_at is null",
                clock.millis(), id);
        if (changed == 0) {
            throw new NotFoundException("Credential");
        }
    }

    /**
     * Internal execution-only resolution. Registers the value for redaction.
     * Never expose this through a controller.
     */
    public String resolveForExecution(long id) {
        List<Object[]> rows = jdbc.query("""
                        select ciphertext, wrapped_key, kek_version, revoked_at from credentials where id = ?
                        """,
                (rs, rowNum) -> new Object[]{
                        rs.getBytes("ciphertext"),
                        rs.getBytes("wrapped_key"),
                        rs.getInt("kek_version"),
                        rs.getObject("revoked_at", Long.class)
                }, id);
        if (rows.isEmpty() || rows.getFirst()[3] != null) {
            throw new NotFoundException("Credential");
        }
        Object[] row = rows.getFirst();
        byte[] plaintext = crypto.open(new CryptoService.Sealed(
                (byte[]) row[0], (byte[]) row[1], (Integer) row[2]));
        String value = new String(plaintext, StandardCharsets.UTF_8);
        java.util.Arrays.fill(plaintext, (byte) 0);
        secretRegistry.register(value);
        return value;
    }

    /**
     * Adds a KEK version and re-wraps every credential's data key in bounded
     * batches. Values are not decrypted. Retirement is coordinated by
     * {@link KeyRotationService} after storage has re-wrapped its files.
     */
    @Transactional
    public int rotateEncryptionKey() {
        int newVersion = crypto.rotateEncryptionKey();
        long lastId = 0;
        while (true) {
            List<Object[]> batch = jdbc.query("""
                            select id, wrapped_key, kek_version from credentials
                            where kek_version < ? and id > ? order by id limit 100
                            """,
                    (rs, rowNum) -> new Object[]{
                            rs.getLong("id"),
                            rs.getBytes("wrapped_key"),
                            rs.getInt("kek_version")
                    }, newVersion, lastId);
            if (batch.isEmpty()) {
                break;
            }
            for (Object[] row : batch) {
                long id = (Long) row[0];
                byte[] wrapped = (byte[]) row[1];
                int from = (Integer) row[2];
                byte[] rewrapped = crypto.rewrap(wrapped, from, newVersion);
                jdbc.update("update credentials set wrapped_key = ?, kek_version = ? where id = ?",
                        rewrapped, newVersion, id);
                lastId = id;
            }
        }
        return newVersion;
    }

    /** Retires every KEK version lower than {@code activeVersion} after a full re-wrap. */
    public void retireKeysBelow(int activeVersion) {
        Set<Integer> versions = new TreeSet<>(jdbc.queryForList(
                "select distinct kek_version from credentials where kek_version < ?",
                Integer.class, activeVersion));
        for (int version : versions) {
            if (crypto.hasKey(version)) {
                crypto.retireKey(version);
            }
        }
    }
}
