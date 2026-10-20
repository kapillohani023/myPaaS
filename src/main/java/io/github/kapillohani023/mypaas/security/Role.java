package io.github.kapillohani023.mypaas.security;

/** Fixed v1 roles. Roles grant capability; memberships grant scope. */
public enum Role {
    VIEWER(1), DEVELOPER(2), ADMIN(3);

    private final int rank;

    Role(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(Role required) {
        return rank >= required.rank;
    }

    public String springAuthority() {
        return "ROLE_" + name();
    }
}
