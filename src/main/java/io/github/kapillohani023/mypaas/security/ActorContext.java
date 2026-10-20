package io.github.kapillohani023.mypaas.security;

import io.github.kapillohani023.mypaas.core.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The ambient security context: actor, trigger and step-up grant.
 *
 * <p>Request adapters populate this from the authenticated principal and the
 * {@code X-Step-Up-Grant} header. Background handlers run with
 * {@link #runAs(Actor, Trigger, Supplier)} so a job can never forge a webhook
 * trigger or a different actor.</p>
 */
public final class ActorContext {

    private static final ThreadLocal<Actor> ACTOR = new ThreadLocal<>();
    private static final ThreadLocal<Trigger> TRIGGER = new ThreadLocal<>();
    private static final ThreadLocal<String> GRANT = new ThreadLocal<>();

    private ActorContext() {
    }

    public static Optional<Actor> actor() {
        Actor override = ACTOR.get();
        if (override != null) {
            return Optional.of(override);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof MypaasPrincipal principal) {
            return Optional.of(principal.actor());
        }
        return Optional.empty();
    }

    public static Actor requireActor() {
        return actor().orElseThrow(() -> new ForbiddenException("Authentication required"));
    }

    public static Trigger trigger() {
        return TRIGGER.get() == null ? Trigger.API : TRIGGER.get();
    }

    public static Optional<String> stepUpGrant() {
        return Optional.ofNullable(GRANT.get());
    }

    public static void setGrant(String grant) {
        if (grant == null) {
            GRANT.remove();
        } else {
            GRANT.set(grant);
        }
    }

    public static <T> T runAs(Actor actor, Trigger trigger, Supplier<T> work) {
        Actor previousActor = ACTOR.get();
        Trigger previousTrigger = TRIGGER.get();
        String previousGrant = GRANT.get();
        ACTOR.set(actor);
        TRIGGER.set(trigger);
        GRANT.remove();
        try {
            return work.get();
        } finally {
            restore(previousActor, previousTrigger, previousGrant);
        }
    }

    public static void runAs(Actor actor, Trigger trigger, Runnable work) {
        runAs(actor, trigger, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T withGrant(String grant, Supplier<T> work) {
        String previous = GRANT.get();
        GRANT.set(grant);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                GRANT.remove();
            } else {
                GRANT.set(previous);
            }
        }
    }

    public static void withGrant(String grant, Runnable work) {
        withGrant(grant, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T withTrigger(Trigger trigger, Supplier<T> work) {
        Trigger previous = TRIGGER.get();
        TRIGGER.set(trigger);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                TRIGGER.remove();
            } else {
                TRIGGER.set(previous);
            }
        }
    }

    private static void restore(Actor actor, Trigger trigger, String grant) {
        if (actor == null) {
            ACTOR.remove();
        } else {
            ACTOR.set(actor);
        }
        if (trigger == null) {
            TRIGGER.remove();
        } else {
            TRIGGER.set(trigger);
        }
        if (grant == null) {
            GRANT.remove();
        } else {
            GRANT.set(grant);
        }
    }
}
