package com.aireader.infrastructure.ai.llm.yandex;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("yandex")
public record YandexProperties(
        @NotBlank String apiKey,
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]+") String folderId,
        @NotBlank String modelName,
        @NotNull URI baseUrl,
        @DecimalMin("0.0") @DecimalMax("1.0") double temperature,
        @Min(1) int maxTokens,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {

    @AssertTrue(message = "Yandex API requires HTTPS (HTTP is allowed only for local tests)")
    public boolean isSecureEndpoint() {
        return baseUrl != null && baseUrl.getHost() != null
                && baseUrl.getUserInfo() == null && baseUrl.getQuery() == null
                && baseUrl.getFragment() == null
                && ("https".equals(baseUrl.getScheme())
                || ("http".equals(baseUrl.getScheme())
                && ("localhost".equals(baseUrl.getHost()) || "127.0.0.1".equals(baseUrl.getHost()))));
    }

    @AssertTrue(message = "Yandex timeouts must be at least 1ms")
    public boolean isTimeoutsValid() {
        return connectTimeout != null && readTimeout != null
                && connectTimeout.toMillis() > 0 && readTimeout.toMillis() > 0;
    }

    @Override
    public String toString() {
        return "YandexProperties[apiKey=<redacted>, folderId=" + folderId + ", modelName=" + modelName + "]";
    }
}
