package io.github.kapillohani023.mypaas.identity;

import io.github.kapillohani023.mypaas.audit.AuditOutcome;
import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.core.RateLimitedException;
import io.github.kapillohani023.mypaas.ratelimit.RateLimiter;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Persistent login throttling for the session/form adapter (the API adapter throttles itself). */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final AuditService audit;

    public LoginRateLimitFilter(RateLimiter rateLimiter, AuditService audit) {
        this.rateLimiter = rateLimiter;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/login".equals(request.getRequestURI())) {
            String email = request.getParameter("username");
            try {
                rateLimiter.acquire(RateLimiter.RateLimitKey.of("login-ip", clientIp(request)),
                        RateLimiter.RateLimitPolicy.of(30, 900));
                if (email != null && !email.isBlank()) {
                    rateLimiter.acquire(RateLimiter.RateLimitKey.of("login-email", email),
                            RateLimiter.RateLimitPolicy.of(10, 900));
                }
            } catch (RateLimitedException e) {
                audit.record(null, "LOGIN_RATE_LIMIT", ResourceRef.global(), AuditOutcome.DENIED,
                        null, "login throttled");
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(e.retryAfterSeconds()));
                response.setContentType("text/html;charset=UTF-8");
                response.getWriter().write("<!doctype html><html><body style=\"font-family:system-ui;padding:40px\">"
                        + "<h1>Too many login attempts</h1><p>Try again in "
                        + e.retryAfterSeconds() + " seconds.</p>"
                        + "<p><a href=\"/login\">Back to sign in</a></p></body></html>");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
