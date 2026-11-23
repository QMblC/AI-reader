package com.aireader.infrastructure.storage;

import com.aireader.domain.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Runs only against an explicitly selected disposable/local PostgreSQL database. */
@EnabledIfEnvironmentVariable(named = "AI_READER_POSTGRES_TEST_URL", matches = ".+")
class PostgresCacheIntegrationTest {
    private static JdbcTemplate jdbc;
    @BeforeAll static void setup() {
        String url = System.getenv("AI_READER_POSTGRES_TEST_URL");
        String user = System.getenv("AI_READER_POSTGRES_TEST_USERNAME");
        String password = System.getenv("AI_READER_POSTGRES_TEST_PASSWORD");
        Flyway.configure().dataSource(url, user, password).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    }
    @Test void expiryIsExclusiveAndReadsDoNotExtendIt() {
        var repository = new PostgresAnalysisCache(jdbc);
        String key = UUID.randomUUID().toString();
        var now = Instant.now();
        var expiry = now.plusSeconds(10);
        var result = new AnalysisResult("Перевод", "Объяснение");
        repository.save(key, result, now, expiry);
        assertThat(repository.find(key, now)).contains(result);
        assertThat(repository.find(key, expiry.minusMillis(1))).contains(result);
        assertThat(repository.find(key, expiry)).isEmpty();
        repository.deleteExpired(expiry);
        assertThat(repository.find(key, now)).isEmpty();
    }
    @Test void onlyOneConcurrentRequestCanClaimAndExpiredKeyCanBeReused() throws Exception {
        var repository = new PostgresIllustrationRepository(jdbc);
        String key = UUID.randomUUID().toString();
        var now = Instant.now();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 12).mapToObj(i -> (Callable<Boolean>) () -> {
                var candidate = new Illustration(UUID.randomUUID(), key, "session-a", Illustration.Status.QUEUED,
                        null, null, now, now.plusSeconds(60));
                return repository.claim(candidate);
            }).toList();
            int claims = 0;
            for (var future : executor.invokeAll(tasks)) if (future.get()) claims++;
            assertThat(claims).isEqualTo(1);
        }
        var job = repository.findByKey(key, now).orElseThrow();
        assertThat(repository.find(job.id(), "session-b", now)).isEmpty();
        repository.ready(job.id(), "image/png");
        assertThat(repository.find(job.id(), "session-a", now).orElseThrow().status()).isEqualTo(Illustration.Status.READY);
        assertThat(repository.find(job.id(), "session-a", now.plusSeconds(60))).isEmpty();
        var replacement = new Illustration(UUID.randomUUID(), key, "session-a", Illustration.Status.QUEUED,
                null, null, now.plusSeconds(61), now.plusSeconds(121));
        assertThat(repository.claim(replacement)).isTrue();
        assertThat(repository.contains(job.id())).isFalse();
        repository.failInterrupted(now.plusSeconds(300));
        assertThat(repository.find(replacement.id(), "session-a", now.plusSeconds(61)).orElseThrow().errorCode())
                .isEqualTo("GENERATION_INTERRUPTED");
        repository.delete(replacement.id());
    }

    @Test void cleanupDeletesExpiredFilesAndMetadataAndOrphansButPreservesLiveImage(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var repository = new PostgresIllustrationRepository(jdbc);
        var storage = new LocalImageStorage(directory);
        var now = Instant.now();
        var expired = new Illustration(UUID.randomUUID(), UUID.randomUUID().toString(), "cleanup-test",
                Illustration.Status.QUEUED, null, null, now.minusSeconds(120), now.minusSeconds(60));
        var live = new Illustration(UUID.randomUUID(), UUID.randomUUID().toString(), "cleanup-test",
                Illustration.Status.QUEUED, null, null, now, now.plusSeconds(3600));
        repository.claim(expired);
        repository.claim(live);
        storage.save(expired.id(), new byte[]{1});
        storage.save(live.id(), new byte[]{2});
        storage.save(UUID.randomUUID(), new byte[]{3});
        new CacheMaintenance(new PostgresAnalysisCache(jdbc), repository, storage,
                Clock.fixed(now, ZoneOffset.UTC)).clean();
        assertThat(repository.contains(expired.id())).isFalse();
        assertThat(repository.contains(live.id())).isTrue();
        assertThat(storage.ids()).containsExactly(live.id());
        repository.delete(live.id());
    }
}
