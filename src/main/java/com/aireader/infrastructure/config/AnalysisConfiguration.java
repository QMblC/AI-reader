package com.aireader.infrastructure.config;

import com.aireader.application.AnalyzeTextUseCase;
import com.aireader.application.port.AiAnalysisModule;
import com.aireader.application.port.AnalysisResultParser;
import com.aireader.application.port.AnalysisCache;
import com.aireader.application.service.CacheKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import com.aireader.application.service.AnalyzeTextService;
import com.aireader.infrastructure.analysis.JsonAnalysisResultParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AnalysisConfiguration {
    @Bean
    AnalysisResultParser analysisResultParser(ObjectMapper mapper) {
        return new JsonAnalysisResultParser(mapper);
    }

    @Bean
    AnalyzeTextUseCase analyzeTextUseCase(AiAnalysisModule aiModule, AnalysisResultParser resultParser,
            AnalysisCache cache, java.time.Clock clock, @Value("${cache.ttl}") java.time.Duration ttl,
            @Value("${yandex.enabled}") boolean enabled, @Value("${yandex.model-name}") String model,
            @Value("${yandex.base-url}") String endpoint, @Value("${yandex.folder-id}") String folder,
            @Value("${yandex.temperature}") String temperature, @Value("${yandex.max-tokens}") String maxTokens)
            throws java.io.IOException {
        String prompt = new ClassPathResource("prompts/analysis-system.txt")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new AnalyzeTextService(aiModule, resultParser, cache, clock, ttl,
                CacheKeys.hash(Boolean.toString(enabled), model, endpoint, folder, temperature, maxTokens, prompt));
    }
}
