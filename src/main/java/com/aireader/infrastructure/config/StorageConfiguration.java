package com.aireader.infrastructure.config;

import com.aireader.application.*;
import com.aireader.application.port.*;
import com.aireader.application.service.GenerateIllustrationService;
import com.aireader.infrastructure.ai.image.*;
import com.aireader.infrastructure.storage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.*;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(IllustrationProperties.class)
public class StorageConfiguration {
    @Bean Clock cacheClock() { return Clock.systemUTC(); }

    @Bean ImageStorage imageStorage(IllustrationProperties properties) throws IOException {
        return new LocalImageStorage(properties.directory());
    }

    @Bean(destroyMethod = "close")
    HttpClient illustrationHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean IllustrationGenerator illustrationGenerator(IllustrationProperties properties, ObjectMapper mapper,
            @Qualifier("illustrationHttpClient") HttpClient httpClient) throws IOException {
        if (!properties.enabled()) {
            return new IllustrationGenerator() {
                public com.aireader.domain.GeneratedImage generate(com.aireader.domain.TextContext context,
                        com.aireader.domain.IllustrationOptions options) {
                    throw new IllustrationException("ILLUSTRATION_DISABLED", 503);
                }
                public String fingerprint() { return "disabled"; }
            };
        }
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.timeout());
        var client = RestClient.builder().baseUrl(properties.baseUrl().toString()).requestFactory(factory).build();
        String prompt = new ClassPathResource("prompts/illustration-scene.txt").getContentAsString(StandardCharsets.UTF_8);
        return new YandexIllustrationGenerator(client, mapper, properties, prompt);
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolExecutor illustrationExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4),
                Thread.ofPlatform().name("illustration-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean GenerateIllustrationUseCase generateIllustrationUseCase(IllustrationRepository repository, GenerationQuota quota,
            ImageStorage storage, IllustrationGenerator generator,
            @Qualifier("illustrationExecutor") ThreadPoolExecutor executor, Clock clock,
            @Value("${cache.ttl}") Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(48)) > 0) {
            throw new IllegalArgumentException("Cache TTL must be positive and at most 48 hours");
        }
        return new GenerateIllustrationService(repository, quota, storage, generator, executor, clock, ttl);
    }

    @Bean CacheMaintenance cacheMaintenance(AnalysisCache analyses, IllustrationRepository illustrations,
            ImageStorage storage, Clock clock) {
        return new CacheMaintenance(analyses, illustrations, storage, clock);
    }
}
