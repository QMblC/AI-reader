package com.aireader.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/** Bounded memory, instance-wide fixed window; health requests are never limited. */
final class AnalysisRateLimitFilter extends OncePerRequestFilter {
    private static final RequestMatcher AI_WRITES = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/analyses"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/illustrations"));
    private static final long WINDOW_NANOS = 60_000_000_000L;
    private final ObjectMapper mapper;
    private final int limit;
    private long windowStart = System.nanoTime();
    private int used;
    AnalysisRateLimitFilter(ObjectMapper mapper, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Rate limit must be positive");
        }
        this.mapper = mapper;
        this.limit = limit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AI_WRITES.matches(request);
    }

    private synchronized long retryAfterSeconds() {
        long now = System.nanoTime();
        long elapsed = now - windowStart;
        if (elapsed >= WINDOW_NANOS) {
            windowStart = now;
            elapsed = 0;
            used = 0;
        }
        if (used < limit) {
            used++;
            return 0;
        }
        return Math.max(1, (WINDOW_NANOS - elapsed + 999_999_999L) / 1_000_000_000L);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        long retryAfter = retryAfterSeconds();
        if (retryAfter == 0) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("type", "about:blank", "title", "Too Many Requests",
                "status", 429, "detail", "Generation request limit exceeded", "code", "REQUEST_RATE_LIMIT"));
    }
}
