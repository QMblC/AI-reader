package com.aireader.infrastructure.ai.image;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@Validated
@ConfigurationProperties("illustration")
public record IllustrationProperties(boolean enabled, String apiKey, String folderId,
        @NotNull URI baseUrl, @NotBlank String model, @NotBlank String sceneModel,
        @NotNull Path directory, @NotNull Duration timeout, @Min(1024) @Max(20971520) int maxImageBytes) {
    @AssertTrue(message = "Image generation requires a key and a plain folder ID")
    public boolean isCredentialsValid() {
        return !enabled || (apiKey != null && !apiKey.isBlank() && folderId != null && folderId.matches("[a-zA-Z0-9_-]+"));
    }
    @AssertTrue(message = "Image API requires HTTPS; HTTP is allowed only on localhost")
    public boolean isEndpointValid() {
        return baseUrl != null && baseUrl.getHost() != null && baseUrl.getUserInfo() == null
                && baseUrl.getQuery() == null && baseUrl.getFragment() == null
                && ("https".equals(baseUrl.getScheme()) || ("http".equals(baseUrl.getScheme())
                && SetHolder.LOCAL.contains(baseUrl.getHost())));
    }
    @AssertTrue(message = "Image timeout must be between 1 and 180 seconds")
    public boolean isTimeoutValid() {
        return timeout != null && timeout.toMillis() >= 1000 && timeout.toMillis() <= 180000;
    }
    private static class SetHolder { static final java.util.Set<String> LOCAL = java.util.Set.of("localhost", "127.0.0.1"); }
    @Override public String toString() { return "IllustrationProperties[enabled=" + enabled + ", apiKey=<redacted>]"; }
}
