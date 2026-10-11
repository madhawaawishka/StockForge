package com.stockforge.gateway.domain.model;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record UserIdentity(UUID userId, String username, Set<String> roles) {

    public UserIdentity {
        Objects.requireNonNull(userId, "userId must not be null");
        roles = roles == null ? Collections.emptySet() : Set.copyOf(roles);
    }

    public static UserIdentity of(UUID userId) {
        return new UserIdentity(userId, userId.toString(), Set.of("ROLE_USER"));
    }

    public static UserIdentity of(UUID userId, String username, Set<String> roles) {
        return new UserIdentity(userId, username, roles);
    }

    public boolean isAdmin() {
        return roles.contains("ROLE_ADMIN") || roles.contains("ADMIN");
    }
}
