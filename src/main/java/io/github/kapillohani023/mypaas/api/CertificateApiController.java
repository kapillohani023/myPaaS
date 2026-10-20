package io.github.kapillohani023.mypaas.api;

import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.routing.CertificateMetadata;
import io.github.kapillohani023.mypaas.routing.CertificateStatus;
import io.github.kapillohani023.mypaas.routing.CertificateUpload;
import io.github.kapillohani023.mypaas.routing.RoutingService;
import io.github.kapillohani023.mypaas.routing.RoutingServiceImpl;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/certificates")
public class CertificateApiController {

    public record CertificateView(CertificateMetadata metadata, CertificateStatus status) {
    }

    private final RoutingService routing;
    private final RoutingServiceImpl routingRead;
    private final Authorization authorization;

    public CertificateApiController(RoutingService routing, RoutingServiceImpl routingRead,
                                    Authorization authorization) {
        this.routing = routing;
        this.routingRead = routingRead;
        this.authorization = authorization;
    }

    @PostMapping
    public ResponseEntity<CertificateView> register(@RequestBody CertificateUpload upload) {
        long id = routing.registerCertificate(upload);
        CertificateMetadata metadata = routingRead.certificate(id).orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CertificateView(metadata, routing.certificateStatus(id)));
    }

    @GetMapping
    public List<CertificateView> list() {
        authorization.require(Action.CERTIFICATE_READ, ResourceRef.global());
        return routingRead.certificates().stream()
                .map(c -> new CertificateView(c, routing.certificateStatus(c.id()))).toList();
    }

    @GetMapping("/{id}/status")
    public CertificateStatus status(@PathVariable long id) {
        authorization.require(Action.CERTIFICATE_READ, ResourceRef.global());
        return routing.certificateStatus(id);
    }
}
