package com.aireader.application.service;

import com.aireader.application.port.*;
import com.aireader.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyzeTextServiceTest {
    @Test void concurrentRequestsShareResultButAnotherSessionGeneratesSeparately() throws Exception {
        var results = new ConcurrentHashMap<String, AnalysisResult>();
        var calls = new AtomicInteger();
        AnalysisCache cache = new AnalysisCache() {
            public Optional<AnalysisResult> find(String key, Instant now) { return Optional.ofNullable(results.get(key)); }
            public void save(String key, AnalysisResult result, Instant created, Instant expires) { results.put(key, result); }
            public void deleteExpired(Instant now) { }
        };
        var result = new AnalysisResult("Замок", "Описание замка");
        var quota = mock(GenerationQuota.class);
        var service = new AnalyzeTextService(context -> { calls.incrementAndGet(); return "result"; },
                json -> result, cache, quota, Clock.systemUTC(), Duration.ofHours(48), "model+prompt");
        var context = new TextContext("", "", "", "castle", "An old castle.");
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(i -> (Callable<AnalysisResult>) () -> service.analyze(context, "session-a")).toList();
            for (var future : executor.invokeAll(tasks)) assertThat(future.get()).isEqualTo(result);
        }
        assertThat(calls.get()).isEqualTo(1);
        service.analyze(context, "session-b");
        assertThat(calls.get()).isEqualTo(2);
        verify(quota).reserveAnalysis("session-a");
        verify(quota).reserveAnalysis("session-b");
    }
}
