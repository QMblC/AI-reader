package com.aireader.api.dto;

import com.aireader.domain.Illustration;
import java.time.Instant;
import java.util.UUID;

public record IllustrationResponse(UUID id, Illustration.Status status, String imageUrl,
        String errorCode, Instant createdAt, Instant expiresAt) {
    public static IllustrationResponse from(Illustration job) {
        return new IllustrationResponse(job.id(), job.status(), job.status() == Illustration.Status.READY
                ? "/api/v1/illustrations/" + job.id() + "/image" : null,
                job.errorCode(), job.createdAt(), job.expiresAt());
    }
}
