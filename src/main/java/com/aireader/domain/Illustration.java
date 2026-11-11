package com.aireader.domain;

import java.time.Instant;
import java.util.UUID;

public record Illustration(UUID id, String cacheKey, String scope, Status status,
        String contentType, String errorCode, Instant createdAt, Instant expiresAt) {
    public enum Status { QUEUED, GENERATING, READY, FAILED }
}
