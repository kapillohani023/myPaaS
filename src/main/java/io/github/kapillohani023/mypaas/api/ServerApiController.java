package io.github.kapillohani023.mypaas.api;

import io.github.kapillohani023.mypaas.core.PageRequest;
import io.github.kapillohani023.mypaas.core.PageResult;
import io.github.kapillohani023.mypaas.server.ManagedServer;
import io.github.kapillohani023.mypaas.server.ServerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/servers")
public class ServerApiController {

    public record RegisterServerRequest(String name, String host, Integer sshPort, String username,
                                         Long credentialId, Boolean local, String os,
                                         String hostKeyFingerprint, String hostKeyPublic,
                                         String allowedRoot) {
    }

    private final ServerService servers;

    public ServerApiController(ServerService servers) {
        this.servers = servers;
    }

    @GetMapping
    public PageResult<ManagedServer> list(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return servers.list(PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public ManagedServer get(@PathVariable long id) {
        return servers.get(id);
    }

    @PostMapping
    public ResponseEntity<ManagedServer> register(@RequestBody RegisterServerRequest request) {
        ManagedServer server = servers.register(request.name(), request.host(),
                request.sshPort() == null ? 22 : request.sshPort(),
                request.username(), request.credentialId(), Boolean.TRUE.equals(request.local()),
                request.os(), request.hostKeyFingerprint(), request.hostKeyPublic(), request.allowedRoot());
        return ResponseEntity.status(HttpStatus.CREATED).body(server);
    }

    @PostMapping("/{id}/verify")
    public ManagedServer verify(@PathVariable long id) {
        return servers.verifyConnectivity(id);
    }
}
