package com.aireader.infrastructure.storage;

import com.aireader.application.port.IllustrationRepository;
import com.aireader.domain.Illustration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class PostgresIllustrationRepository implements IllustrationRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Illustration> MAPPER = (rs, row) -> new Illustration(
            rs.getObject("id", UUID.class), rs.getString("cache_key"), rs.getString("scope"),
            Illustration.Status.valueOf(rs.getString("status")), rs.getString("content_type"),
            rs.getString("error_code"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant());
    public PostgresIllustrationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Illustration> findByKey(String key, Instant now) {
        return jdbc.query("SELECT * FROM illustration_jobs WHERE cache_key = ? AND expires_at > ?",
                MAPPER, key, Timestamp.from(now)).stream().findFirst();
    }
    public Optional<Illustration> find(UUID id, String scope, Instant now) {
        return jdbc.query("SELECT * FROM illustration_jobs WHERE id = ? AND scope = ? AND expires_at > ?",
                MAPPER, id, scope, Timestamp.from(now)).stream().findFirst();
    }
    public boolean claim(Illustration job) {
        return jdbc.update("""
                INSERT INTO illustration_jobs (cache_key, id, scope, status, created_at, expires_at)
                VALUES (?, ?, ?, 'QUEUED', ?, ?)
                ON CONFLICT (cache_key) DO UPDATE SET id = EXCLUDED.id, scope = EXCLUDED.scope,
                status = 'QUEUED', content_type = NULL, error_code = NULL,
                created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at
                WHERE illustration_jobs.expires_at <= EXCLUDED.created_at
                """, job.cacheKey(), job.id(), job.scope(), Timestamp.from(job.createdAt()), Timestamp.from(job.expiresAt())) == 1;
    }
    public void generating(UUID id) { jdbc.update("UPDATE illustration_jobs SET status = 'GENERATING' WHERE id = ?", id); }
    public void ready(UUID id, String contentType) {
        jdbc.update("UPDATE illustration_jobs SET status = 'READY', content_type = ? WHERE id = ?", contentType, id);
    }
    public void fail(UUID id, String code, Instant expiresAt) {
        jdbc.update("UPDATE illustration_jobs SET status = 'FAILED', error_code = ?, expires_at = ? WHERE id = ?",
                code, Timestamp.from(expiresAt), id);
    }
    public List<Illustration> expired(Instant now) {
        return jdbc.query("SELECT * FROM illustration_jobs WHERE expires_at <= ? LIMIT 500", MAPPER, Timestamp.from(now));
    }
    public void delete(UUID id) { jdbc.update("DELETE FROM illustration_jobs WHERE id = ?", id); }
    public boolean contains(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM illustration_jobs WHERE id = ?)", Boolean.class, id));
    }
    public void failInterrupted(Instant expiresAt) {
        jdbc.update("""
                UPDATE illustration_jobs SET status = 'FAILED', error_code = 'GENERATION_INTERRUPTED', expires_at = ?
                WHERE status IN ('QUEUED', 'GENERATING')
                """, Timestamp.from(expiresAt));
    }
}
