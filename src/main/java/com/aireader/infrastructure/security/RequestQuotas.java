package com.aireader.infrastructure.security;

import com.aireader.application.port.RequestQuotaExceededException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
final class RequestQuotas {
    static final String GLOBAL = "*";

    private final JdbcTemplate jdbc;

    RequestQuotas(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void reserve(String operation, String subject, Instant now, ChronoUnit interval, int limit, String code) {
        Instant windowEnd = now.truncatedTo(interval).plus(1, interval);
        int changed = jdbc.update("""
                INSERT INTO api_usage (operation, subject, window_end, used) VALUES (?, ?, ?, 1)
                ON CONFLICT (operation, subject, window_end) DO UPDATE SET used = api_usage.used + 1
                WHERE api_usage.used < ?
                """, operation, subject, Timestamp.from(windowEnd), limit);
        if (changed == 0) {
            long retryAfter = Math.ceilDiv(Duration.between(now, windowEnd).toMillis(), 1000);
            throw new RequestQuotaExceededException(code, Math.max(1, retryAfter));
        }
    }

    void deleteExpired(Instant now) {
        jdbc.update("DELETE FROM api_usage WHERE window_end <= ?", Timestamp.from(now));
    }
}
