package io.github.kapillohani023.mypaas.authorization;

import io.github.kapillohani023.mypaas.Fixtures;
import io.github.kapillohani023.mypaas.IntegrationTestBase;
import io.github.kapillohani023.mypaas.core.ForbiddenException;
import io.github.kapillohani023.mypaas.core.StepUpRequiredException;
import io.github.kapillohani023.mypaas.notification.NotificationService;
import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.Actor;
import io.github.kapillohani023.mypaas.security.ActorContext;
import io.github.kapillohani023.mypaas.security.ResourceRef;
import io.github.kapillohani023.mypaas.security.ResourceType;
import io.github.kapillohani023.mypaas.security.Role;
import io.github.kapillohani023.mypaas.security.Trigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationTest extends IntegrationTestBase {

    @Autowired
    Authorization authorization;

    @Autowired
    NotificationService notifications;

    @Autowired
    PasswordEncoder passwordEncoder;

    private long admin;
    private long developer;
    private long viewer;
    private long outsider;
    private long projectId;
    private long fileId;
    private long secondFileId;

    @BeforeEach
    void setUp() {
        admin = Fixtures.user(jdbc, passwordEncoder, "admin@example.com", "ADMIN");
        developer = Fixtures.user(jdbc, passwordEncoder, "dev@example.com", "DEVELOPER");
        viewer = Fixtures.user(jdbc, passwordEncoder, "viewer@example.com", "VIEWER");
        outsider = Fixtures.user(jdbc, passwordEncoder, "outsider@example.com", "DEVELOPER");
        long serverId = Fixtures.server(jdbc, "test-server");
        projectId = Fixtures.project(jdbc, "auth-project", serverId);
        Fixtures.member(jdbc, projectId, admin);
        Fixtures.member(jdbc, projectId, developer);
        Fixtures.member(jdbc, projectId, viewer);
        fileId = Fixtures.file(jdbc, projectId, "config.env");
        secondFileId = Fixtures.file(jdbc, projectId, "other.env");
    }

    @Test
    void roleAndScopeMatrixHolds() {
        Actor dev = actor(developer, "dev@example.com", Role.DEVELOPER);
        Actor view = actor(viewer, "viewer@example.com", Role.VIEWER);
        Actor out = actor(outsider, "outsider@example.com", Role.DEVELOPER);
        Actor adm = actor(admin, "admin@example.com", Role.ADMIN);

        ActorContext.runAs(dev, Trigger.API, () ->
                assertThatCode(() -> authorization.require(Action.DEPLOY, ResourceRef.project(projectId)))
                        .doesNotThrowAnyException());
        ActorContext.runAs(view, Trigger.API, () -> {
            assertThatCode(() -> authorization.require(Action.PROJECT_READ,
                    ResourceRef.project(projectId))).doesNotThrowAnyException();
            assertThatThrownBy(() -> authorization.require(Action.DEPLOY,
                    ResourceRef.project(projectId))).isInstanceOf(ForbiddenException.class);
        });
        ActorContext.runAs(out, Trigger.API, () ->
                assertThatThrownBy(() -> authorization.require(Action.PROJECT_READ,
                        ResourceRef.project(projectId))).isInstanceOf(ForbiddenException.class));
        ActorContext.runAs(adm, Trigger.API, () -> {
            assertThatCode(() -> authorization.require(Action.DEPLOY,
                    ResourceRef.project(projectId))).doesNotThrowAnyException();
            assertThatCode(() -> authorization.require(Action.AUDIT_READ,
                    ResourceRef.global())).doesNotThrowAnyException();
        });
    }

    @Test
    void visibleScopeIsAppliedAndEmptyForOutsiders() {
        Actor dev = actor(developer, "dev@example.com", Role.DEVELOPER);
        Actor out = actor(outsider, "outsider@example.com", Role.DEVELOPER);

        AccessScope scope = ActorContext.runAs(dev, Trigger.API,
                () -> authorization.visibleScope(ResourceType.PROJECT));
        assertThat(scope.unrestricted()).isFalse();
        assertThat(scope.ids()).containsExactly(projectId);

        AccessScope empty = ActorContext.runAs(out, Trigger.API,
                () -> authorization.visibleScope(ResourceType.PROJECT));
        assertThat(empty.ids()).isEmpty();
    }

    @Test
    void stepUpGrantIsSingleUseAndBoundToResource() {
        Actor dev = actor(developer, "dev@example.com", Role.DEVELOPER);
        Authorization.StepUpGrant grant = issueGrant(dev, Action.FILE_DELETE,
                ResourceRef.file(fileId));

        ActorContext.runAs(dev, Trigger.API, () ->
                assertThatCode(() -> ActorContext.withGrant(grant.value(),
                                () -> authorization.require(Action.FILE_DELETE, ResourceRef.file(fileId))))
                        .doesNotThrowAnyException());

        ActorContext.runAs(dev, Trigger.API, () ->
                assertThatThrownBy(() -> ActorContext.withGrant(grant.value(),
                        () -> authorization.require(Action.FILE_DELETE, ResourceRef.file(fileId))))
                        .isInstanceOf(StepUpRequiredException.class));

        Authorization.StepUpGrant crossResourceGrant = issueGrant(dev, Action.FILE_DELETE,
                ResourceRef.file(fileId));
        ActorContext.runAs(dev, Trigger.API, () ->
                assertThatThrownBy(() -> ActorContext.withGrant(crossResourceGrant.value(),
                        () -> authorization.require(Action.FILE_DELETE, ResourceRef.file(secondFileId))))
                        .isInstanceOf(StepUpRequiredException.class));
    }

    @Test
    void missingAndExpiredChallengesAreRejected() {
        Actor dev = actor(developer, "dev@example.com", Role.DEVELOPER);
        ActorContext.runAs(dev, Trigger.API, () ->
                assertThatThrownBy(() -> authorization.require(Action.FILE_DELETE, ResourceRef.file(fileId)))
                        .isInstanceOf(StepUpRequiredException.class));

        Authorization.StepUpChallenge challenge = ActorContext.runAs(dev, Trigger.API,
                () -> authorization.beginStepUp(Action.FILE_DELETE, ResourceRef.file(fileId)));
        jdbc.update("update step_up_challenges set expires_at = 0 where id = ?", challenge.id());
        ActorContext.runAs(dev, Trigger.API, () ->
                assertThatThrownBy(() -> authorization.completeStepUp(challenge.id(), otp()))
                        .isInstanceOf(ForbiddenException.class));
    }

    @Test
    void fileAccessIsNotGrantedByGuessingAnId() {
        Actor out = actor(outsider, "outsider@example.com", Role.DEVELOPER);
        ActorContext.runAs(out, Trigger.API, () ->
                assertThatThrownBy(() -> authorization.require(Action.FILE_READ, ResourceRef.file(fileId)))
                        .isInstanceOf(ForbiddenException.class));
    }

    private Authorization.StepUpGrant issueGrant(Actor actor, Action action, ResourceRef resource) {
        Authorization.StepUpChallenge challenge = ActorContext.runAs(actor, Trigger.API,
                () -> authorization.beginStepUp(action, resource));
        return ActorContext.runAs(actor, Trigger.API,
                () -> authorization.completeStepUp(challenge.id(), otp()));
    }

    private String otp() {
        return notifications.capturedMessages().stream()
                .filter(message -> message.subject().contains("verification code"))
                .map(message -> {
                    Matcher matcher = Pattern.compile("code is (\\d{6})").matcher(message.body());
                    return matcher.find() ? matcher.group(1) : "";
                })
                .filter(code -> !code.isBlank())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No OTP email was captured"));
    }

    private static Actor actor(long id, String email, Role role) {
        return new Actor(id, email, email.substring(0, email.indexOf('@')), role);
    }
}
