package io.github.kapillohani023.mypaas.authorization;

import io.github.kapillohani023.mypaas.security.ResourceType;

import java.util.List;
import java.util.Set;

/**
 * Opaque scope applied to list queries. Repositories must apply it in SQL;
 * fetch-all-then-filter is forbidden.
 */
public record AccessScope(ResourceType type, boolean unrestricted, Set<Long> ids) {

    public static AccessScope all(ResourceType type) {
        return new AccessScope(type, true, Set.of());
    }

    public static AccessScope of(ResourceType type, Set<Long> ids) {
        return new AccessScope(type, false, Set.copyOf(ids));
    }

    public static AccessScope none(ResourceType type) {
        return new AccessScope(type, false, Set.of());
    }

    /** Returns a SQL predicate for {@code column} and appends its parameters. */
    public String predicate(String column, List<Object> parameters) {
        if (unrestricted) {
            return "1 = 1";
        }
        if (ids.isEmpty()) {
            return "1 = 0";
        }
        parameters.addAll(ids);
        return column + " in (" + String.join(",", ids.stream().map(id -> "?").toList()) + ")";
    }
}
