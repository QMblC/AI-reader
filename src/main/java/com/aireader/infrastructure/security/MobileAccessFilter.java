package com.aireader.infrastructure.security;

import com.aireader.infrastructure.web.ApiRequestLoggingFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

final class MobileAccessFilter extends OncePerRequestFilter {
    static final String APP_CHECK_HEADER = "X-Firebase-AppCheck";
    static final RequestMatcher GUEST_SESSION_ENDPOINTS = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/guest-sessions"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/guest-sessions/refresh"));
    private static final RequestMatcher MOBILE_API = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**");

    private final FirebaseAppCheck appCheck;
    private final GuestSessions sessions;
    private final ObjectMapper mapper;

    MobileAccessFilter(FirebaseAppCheck appCheck, GuestSessions sessions, ObjectMapper mapper) {
        this.appCheck = appCheck;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MOBILE_API.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String token = request.getHeader(APP_CHECK_HEADER);
        if (token == null || token.isBlank() || token.length() > 4096) {
            problem(request, response, HttpStatus.UNAUTHORIZED, "APP_CHECK_REQUIRED", "A valid App Check token is required");
            return;
        }
        String appId;
        try {
            appId = appCheck.verify(token);
        } catch (BadJwtException ex) {
            problem(request, response, HttpStatus.UNAUTHORIZED, "APP_CHECK_INVALID", "The App Check token is invalid or expired");
            return;
        } catch (JwtException ex) {
            problem(request, response, HttpStatus.SERVICE_UNAVAILABLE, "APP_CHECK_UNAVAILABLE", "App Check verification is temporarily unavailable");
            return;
        }

        if (GUEST_SESSION_ENDPOINTS.matches(request)) {
            request.setAttribute("verifiedAppId", appId);
        } else {
            var scope = sessions.authenticate(appId, request.getHeader(HttpHeaders.AUTHORIZATION));
            if (scope.isEmpty()) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                problem(request, response, HttpStatus.UNAUTHORIZED, "GUEST_ACCESS_INVALID", "A valid guest access token is required");
                return;
            }
            var principal = UsernamePasswordAuthenticationToken.authenticated(scope.get(), null, List.of());
            var securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(principal);
            SecurityContextHolder.setContext(securityContext);
        }
        chain.doFilter(request, response);
    }

    private void problem(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        ApiRequestLoggingFilter.recordFailure(request, code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("type", "about:blank", "title", status.getReasonPhrase(),
                "status", status.value(), "detail", detail, "code", code));
    }
}
