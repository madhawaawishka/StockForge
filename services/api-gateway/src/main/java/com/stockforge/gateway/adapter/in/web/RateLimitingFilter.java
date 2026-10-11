package com.stockforge.gateway.adapter.in.web;

import com.stockforge.gateway.domain.model.RateLimitResult;
import com.stockforge.gateway.domain.port.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Gateway filter applying token-bucket rate limiting to mutating operations (spec §23 C). */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;

    public RateLimitingFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        // Rate limit mutating endpoints
        if (!"POST".equalsIgnoreCase(method)) {
            return true;
        }
        return !(uri.startsWith("/api/v1/reservations") || uri.startsWith("/api/v1/orders"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String key = resolveRateLimitKey(request);
        RateLimitResult decision = rateLimiter.tryAcquire(key, 1);

        if (!decision.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            String problemJson = String.format("""
                    {
                      "type": "https://github.com/madhawaawishka/StockForge/blob/main/docs/api/problems.md#rate-limit-exceeded",
                      "title": "Too Many Requests",
                      "status": 429,
                      "detail": "Rate limit exceeded. Please retry after %d seconds.",
                      "instance": "%s"
                    }
                    """, decision.retryAfterSeconds(), request.getRequestURI());
            response.getWriter().write(problemJson);
            return;
        }

        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));
        filterChain.doFilter(request, response);
    }

    private static String resolveRateLimitKey(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal != null
                && principal.getName() != null
                && !principal.getName().isBlank()) {
            return "user:" + principal.getName();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int commaIdx = forwarded.indexOf(',');
            return "ip:" + (commaIdx > 0 ? forwarded.substring(0, commaIdx).trim() : forwarded.trim());
        }
        return "ip:" + request.getRemoteAddr();
    }
}
