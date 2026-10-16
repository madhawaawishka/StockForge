package com.stockforge.gateway.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration of the API gateway ({@code gateway.*}). The JWT secret has no default: the gateway refuses to
 * start without one (spec §28: no secrets in the repository; spec §33: fail fast).
 *
 * @param deadline gRPC deadline of each downstream call, the request's time budget at the edge (spec §23 A)
 * @param paymentSimulationEnabled whether clients may choose the simulated payment outcome; test environments only
 * @param jwt token validation settings
 * @param rateLimit per-client limits (spec §23 C)
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @DefaultValue("2s") Duration deadline,
        @DefaultValue("false") boolean paymentSimulationEnabled,
        Jwt jwt,
        @DefaultValue RateLimit rateLimit) {

    public GatewayProperties {
        if (jwt == null) {
            throw new IllegalArgumentException("gateway.jwt.* must be configured (GATEWAY_JWT_SECRET is required)");
        }
    }

    /**
     * HMAC-signed JWTs from a trusted issuer (spec §28; a real identity provider such as Keycloak would supply an RSA
     * key set instead).
     *
     * @param secret HMAC-SHA256 key, at least 32 bytes
     * @param issuer required {@code iss} claim
     * @param audience required value in the {@code aud} claim
     */
    public record Jwt(
            String secret,
            @DefaultValue("stockforge-auth") String issuer,
            @DefaultValue("stockforge-api") String audience) {

        public Jwt {
            if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("gateway.jwt.secret must be at least 32 bytes");
            }
        }
    }

    /**
     * Token buckets per route class, keyed by authenticated user.
     *
     * @param failOpen when Redis is unreachable: {@code true} lets requests through (availability over protection,
     *     ADR-010), {@code false} rejects them
     */
    public record RateLimit(
            @DefaultValue("true") boolean failOpen,
            @DefaultValue Map<String, Bucket> policies) {

        public RateLimit {
            policies = Map.copyOf(policies);
        }
    }

    /**
     * One token bucket.
     *
     * @param capacity burst size
     * @param refillPerSecond sustained rate
     */
    public record Bucket(int capacity, double refillPerSecond) {

        public Bucket {
            if (capacity < 1 || refillPerSecond <= 0) {
                throw new IllegalArgumentException("rate limit capacity and refill rate must be positive");
            }
        }
    }
}
