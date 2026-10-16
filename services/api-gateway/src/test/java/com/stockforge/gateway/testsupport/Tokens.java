package com.stockforge.gateway.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Issues JWTs like the identity provider would, plus deliberately broken ones. */
public final class Tokens {

    /** Must match gateway.jwt.secret in src/test/resources/config/application.yml. */
    public static final String SECRET = "test-only-hmac-key-of-at-least-32-bytes!";

    private Tokens() {}

    public static String user(String subject) {
        return token(SECRET, subject, "stockforge-auth", "stockforge-api", List.of("USER"), Duration.ofMinutes(5));
    }

    public static String admin(String subject) {
        return token(
                SECRET, subject, "stockforge-auth", "stockforge-api", List.of("USER", "ADMIN"), Duration.ofMinutes(5));
    }

    public static String token(
            String secret, String subject, String issuer, String audience, List<String> roles, Duration validFor) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .audience(audience)
                    .issueTime(Date.from(now.minusSeconds(5)))
                    .expirationTime(Date.from(now.plus(validFor)))
                    .claim("roles", roles)
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
