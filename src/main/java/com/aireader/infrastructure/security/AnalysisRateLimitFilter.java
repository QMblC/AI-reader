package com.aireader.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/** Bounded memory, instance-wide fixed window; health requests are never limited. */
@Component
public class AnalysisRateLimitFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final int limit;
    private long windowStart = System.nanoTime();
    private int used;
    private static final long WINDOW_NANOS = 60_000_000_000L;

    public AnalysisRateLimitFilter(ObjectMapper mapper,
            @Value("${app.rate-limit.requests-per-minute}") int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Rate limit must be positive");
        }
        this.mapper = mapper;
        this.limit = limit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !"/api/v1/analyses".equals(request.getServletPath());
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
                "status", 429, "detail", "Analysis request limit exceeded"));
    }
}
