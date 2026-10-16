package com.stockforge.gateway.adapter.in.web;

import com.stockforge.gateway.domain.model.Caller;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** The caller, taken only from the validated token (spec §28: never from the body or a client-chosen header). */
final class Callers {

    static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private Callers() {}

    /** The token's subject was validated to be a UUID when the token was decoded (see the security configuration). */
    static Caller of(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
        return new Caller(UUID.fromString(authentication.getName()), admin);
    }
}
