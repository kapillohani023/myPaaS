package io.github.kapillohani023.mypaas.identity;

import io.github.kapillohani023.mypaas.security.Role;

public record User(
        long id,
        String email,
        String displayName,
        String passwordHash,
        Role role,
        boolean emailVerified,
        boolean mustChangePassword,
        boolean otpEmailEnabled,
        boolean notifyDeployments,
        boolean notifyAlerts,
        long createdAt
) {
}
