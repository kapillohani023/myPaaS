package io.github.kapillohani023.mypaas.security;

public record Actor(long id, String email, String displayName, Role role) {

    public static Actor system(String name) {
        return new Actor(0L, name + "@system", name, Role.ADMIN);
    }

    public boolean isSystem() {
        return id == 0L;
    }
}
