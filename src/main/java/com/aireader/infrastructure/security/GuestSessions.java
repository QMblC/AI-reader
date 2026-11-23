package com.aireader.infrastructure.security;

import com.aireader.application.service.CacheKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class GuestSessions {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final MobileAccessProperties properties;
    private final RequestQuotas quotas;

    GuestSessions(JdbcTemplate jdbc, Clock clock, MobileAccessProperties properties, RequestQuotas quotas) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.quotas = quotas;
    }

    @Transactional
    public IssuedSession create(String appId) {
        var now = clock.instant();
        quotas.reserve("guest-create", RequestQuotas.GLOBAL, now, ChronoUnit.MINUTES,
                properties.sessionCreationsPerMinute(), "GUEST_SESSION_RATE_LIMIT");
        UUID id = UUID.randomUUID();
        Instant sessionExpiresAt = now.plus(properties.sessionTtl());
        String refreshToken = generateToken();
        jdbc.update("INSERT INTO guest_sessions (id, app_id, refresh_token_hash, expires_at) VALUES (?, ?, ?, ?)",
                id, appId, CacheKeys.hash(refreshToken), Timestamp.from(sessionExpiresAt));
        AccessGrant access = issueAccess(id, now, sessionExpiresAt);
        return new IssuedSession(access.accessToken(), access.accessExpiresAt(), refreshToken, sessionExpiresAt);
    }

    @Transactional
    public Optional<AccessGrant> refresh(String appId, String authorization) {
        String token = bearer(authorization);
        if (token == null) {
            return Optional.empty();
        }
        var now = clock.instant();
        var session = jdbc.query("""
                SELECT id, expires_at FROM guest_sessions
                WHERE refresh_token_hash = ? AND app_id = ? AND expires_at > ?
                """, (rs, row) -> new Session(rs.getObject("id", UUID.class), rs.getTimestamp("expires_at").toInstant()),
                CacheKeys.hash(token), appId, Timestamp.from(now)).stream().findFirst();
        return session.map(guest -> {
            quotas.reserve("guest-refresh", guest.id().toString(), now, ChronoUnit.MINUTES,
                    properties.sessionRefreshesPerMinute(), "GUEST_REFRESH_RATE_LIMIT");
            return issueAccess(guest.id(), now, guest.expiresAt());
        });
    }

    Optional<String> authenticate(String appId, String authorization) {
        String token = bearer(authorization);
        if (token == null) {
            return Optional.empty();
        }
        var now = Timestamp.from(clock.instant());
        return jdbc.query("""
                SELECT guest_sessions.id FROM guest_access_tokens
                JOIN guest_sessions ON guest_sessions.id = guest_access_tokens.session_id
                WHERE token_hash = ? AND app_id = ? AND guest_access_tokens.expires_at > ? AND guest_sessions.expires_at > ?
                """, (rs, row) -> CacheKeys.hash("guest", rs.getObject("id", UUID.class).toString()),
                CacheKeys.hash(token), appId, now, now).stream().findFirst();
    }

    @Scheduled(fixedDelayString = "${cache.cleanup-interval}", initialDelayString = "${cache.cleanup-interval}")
    public void cleanExpired() {
        Instant now = clock.instant();
        jdbc.update("DELETE FROM guest_access_tokens WHERE expires_at <= ?", Timestamp.from(now));
        jdbc.update("DELETE FROM guest_sessions WHERE expires_at <= ?", Timestamp.from(now));
        quotas.deleteExpired(now);
    }

    private AccessGrant issueAccess(UUID id, Instant now, Instant sessionExpiresAt) {
        Instant expiresAt = now.plus(properties.accessTtl());
        if (expiresAt.isAfter(sessionExpiresAt)) {
            expiresAt = sessionExpiresAt;
        }
        String token = generateToken();
        jdbc.update("INSERT INTO guest_access_tokens (token_hash, session_id, expires_at) VALUES (?, ?, ?)",
                CacheKeys.hash(token), id, Timestamp.from(expiresAt));
        return new AccessGrant(token, expiresAt);
    }

    private static String bearer(String authorization) {
        String prefix = "Bearer ";
        if (authorization == null || !authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String token = authorization.substring(prefix.length());
        return TOKEN.matcher(token).matches() ? token : null;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record Session(UUID id, Instant expiresAt) { }

    public record AccessGrant(String accessToken, Instant accessExpiresAt) { }

    public record IssuedSession(String accessToken, Instant accessExpiresAt, String refreshToken, Instant sessionExpiresAt) { }
}
