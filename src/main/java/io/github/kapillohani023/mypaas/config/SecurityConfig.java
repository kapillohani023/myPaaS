package io.github.kapillohani023.mypaas.config;

import io.github.kapillohani023.mypaas.audit.AuditService;
import io.github.kapillohani023.mypaas.identity.JwtAuthenticationFilter;
import io.github.kapillohani023.mypaas.identity.LoginRateLimitFilter;
import io.github.kapillohani023.mypaas.identity.PasswordChangeRequiredFilter;
import io.github.kapillohani023.mypaas.identity.TokenService;
import io.github.kapillohani023.mypaas.identity.UserService;
import io.github.kapillohani023.mypaas.ratelimit.RateLimiter;
import io.github.kapillohani023.mypaas.identity.MypaasUserDetailsService;
import io.github.kapillohani023.mypaas.security.MypaasPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(TokenService tokens, UserService users) {
        return new JwtAuthenticationFilter(tokens, users);
    }

    @Bean
    public LoginRateLimitFilter loginRateLimitFilter(RateLimiter rateLimiter, AuditService audit) {
        return new LoginRateLimitFilter(rateLimiter, audit);
    }

    @Bean
    public PasswordChangeRequiredFilter passwordChangeRequiredFilter() {
        return new PasswordChangeRequiredFilter();
    }

    /**
     * These filters are part of the security chain only. Spring Boot would
     * otherwise auto-register every Filter bean as a servlet filter as well,
     * causing duplicate execution and double-counting rate limits.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<LoginRateLimitFilter> loginRateLimitFilterRegistration(
            LoginRateLimitFilter filter) {
        FilterRegistrationBean<LoginRateLimitFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<PasswordChangeRequiredFilter> passwordChangeFilterRegistration(
            PasswordChangeRequiredFilter filter) {
        FilterRegistrationBean<PasswordChangeRequiredFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public AuthenticationManager authenticationManager(MypaasUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
                                                   LoginRateLimitFilter loginRateLimitFilter,
                                                   PasswordChangeRequiredFilter passwordChangeRequiredFilter,
                                                   MypaasUserDetailsService userDetailsService,
                                                   PasswordEncoder passwordEncoder) throws Exception {
        http
                .authenticationProvider(authenticationProvider(userDetailsService, passwordEncoder))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/forgot-password", "/reset-password", "/verify-email",
                                "/css/**", "/js/**", "/img/**", "/favicon.ico",
                                "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/password-reset/**",
                                "/api/v1/webhooks/**", "/tracking/**", "/actuator/health", "/openapi.yaml", "/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/dashboard", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/api/**", "/tracking/**", "/api/v1/webhooks/**"))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            if (request.getRequestURI().startsWith("/api/")) {
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                response.getWriter().write("""
                                        {"error":{"code":"unauthenticated","message":"Authentication required"}}
                                        """.strip());
                            } else {
                                response.sendRedirect("/login");
                            }
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            if (request.getRequestURI().startsWith("/api/")) {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                response.getWriter().write("""
                                        {"error":{"code":"forbidden","message":"Forbidden"}}
                                        """.strip());
                            } else {
                                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                            }
                        }))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(passwordChangeRequiredFilter, AuthorizationFilter.class);
        return http.build();
    }

    private DaoAuthenticationProvider authenticationProvider(MypaasUserDetailsService userDetailsService,
                                                             PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }
}
