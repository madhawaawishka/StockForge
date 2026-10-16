package com.stockforge.gateway.adapter.in.web;

import com.stockforge.gateway.domain.port.RateLimiter;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-user rate limits on the write endpoints (spec §23 C), applied after authentication so the bucket is keyed by the
 * token's subject rather than a spoofable address. Rejections are 429 + Retry-After ("you are sending too much"),
 * distinct from 503 + Retry-After ("the system is saturated", spec §23 D).
 */
final class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final JsonMapper json;
    private final Tracer tracer;

    RateLimitingFilter(RateLimiter rateLimiter, JsonMapper json, Tracer tracer) {
        this.rateLimiter = rateLimiter;
        this.json = json;
        this.tracer = tracer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return policyOf(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            chain.doFilter(request, response); // the security chain answers 401
            return;
        }
        RateLimiter.Decision decision = rateLimiter.tryAcquire(policyOf(request), authentication.getName());
        if (decision.allowed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
            chain.doFilter(request, response);
            return;
        }
        ProblemDetail problem = Problems.problem(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate-limit-exceeded",
                "Rate limit exceeded",
                "Too many requests; retry after " + decision.retryAfterSeconds() + " seconds",
                request.getRequestURI(),
                tracer);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(problem));
    }

    /** Only the expensive, side-effecting endpoints are limited; reads are protected by caching. */
    private static String policyOf(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return null;
        }
        String path = request.getRequestURI();
        if (path.equals("/api/v1/reservations")) {
            return "reservations";
        }
        if (path.equals("/api/v1/orders")) {
            return "orders";
        }
        return null;
    }
}
