package com.stockforge.gateway.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * The authenticated user behind a request. The id always comes from the validated token, never from the request body or
 * a header the client controls (spec §28).
 *
 * @param admin whether the token carries the ADMIN role
 */
public record Caller(UUID userId, boolean admin) {

    public Caller {
        Objects.requireNonNull(userId, "userId must not be null");
    }

    /**
     * Whose data the downstream service may return: the caller's own, or anyone's for an administrator. An empty
     * requester tells the service the call is privileged (see the proto contracts).
     */
    public String requesterScope() {
        return admin ? "" : userId.toString();
    }
}
