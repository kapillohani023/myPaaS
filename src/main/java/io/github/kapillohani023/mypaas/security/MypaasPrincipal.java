package io.github.kapillohani023.mypaas.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public final class MypaasPrincipal implements UserDetails {

    private final Actor actor;
    private final String passwordHash;
    private final boolean enabled;
    private final boolean mustChangePassword;

    public MypaasPrincipal(Actor actor, String passwordHash) {
        this(actor, passwordHash, true, false);
    }

    public MypaasPrincipal(Actor actor, String passwordHash, boolean enabled) {
        this(actor, passwordHash, enabled, false);
    }

    public MypaasPrincipal(Actor actor, String passwordHash, boolean enabled, boolean mustChangePassword) {
        this.actor = actor;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.mustChangePassword = mustChangePassword;
    }

    public Actor actor() {
        return actor;
    }

    public boolean mustChangePassword() {
        return mustChangePassword;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(actor.role().springAuthority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return actor.email();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
