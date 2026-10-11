package com.stockforge.gateway.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stockforge.gateway.domain.model.RateLimitResult;
import com.stockforge.gateway.domain.port.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitingFilterTest {

    private RateLimiter rateLimiter;
    private RateLimitingFilter filter;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        filter = new RateLimitingFilter(rateLimiter);
    }

    @Test
    void allowsRequestWithinLimit() throws ServletException, IOException {
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(RateLimitResult.allowed(99));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservations");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("99");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void rejectsExcessTrafficWith429AndRetryAfter() throws ServletException, IOException {
        // Spec §23 C: Excess traffic -> 429 Too Many Requests with Retry-After header
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(RateLimitResult.rejected(5));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservations");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("rate-limit-exceeded");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void skipsNonMutatingRequests() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt());
        verify(filterChain).doFilter(request, response);
    }
}
