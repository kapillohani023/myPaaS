package io.github.kapillohani023.mypaas.identity;

import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.MypaasPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bearer-token adapter for the API. JWT claims identify the user; the role and
 * existence are reloaded from the database on every request.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokens;
    private final UserService users;

    public JwtAuthenticationFilter(TokenService tokens, UserService users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                TokenService.Claims claims = tokens.verify(header.substring(7));
                users.findById(claims.sub()).filter(User::emailVerified).ifPresent(user -> {
                    Actor actor = new Actor(user.id(), user.email(), user.displayName(), user.role());
                    MypaasPrincipal principal = new MypaasPrincipal(actor, user.passwordHash(), true,
                            user.mustChangePassword());
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            } catch (RuntimeException ignored) {
                // Leave the request unauthenticated; entry point returns 401.
            }
        }
        String grant = request.getHeader("X-Step-Up-Grant");
        if (grant != null && !grant.isBlank()) {
            ActorContext.setGrant(grant);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            ActorContext.setGrant(null);
        }
    }
}
