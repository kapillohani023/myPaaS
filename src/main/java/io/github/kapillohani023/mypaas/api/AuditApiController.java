package io.github.kapillohani023.mypaas.api;

import io.github.kapillohani023.mypaas.audit.AuditEntry;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.core.PageRequest;
import io.github.kapillohani023.mypaas.core.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditApiController {

    private final AuditService audit;

    public AuditApiController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    public PageResult<AuditEntry> query(@RequestParam(required = false) String action,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return audit.query(action, PageRequest.of(page, size));
    }
}
