package com.aireader.application.service;

import com.aireader.application.AnalyzeTextUseCase;
import com.aireader.application.port.AiAnalysisModule;
import com.aireader.application.port.AnalysisResultParser;
import com.aireader.application.port.AnalysisCache;
import com.aireader.application.port.GenerationQuota;
import com.aireader.domain.AnalysisResult;
import com.aireader.domain.TextContext;

public final class AnalyzeTextService implements AnalyzeTextUseCase {
    private final AiAnalysisModule aiModule;
    private final AnalysisResultParser resultParser;
    private final AnalysisCache cache;
    private final GenerationQuota quota;
    private final java.time.Clock clock;
    private final java.time.Duration ttl;
    private final String fingerprint;
    private final Object[] locks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public AnalyzeTextService(AiAnalysisModule aiModule, AnalysisResultParser resultParser,
            AnalysisCache cache, GenerationQuota quota, java.time.Clock clock, java.time.Duration ttl, String fingerprint) {
        this.aiModule = aiModule;
        this.resultParser = resultParser;
        this.cache = cache;
        this.quota = quota;
        this.clock = clock;
        this.ttl = ttl;
        this.fingerprint = fingerprint;
    }

    @Override
    public AnalysisResult analyze(TextContext context, String scope) {
        String key = CacheKeys.forContext(scope, context, "analysis", "ru", fingerprint);
        synchronized (locks[(key.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            var cached = cache.find(key, clock.instant());
            if (cached.isPresent()) return cached.get();
            quota.reserveAnalysis(scope);
            var result = resultParser.parse(aiModule.analyze(context));
            var created = clock.instant();
            cache.save(key, result, created, created.plus(ttl));
            return result;
        }
    }
}
