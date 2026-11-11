package com.aireader.application.port;

import com.aireader.domain.AnalysisResult;
import java.time.Instant;
import java.util.Optional;

public interface AnalysisCache {
    Optional<AnalysisResult> find(String key, Instant now);
    void save(String key, AnalysisResult result, Instant createdAt, Instant expiresAt);
    void deleteExpired(Instant now);
}
