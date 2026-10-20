package io.github.kapillohani023.mypaas.api;

import io.github.kapillohani023.mypaas.deployment.DeploymentHandle;
import io.github.kapillohani023.mypaas.deployment.DeploymentService;
import io.github.kapillohani023.mypaas.deployment.DeploymentStatus;
import io.github.kapillohani023.mypaas.logging.LogEntry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/deployments")
public class DeploymentApiController {

    private final DeploymentService deployments;

    public DeploymentApiController(DeploymentService deployments) {
        this.deployments = deployments;
    }

    @GetMapping("/{id}")
    public DeploymentStatus status(@PathVariable long id) {
        return deployments.status(id);
    }

    @GetMapping("/{id}/logs")
    public List<LogEntry> logs(@PathVariable long id,
                               @RequestParam(defaultValue = "0") long from,
                               @RequestParam(defaultValue = "1000") int limit) {
        List<LogEntry> entries = deployments.logs(id, from);
        return entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    @GetMapping(value = "/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long id, @RequestParam(defaultValue = "0") long from) {
        return deployments.streamLogs(id, from);
    }

    @PostMapping("/{id}/cancel")
    public DeploymentStatus cancel(@PathVariable long id) {
        deployments.cancel(id);
        return deployments.status(id);
    }

    @PostMapping("/{id}/rollback")
    public DeploymentHandle rollback(@PathVariable long id) {
        return deployments.rollback(id);
    }
}
