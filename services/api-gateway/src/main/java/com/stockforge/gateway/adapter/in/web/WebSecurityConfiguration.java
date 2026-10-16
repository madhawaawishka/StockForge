package com.stockforge.gateway.adapter.in.web;

import com.stockforge.gateway.config.GatewayProperties;
import com.stockforge.gateway.domain.port.RateLimiter;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authentication and authorization at the edge (spec §28).
 *
 * <ul>
 *   <li>Stateless JWT bearer tokens, validated for signature, expiry, issuer and audience; the subject must be a user
 *       id (UUID). Roles come from the {@code roles} claim.
 *   <li>Catalog reads are public; everything else needs a token; {@code /api/v1/admin/**} needs the ADMIN role.
 *   <li>401 and 403 are RFC 9457 problems like every other error.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class WebSecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http, RateLimiter rateLimiter, JsonMapper json, ObjectProvider<Tracer> tracer)
            throws Exception {
        Tracer activeTracer = tracer.getIfAvailable();
        AuthenticationEntryPoint unauthorized = (request, response, failure) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            writeProblem(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "unauthorized",
                    "A valid bearer token is required",
                    request.getRequestURI(),
                    json,
                    activeTracer);
        };
        AccessDeniedHandler forbidden = (request, response, denied) -> writeProblem(
                response,
                HttpStatus.FORBIDDEN,
                "forbidden",
                "You are not allowed to perform this operation",
                request.getRequestURI(),
                json,
                activeTracer);
        http.csrf(csrf -> csrf.disable()) // stateless bearer tokens: no cookies, no CSRF exposure
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth -> auth.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                                .permitAll()
                                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                                .permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/**")
                                .permitAll()
                                .requestMatchers("/api/v1/admin/**")
                                .hasRole("ADMIN")
                                .requestMatchers("/api/v1/**")
                                .authenticated()
                                .anyRequest()
                                .denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(new RolesConverter()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .addFilterAfter(
                        new RateLimitingFilter(rateLimiter, json, activeTracer), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(GatewayProperties properties) {
        GatewayProperties.Jwt config = properties.jwt();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(
                        new SecretKeySpec(config.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(config.issuer()),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(config.audience())),
                WebSecurityConfiguration::subjectIsUserId);
        decoder.setJwtValidator(validators);
        return decoder;
    }

    /** The subject is the user id every downstream service scopes data by; anything else is rejected outright. */
    private static OAuth2TokenValidatorResult subjectIsUserId(Jwt token) {
        try {
            UUID.fromString(token.getSubject());
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException | NullPointerException notAUserId) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "The token subject must be a user id", null));
        }
    }

    private static void writeProblem(
            jakarta.servlet.http.HttpServletResponse response,
            HttpStatus status,
            String type,
            String detail,
            String instance,
            JsonMapper json,
            Tracer tracer)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter()
                .write(json.writeValueAsString(
                        Problems.problem(status, type, Problems.titleOf(type), detail, instance, tracer)));
    }

    /** Maps the {@code roles} claim (for example ["ADMIN"]) to Spring authorities (ROLE_ADMIN). */
    static final class RolesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            List<String> roles = jwt.getClaimAsStringList("roles");
            Collection<GrantedAuthority> authorities = roles == null
                    ? List.of()
                    : roles.stream()
                            .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .toList();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        }
    }
}
