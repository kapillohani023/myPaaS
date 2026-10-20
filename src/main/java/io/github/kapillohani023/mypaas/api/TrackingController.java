package io.github.kapillohani023.mypaas.api;

import io.github.kapillohani023.mypaas.analytics.AnalyticsService;
import io.github.kapillohani023.mypaas.analytics.ClientEvent;
import io.github.kapillohani023.mypaas.core.NotFoundException;
import io.github.kapillohani023.mypaas.ratelimit.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Documented, rate-limited client tracking endpoint. */
@RestController
@RequestMapping("/tracking/v1")
public class TrackingController {

    public record TrackRequest(String trackingKey, String type, String path, String hostname) {
    }

    private final JdbcTemplate jdbc;
    private final AnalyticsService analytics;
    private final RateLimiter rateLimiter;

    public TrackingController(JdbcTemplate jdbc, AnalyticsService analytics, RateLimiter rateLimiter) {
        this.jdbc = jdbc;
        this.analytics = analytics;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/event")
    public ResponseEntity<Void> event(@RequestBody TrackRequest request) {
        rateLimiter.acquire(RateLimiter.RateLimitKey.of("tracking",
                        request.trackingKey() == null ? "unknown" : request.trackingKey()),
                RateLimiter.RateLimitPolicy.of(300, 60));
        List<Long> projectIds = jdbc.queryForList(
                "select id from projects where tracking_key = ?", Long.class, request.trackingKey());
        if (projectIds.isEmpty()) {
            throw new NotFoundException("Project");
        }
        analytics.recordClientEvent(new ClientEvent(projectIds.getFirst(), request.type(),
                request.path(), request.hostname(), "tracking-script", System.currentTimeMillis()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @GetMapping(value = "/script.js", produces = "application/javascript")
    public ResponseEntity<byte[]> script(@RequestParam("key") String key,
                                         @RequestParam(value = "host", required = false) String host) {
        String script = """
                (function () {
                  var key = %s;
                  var host = %s || location.hostname;
                  function send(type, path) {
                    try {
                      navigator.sendBeacon ? navigator.sendBeacon('/tracking/v1/event',
                        new Blob([JSON.stringify({trackingKey:key,type:type,path:path,hostname:host})],
                          {type:'application/json'})) : fetch('/tracking/v1/event', {method:'POST',
                        headers:{'Content-Type':'application/json'},
                        body:JSON.stringify({trackingKey:key,type:type,path:path,hostname:host})});
                    } catch (e) {}
                  }
                  if (!sessionStorage.getItem('mypaas-pv')) {
                    sessionStorage.setItem('mypaas-pv','1'); send('PAGE_VIEW', location.pathname);
                  }
                  document.addEventListener('click', function (e) {
                    var a = e.target.closest ? e.target.closest('a,button') : null;
                    if (a) send('CLICK', location.pathname);
                  }, {capture:true, passive:true});
                })();
                """.formatted(jsString(key), host == null ? "null" : jsString(host));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .body(script.getBytes(StandardCharsets.UTF_8));
    }

    private static String jsString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
