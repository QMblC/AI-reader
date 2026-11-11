package com.aireader.infrastructure.storage;

import com.aireader.application.port.AnalysisCache;
import com.aireader.domain.AnalysisResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class PostgresAnalysisCache implements AnalysisCache {
    private final JdbcTemplate jdbc;
    public PostgresAnalysisCache(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<AnalysisResult> find(String key, Instant now) {
        return jdbc.query("SELECT translation, explanation FROM analysis_cache WHERE cache_key = ? AND expires_at > ?",
                (rs, row) -> new AnalysisResult(rs.getString(1), rs.getString(2)), key, Timestamp.from(now))
                .stream().findFirst();
    }

    public void save(String key, AnalysisResult result, Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO analysis_cache VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (cache_key) DO UPDATE SET translation = EXCLUDED.translation,
                explanation = EXCLUDED.explanation, created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at
                """, key, result.translation(), result.explanation(), Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public void deleteExpired(Instant now) {
        jdbc.update("DELETE FROM analysis_cache WHERE expires_at <= ?", Timestamp.from(now));
    }
}
