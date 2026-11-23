package com.aireader.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiRequestLoggingFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String REQUEST_ID = ApiRequestLoggingFilter.class.getName() + ".requestId";
    private static final String FAILURE = ApiRequestLoggingFilter.class.getName() + ".failure";
    private static final Logger LOG = LoggerFactory.getLogger(ApiRequestLoggingFilter.class);
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long startedAt = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            failed = true;
            throw ex;
        } finally {
            int status = failed && response.getStatus() < 400 ? 500 : response.getStatus();
            log(request, requestId, status, (System.nanoTime() - startedAt) / 1_000_000);
        }
    }

    public static void recordFailure(HttpServletRequest request, String code) {
        request.setAttribute(FAILURE, new Failure(code, null, null, null));
    }

    public static ProblemDetail recordProblem(WebRequest request, ProblemDetail problem) {
        Object requestId = request.getAttribute(REQUEST_ID, WebRequest.SCOPE_REQUEST);
        if (requestId != null) {
            var fields = problem.getProperties();
            if (fields != null) {
                request.setAttribute(FAILURE, new Failure(fields.get("code"), fields.get("reason"), fields.get("line"),
                        fields.get("column")), WebRequest.SCOPE_REQUEST);
            }
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }

    private static void log(HttpServletRequest request, String id, int status, long durationMs) {
        var event = status >= 500 ? LOG.atWarn() : (status == 400 || status == 429 ? LOG.atInfo() : LOG.atDebug());
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
        if (request.getAttribute(FAILURE) instanceof Failure failure) {
            event.log("API request completed requestId={} method={} route={} status={} durationMs={} code={} reason={} line={} column={}",
                    id, method, route, status, durationMs, failure.code(), failure.reason(), failure.line(), failure.column());
        } else {
            event.log("API request completed requestId={} method={} route={} status={} durationMs={}",
                    id, method, route, status, durationMs);
        }
    }

    private record Failure(Object code, Object reason, Object line, Object column) { }
}
