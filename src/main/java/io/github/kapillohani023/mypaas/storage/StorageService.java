package io.github.kapillohani023.mypaas.storage;

import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.config.MypaasProperties;
import io.github.kapillohani023.mypaas.core.ConflictException;
import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.core.NotFoundException;
import io.github.kapillohani023.mypaas.core.PageResult;
import io.github.kapillohani023.mypaas.core.PageRequest;
import io.github.kapillohani023.mypaas.execution.CancellationToken;
import io.github.kapillohani023.mypaas.ratelimit.RateLimiter;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Owns file bytes and metadata. Storage keys are opaque; callers never see
 * filesystem paths. Sensitive files are encrypted through the credentials
 * module. Every tenant access is authorized from the security context.
 */
@Service
public class StorageService {

    private static final RowMapper<StoredFile> MAPPER = (rs, rowNum) -> new StoredFile(
            rs.getLong("id"),
            rs.getString("storage_key"),
            rs.getString("original_name"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getString("checksum_sha256"),
            rs.getBoolean("sensitive"),
            rs.getObject("project_id", Long.class),
            rs.getObject("certificate_id", Long.class),
            rs.getLong("created_at"),
            rs.getObject("created_by", Long.class));

    private final JdbcTemplate jdbc;
    private final StorageProvider provider;
    private final FileTransferRouter transfers;
    private final Authorization authorization;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final long maxUploadBytes;

    public StorageService(JdbcTemplate jdbc, StorageProvider provider, FileTransferRouter transfers,
                          Authorization authorization, RateLimiter rateLimiter,
                          MypaasProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.transfers = transfers;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.maxUploadBytes = properties.getMaxUploadBytes();
    }

    @Transactional
    public StoredFile upload(long projectId, String filename, String contentType,
                             InputStream content, long declaredSize) {
        authorization.require(Action.FILE_UPLOAD, ResourceRef.project(projectId));
        long actorId = ActorContext.requireActor().id();
        rateLimiter.acquire(RateLimiter.RateLimitKey.of("file-upload", actorId + ":" + projectId),
                RateLimiter.RateLimitPolicy.of(30, 60));
        return store(projectId, null, filename, contentType, content, declaredSize, false, actorId);
    }

    /** Internal (deployment, certificate) storage path. Callers have already authorized. */
    @Transactional
    public StoredFile storeInternal(Long projectId, Long certificateId, String filename, String contentType,
                                    InputStream content, long declaredSize, boolean sensitive, Long createdBy) {
        return store(projectId, certificateId, filename, contentType, content, declaredSize, sensitive, createdBy);
    }

    private StoredFile store(Long projectId, Long certificateId, String filename, String contentType,
                             InputStream content, long declaredSize, boolean sensitive, Long createdBy) {
        if (filename == null || filename.isBlank() || filename.length() > 255) {
            throw new DomainException("filename_invalid", 422, "A file name is required");
        }
        if (declaredSize > maxUploadBytes) {
            throw new DomainException("file_too_large", 413,
                    "Uploads are limited to " + (maxUploadBytes / (1024 * 1024)) + " MB");
        }
        Path temp = null;
        try {
            temp = Files.createTempFile("mypaas-upload-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total;
            try (InputStream in = new DigestInputStream(content, digest);
                 var out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int read;
                total = 0;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > maxUploadBytes) {
                        throw new DomainException("file_too_large", 413,
                                "Uploads are limited to " + (maxUploadBytes / (1024 * 1024)) + " MB");
                    }
                    out.write(buffer, 0, read);
                }
            }
            String checksum = HexFormat.of().formatHex(digest.digest());
            final long storedSize = total;
            String storageKey = newStorageKey();
            provider.put(storageKey, temp, sensitive);
            long now = clock.millis();
            KeyHolder keys = new GeneratedKeyHolder();
            String safeName = Path.of(filename).getFileName().toString();
            jdbc.update(connection -> {
                var ps = connection.prepareStatement("""
                        insert into stored_files (storage_key, original_name, content_type, size_bytes,
                                                  checksum_sha256, sensitive, project_id, certificate_id,
                                                  created_at, created_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, java.sql.Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, storageKey);
                ps.setString(2, safeName);
                ps.setString(3, contentType == null ? "application/octet-stream" : contentType);
                ps.setLong(4, storedSize);
                ps.setString(5, checksum);
                ps.setBoolean(6, sensitive);
                ps.setObject(7, projectId);
                ps.setObject(8, certificateId);
                ps.setLong(9, now);
                ps.setObject(10, createdBy);
                return ps;
            }, keys);
            long id = keys.getKey().longValue();
            return find(id);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new DomainException("storage_failure", 500, "Unable to store file", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public StoredFile metadata(long id) {
        return find(id);
    }

    private StoredFile find(long id) {
        List<StoredFile> found = jdbc.query("select * from stored_files where id = ?", MAPPER, id);
        if (found.isEmpty()) {
            throw new NotFoundException("File");
        }
        return found.getFirst();
    }

    public PageResult<StoredFile> list(long projectId, PageRequest page) {
        authorization.require(Action.FILE_READ, ResourceRef.project(projectId));
        Long total = jdbc.queryForObject("select count(*) from stored_files where project_id = ?",
                Long.class, projectId);
        List<StoredFile> files = jdbc.query("""
                        select * from stored_files where project_id = ?
                        order by created_at desc limit ? offset ?
                        """,
                MAPPER, projectId, page.size(), page.offset());
        return PageResult.of(files, page, total == null ? 0 : total);
    }

    public List<StoredFile> listInternal(long projectId) {
        return jdbc.query("select * from stored_files where project_id = ? order by created_at desc",
                MAPPER, projectId);
    }

    /** Authorized content read. Sensitive file bytes are still decrypted; callers never see them for certificate keys. */
    public byte[] read(long fileId) {
        authorization.require(Action.FILE_READ, ResourceRef.file(fileId));
        return readInternal(fileId);
    }

    public byte[] readInternal(long fileId) {
        StoredFile file = metadata(fileId);
        try (InputStream in = provider.open(file.storageKey(), file.sensitive())) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new DomainException("storage_failure", 500, "Unable to read file", e);
        }
    }

    @Transactional
    public void delete(long fileId) {
        authorization.require(Action.FILE_DELETE, ResourceRef.file(fileId));
        deleteInternal(fileId);
    }

    @Transactional
    public void deleteInternal(long fileId) {
        StoredFile file = metadata(fileId);
        provider.delete(file.storageKey());
        jdbc.update("delete from stored_files where id = ?", fileId);
    }

    @Transactional
    public void associateCertificate(long fileId, long certificateId) {
        jdbc.update("update stored_files set certificate_id = ? where id = ?", certificateId, fileId);
    }

    /**
     * Idempotent transfer to a managed server. Destination is validated against
     * the target's allowed root; content is never logged.
     */
    public TransferReceipt transfer(long fileId, TransferTarget target, String destination,
                                    String owner, String mode, boolean overwrite) {
        StoredFile file = metadata(fileId);
        if (file.projectId() != null) {
            authorization.require(Action.FILE_READ, ResourceRef.file(fileId));
        }
        validateDestination(destination, target.allowedRoot());
        byte[] content = readInternal(fileId);
        try {
            TransferReceipt receipt = transfers.put(new TransferSpec(
                    target,
                    () -> new java.io.ByteArrayInputStream(content),
                    destination,
                    owner,
                    mode,
                    file.checksumSha256(),
                    file.sizeBytes(),
                    overwrite,
                    "storage-transfer-" + fileId
            ), CancellationToken.none());
            return receipt;
        } finally {
            java.util.Arrays.fill(content, (byte) 0);
        }
    }

    /** Internal transfer for generated content (env files, scripts) with its own checksum. */
    public TransferReceipt transferContent(byte[] content, String checksum, TransferTarget target,
                                           String destination, String owner, String mode, boolean overwrite) {
        validateDestination(destination, target.allowedRoot());
        return transfers.put(new TransferSpec(target,
                () -> new java.io.ByteArrayInputStream(content),
                destination, owner, mode, checksum, content.length, overwrite,
                "storage-generated"), CancellationToken.none());
    }

    @Transactional
    public StoredFile storeSensitiveGenerated(Long projectId, String filename, String contentType,
                                              byte[] content, Long createdBy) {
        return store(projectId, null, filename, contentType, new java.io.ByteArrayInputStream(content),
                content.length, true, createdBy);
    }

    /** Re-wraps every sensitive file's data key after a KEK rotation. */
    @Transactional
    public int rotateEncryptionKey() {
        List<String> keys = jdbc.queryForList(
                "select storage_key from stored_files where sensitive = true", String.class);
        for (String key : keys) {
            provider.rewrap(key);
        }
        return keys.size();
    }

    public static String checksum(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public long uploadLimitBytes() {
        return maxUploadBytes;
    }

    private static String newStorageKey() {
        String uuid = UUID.randomUUID().toString();
        return uuid.substring(0, 2) + "/" + uuid.substring(2, 4) + "/" + uuid;
    }

    private static void validateDestination(String destination, String allowedRoot) {
        if (destination == null || !destination.startsWith("/")
                || !destination.matches("/[A-Za-z0-9/._-]+")) {
            throw new DomainException("destination_rejected", 422,
                    "Destination must be an absolute path using safe characters");
        }
        String normalized = normalizeUnix(destination);
        String root = normalizeUnix(allowedRoot == null ? "" : allowedRoot);
        if (root.isEmpty() || !(normalized.equals(root) || normalized.startsWith(root + "/"))) {
            throw new DomainException("destination_rejected", 422, "Destination is outside the allowed root");
        }
    }

    static String normalizeUnix(String path) {
        if (path == null || !path.startsWith("/")) {
            return "";
        }
        java.util.Deque<String> parts = new java.util.ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
                continue;
            }
            parts.addLast(part);
        }
        return "/" + String.join("/", parts);
    }
}
