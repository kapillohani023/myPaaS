package io.github.kapillohani023.mypaas.api;

import io.github.kapillohani023.mypaas.authorization.Authorization;
import io.github.kapillohani023.mypaas.core.PageRequest;
import io.github.kapillohani023.mypaas.core.ValidationException;
import io.github.kapillohani023.mypaas.identity.AuthService;
import io.github.kapillohani023.mypaas.identity.TokenService;
import io.github.kapillohani023.mypaas.identity.User;
import io.github.kapillohani023.mypaas.identity.UserService;
import io.github.kapillohani023.mypaas.project.ProjectService;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.ResourceType;
import io.github.kapillohani023.mypaas.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthApiController {

    public record LoginRequest(String email, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record StepUpBeginRequest(String action, String resourceType, Long resourceId) {
    }

    public record StepUpCompleteRequest(String challengeId, String code) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    public record ResetRequest(String email) {
    }

    public record ResetConfirmRequest(String token, String newPassword) {
    }

    public record MeResponse(long id, String email, String displayName, String role,
                             boolean mustChangePassword, boolean otpEmailEnabled,
                             boolean notifyDeployments, boolean notifyAlerts,
                             List<ProjectRef> projects) {
    }

    public record ProjectRef(long id, String name, String slug) {
    }

    public record CreateUserRequest(String email, String displayName, String password, String role) {
    }

    private final AuthService auth;
    private final UserService users;
    private final Authorization authorization;
    private final ProjectService projects;
    private final io.github.kapillohani023.mypaas.config.MypaasProperties properties;

    public AuthApiController(AuthService auth, UserService users, Authorization authorization,
                             ProjectService projects,
                             io.github.kapillohani023.mypaas.config.MypaasProperties properties) {
        this.auth = auth;
        this.users = users;
        this.authorization = authorization;
        this.projects = projects;
        this.properties = properties;
    }

    @PostMapping("/login")
    public AuthService.TokenPair login(@RequestBody LoginRequest request, HttpServletRequest servlet) {
        return auth.login(request.email(), request.password(), clientIp(servlet));
    }

    @PostMapping("/refresh")
    public AuthService.TokenPair refresh(@RequestBody RefreshRequest request, HttpServletRequest servlet) {
        return auth.refresh(request.refreshToken(), clientIp(servlet));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest request) {
        if (request != null) {
            auth.logout(request.refreshToken());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        var actor = ActorContext.requireActor();
        User user = users.require(actor.id());
        List<ProjectRef> visible = projects.projectsForUser(user.id()).stream()
                .map(p -> new ProjectRef(p.id(), p.name(), p.slug())).toList();
        return new MeResponse(user.id(), user.email(), user.displayName(), user.role().name(),
                user.mustChangePassword(), user.otpEmailEnabled(), user.notifyDeployments(),
                user.notifyAlerts(), visible);
    }

    @PostMapping("/step-up/begin")
    public Authorization.StepUpChallenge beginStepUp(@RequestBody StepUpBeginRequest request) {
        Action action = parseAction(request.action());
        ResourceType type = parseType(request.resourceType());
        ResourceRef resource = new ResourceRef(type, request.resourceId());
        return authorization.beginStepUp(action, resource);
    }

    @PostMapping("/step-up/complete")
    public Authorization.StepUpGrant completeStepUp(@RequestBody StepUpCompleteRequest request) {
        return authorization.completeStepUp(request.challengeId(), request.code());
    }

    @PostMapping("/password/change")
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        authorization.require(Action.SETTINGS_UPDATE, ResourceRef.global());
        var actor = ActorContext.requireActor();
        users.changePassword(actor.id(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<Void> requestReset(@RequestBody ResetRequest request) {
        auth.requestPasswordReset(request.email(), properties.getPublicBaseUrl());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmReset(@RequestBody ResetConfirmRequest request) {
        auth.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/email/verify")
    public ResponseEntity<Void> verifyEmail(@RequestBody ResetConfirmRequest request) {
        auth.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users")
    public ResponseEntity<MeResponse> createUser(@RequestBody CreateUserRequest request) {
        authorization.require(Action.USER_MANAGE, ResourceRef.global());
        Role role;
        try {
            role = Role.valueOf(request.role() == null ? "VIEWER" : request.role().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Role must be ADMIN, DEVELOPER or VIEWER");
        }
        User user = users.create(request.email(), request.displayName(), request.password(), role);
        return ResponseEntity.status(HttpStatus.CREATED).body(new MeResponse(user.id(), user.email(),
                user.displayName(), user.role().name(), user.mustChangePassword(), user.otpEmailEnabled(),
                user.notifyDeployments(), user.notifyAlerts(), List.of()));
    }

    @GetMapping("/users")
    public List<MeResponse> listUsers() {
        authorization.require(Action.USER_READ, ResourceRef.global());
        return users.list().stream().map(u -> new MeResponse(u.id(), u.email(), u.displayName(),
                u.role().name(), u.mustChangePassword(), u.otpEmailEnabled(), u.notifyDeployments(),
                u.notifyAlerts(), List.of())).toList();
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    static Action parseAction(String value) {
        try {
            return Action.valueOf(value.toUpperCase());
        } catch (RuntimeException e) {
            throw new ValidationException("Unknown action");
        }
    }

    static ResourceType parseType(String value) {
        try {
            return ResourceType.valueOf(value.toUpperCase());
        } catch (RuntimeException e) {
            throw new ValidationException("Unknown resource type");
        }
    }
}
