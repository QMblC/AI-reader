package com.aireader.application.service;

import com.aireader.application.port.*;
import com.aireader.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerateIllustrationServiceTest {
    private final IllustrationRepository repository = mock(IllustrationRepository.class);
    private final GenerationQuota quota = mock(GenerationQuota.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final IllustrationGenerator generator = mock(IllustrationGenerator.class);
    private final Executor executor = mock(Executor.class);
    private final Instant now = Instant.parse("2026-11-11T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final TextContext context = new TextContext("Book", "Author", "", "A castle on a hill.", "A forest nearby.");
    private final IllustrationOptions options = new IllustrationOptions(null, null);
    private final GenerateIllustrationService service = new GenerateIllustrationService(
            repository, quota, storage, generator, executor, clock, Duration.ofHours(48));

    @Test void returnsCacheWithoutSchedulingOrCallingProvider() {
        when(generator.fingerprint()).thenReturn("model-v1");
        var job = new Illustration(UUID.randomUUID(), "key", "scope", Illustration.Status.READY,
                "image/png", null, now, now.plusSeconds(100));
        when(repository.findByKey(anyString(), eq(now))).thenReturn(Optional.of(job));
        assertThat(service.generate(context, options, "scope")).isEqualTo(job);
        verifyNoInteractions(executor, storage, quota);
        verify(generator, never()).generate(any(), any());
    }

    @Test void savesOnlyCompletedImageAndUsesFixedExpiry() throws Exception {
        when(generator.fingerprint()).thenReturn("model-v1");
        when(quota.claimIllustration(any())).thenReturn(true);
        when(generator.generate(context, options)).thenReturn(new GeneratedImage(new byte[]{1,2,3}, "image/png"));
        var job = service.generate(context, options, "scope");
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        verifyNoInteractions(storage);
        task.getValue().run();
        verify(storage).save(eq(job.id()), aryEq(new byte[]{1,2,3}));
        verify(repository).ready(job.id(), "image/png");
        assertThat(job.expiresAt()).isEqualTo(now.plus(Duration.ofHours(48)));
    }

    @Test void failedProviderIsNotPublishedAndFailureExpiresInFiveMinutes() {
        when(generator.fingerprint()).thenReturn("v1");
        when(quota.claimIllustration(any())).thenReturn(true);
        when(generator.generate(any(), any())).thenThrow(new IllustrationException("IMAGE_PROVIDER_ACCESS_DENIED", 503));
        var job = service.generate(context, options, "scope");
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
        verify(repository).fail(job.id(), "IMAGE_PROVIDER_ACCESS_DENIED", now.plusSeconds(300));
        verify(repository, never()).ready(any(), any());
    }

    @Test void fullQueueRejectsWithoutCallingPaidProvider() {
        when(generator.fingerprint()).thenReturn("v1");
        when(quota.claimIllustration(any())).thenReturn(true);
        doThrow(new RejectedExecutionException()).when(executor).execute(any());
        assertThatThrownBy(() -> service.generate(context, options, "scope"))
                .isInstanceOfSatisfying(IllustrationException.class, ex -> assertThat(ex.status()).isEqualTo(429));
        verify(generator, never()).generate(any(), any());
    }

    @Test void inaccessibleOrExpiredImagesReturn404BeforeStorageRead() {
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> service.image(id, "other-session"))
                .isInstanceOfSatisfying(IllustrationException.class, ex -> assertThat(ex.status()).isEqualTo(404));
        verifyNoInteractions(storage);
    }

    @Test void keysSeparateSessionsOptionsAndModelsAndHaveUnambiguousBoundaries() {
        assertThat(CacheKeys.forContext("a", context, "model", "BOOK"))
                .isNotEqualTo(CacheKeys.forContext("b", context, "model", "BOOK"))
                .isNotEqualTo(CacheKeys.forContext("a", context, "other-model", "BOOK"))
                .isNotEqualTo(CacheKeys.forContext("a", context, "model", "COMIC"));
        assertThat(CacheKeys.hash("ab", "c")).isNotEqualTo(CacheKeys.hash("a", "bc"));
        assertThat(CacheKeys.hash("a\r\nb")).isEqualTo(CacheKeys.hash("a\nb"));
    }
    private static byte[] aryEq(byte[] bytes) { return org.mockito.AdditionalMatchers.aryEq(bytes); }
}
