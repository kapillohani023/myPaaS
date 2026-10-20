package io.github.kapillohani023.mypaas.api;

import io.github.kapillohani023.mypaas.core.ForbiddenException;
import io.github.kapillohani023.mypaas.core.PageRequest;
import io.github.kapillohani023.mypaas.core.PageResult;
import io.github.kapillohani023.mypaas.storage.StoredFile;
import io.github.kapillohani023.mypaas.storage.StorageService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class FileApiController {

    private final StorageService storage;

    public FileApiController(StorageService storage) {
        this.storage = storage;
    }

    @GetMapping("/projects/{projectId}/files")
    public List<StoredFile> list(@PathVariable long projectId,
                                 @RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size) {
        PageResult<StoredFile> result = storage.list(projectId, PageRequest.of(page == null ? 0 : page,
                size == null ? 100 : size));
        return result.items();
    }

    @PostMapping("/projects/{projectId}/files")
    public ResponseEntity<StoredFile> upload(@PathVariable long projectId,
                                             @RequestParam("file") MultipartFile file) throws IOException {
        StoredFile stored = storage.upload(projectId, file.getOriginalFilename(),
                file.getContentType(), file.getInputStream(), file.getSize());
        return ResponseEntity.status(HttpStatus.CREATED).body(stored);
    }

    @GetMapping("/files/{id}/content")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        StoredFile file = storage.metadata(id);
        if (file.sensitive() || file.certificateId() != null) {
            throw new ForbiddenException("Sensitive certificate material cannot be downloaded");
        }
        byte[] content = storage.read(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.originalName(), StandardCharsets.UTF_8).build().toString())
                .contentType(file.contentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(file.contentType()))
                .body(content);
    }

    @DeleteMapping("/files/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        storage.delete(id);
        return ResponseEntity.noContent().build();
    }
}
