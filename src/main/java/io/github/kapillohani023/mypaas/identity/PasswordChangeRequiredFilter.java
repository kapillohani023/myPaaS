package io.github.kapillohani023.mypaas.identity;

import io.github.kapillohani023.mypaas.security.MypaasPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Forces first-login password changes for bootstrap and administrator-created accounts. */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof MypaasPrincipal principal
                && principal.mustChangePassword()) {
            String path = request.getRequestURI();
            boolean allowed = path.equals("/change-password")
                    || path.equals("/logout")
                    || path.startsWith("/css/")
                    || path.startsWith("/js/")
                    || path.startsWith("/img/")
                    || path.startsWith("/api/")
                    || path.startsWith("/actuator/");
            if (!allowed) {
                response.sendRedirect("/change-password");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
