package com.aireader.infrastructure.ai.llm.yandex;

import com.aireader.application.port.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "yandex", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(YandexProperties.class)
public class YandexConfiguration {
    @Bean(destroyMethod = "close")
    HttpClient yandexHttpClient(YandexProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    RestClient yandexRestClient(@Qualifier("yandexHttpClient") HttpClient httpClient,
                               YandexProperties properties) {
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(properties.baseUrl().toString())
                .requestFactory(factory).build();
    }

    @Bean({"yandexLlmClient", "analysisLlmClient"})
    LlmClient yandexLlmClient(@Qualifier("yandexRestClient") RestClient client,
                             ObjectMapper mapper, YandexProperties properties) {
        return new YandexLlmClient(client, mapper, properties);
    }
}
