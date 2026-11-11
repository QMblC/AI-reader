package com.aireader.application.port;

import com.aireader.domain.Illustration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IllustrationRepository {
    Optional<Illustration> findByKey(String key, Instant now);
    Optional<Illustration> find(UUID id, String scope, Instant now);
    boolean claim(Illustration job);
    void generating(UUID id);
    void ready(UUID id, String contentType);
    void fail(UUID id, String code, Instant expiresAt);
    List<Illustration> expired(Instant now);
    void delete(UUID id);
    void failInterrupted(Instant expiresAt);
    boolean contains(UUID id);
}
