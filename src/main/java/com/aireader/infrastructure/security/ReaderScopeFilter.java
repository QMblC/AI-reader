package com.aireader.infrastructure.security;

import com.aireader.application.service.CacheKeys;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/** Anonymous bearer session until account authentication is added. Never derive scope from IP. */
@Component
@Order(1)
public class ReaderScopeFilter extends OncePerRequestFilter {
    private static final String HEADER = "X-Reader-Session";
    private static final String COOKIE = "AI_READER_SESSION";
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/v1/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        String token = request.getHeader(HEADER);
        if (token == null && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) if (COOKIE.equals(cookie.getName())) token = cookie.getValue();
        }
        if (token == null) token = UUID.randomUUID().toString();
        if (!token.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            response.sendError(400);
            return;
        }
        request.setAttribute("readerScope", CacheKeys.hash(token));
        response.setHeader(HEADER, token);
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE, token).httpOnly(true)
                .secure(request.isSecure()).sameSite("Strict").path("/api/v1").maxAge(Duration.ofHours(48)).build().toString());
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
