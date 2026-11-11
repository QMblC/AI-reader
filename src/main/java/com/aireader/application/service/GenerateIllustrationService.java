package com.aireader.application.service;

import com.aireader.application.GenerateIllustrationUseCase;
import com.aireader.application.port.*;
import com.aireader.domain.*;
import java.io.IOException;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class GenerateIllustrationService implements GenerateIllustrationUseCase {
    private final IllustrationRepository repository;
    private final ImageStorage storage;
    private final IllustrationGenerator generator;
    private final Executor executor;
    private final Clock clock;
    private final Duration ttl;

    public GenerateIllustrationService(IllustrationRepository repository, ImageStorage storage,
            IllustrationGenerator generator, Executor executor, Clock clock, Duration ttl) {
        this.repository = repository;
        this.storage = storage;
        this.generator = generator;
        this.executor = executor;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public synchronized Illustration generate(TextContext context, IllustrationOptions options, String scope) {
        String key = CacheKeys.forContext(scope, context, "illustration", generator.fingerprint(),
                options.style().name(), options.size().name());
        var cached = repository.findByKey(key, clock.instant());
        if (cached.isPresent()) return cached.get();
        var now = clock.instant();
        var job = new Illustration(UUID.randomUUID(), key, scope, Illustration.Status.QUEUED,
                null, null, now, now.plus(ttl));
        if (!repository.claim(job)) {
            return repository.findByKey(key, clock.instant())
                    .orElseThrow(() -> new IllustrationException("ILLUSTRATION_BUSY", 429));
        }
        try {
            executor.execute(() -> run(job, context, options));
        } catch (RejectedExecutionException ex) {
            repository.fail(job.id(), "ILLUSTRATION_BUSY", now.plusSeconds(300));
            throw new IllustrationException("ILLUSTRATION_BUSY", 429);
        }
        return job;
    }

    private void run(Illustration job, TextContext context, IllustrationOptions options) {
        try {
            repository.generating(job.id());
            var image = generator.generate(context, options);
            if (!job.expiresAt().isAfter(clock.instant())) {
                repository.fail(job.id(), "ILLUSTRATION_EXPIRED", clock.instant());
                return;
            }
            storage.save(job.id(), image.bytes());
            repository.ready(job.id(), image.contentType());
        } catch (Exception ex) {
            try { storage.delete(job.id()); } catch (IOException ignored) { /* Cleanup retries orphan files. */ }
            String code = ex instanceof IllustrationException failure ? failure.code() : "ILLUSTRATION_FAILED";
            repository.fail(job.id(), code, clock.instant().plusSeconds(300));
        }
    }

    @Override
    public Illustration get(UUID id, String scope) {
        return repository.find(id, scope, clock.instant())
                .orElseThrow(() -> new IllustrationException("ILLUSTRATION_NOT_FOUND", 404));
    }

    @Override
    public GeneratedImage image(UUID id, String scope) {
        var job = get(id, scope);
        if (job.status() != Illustration.Status.READY) {
            throw new IllustrationException("ILLUSTRATION_NOT_READY", 409);
        }
        try {
            return new GeneratedImage(storage.get(id), job.contentType());
        } catch (java.nio.file.NoSuchFileException ex) {
            repository.fail(id, "ILLUSTRATION_FILE_MISSING", clock.instant());
            throw new IllustrationException("ILLUSTRATION_NOT_FOUND", 404);
        } catch (IOException ex) {
            throw new IllustrationException("IMAGE_STORAGE_UNAVAILABLE", 503);
        }
    }
}
