package com.aireader.infrastructure.security;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

@Validated
@ConfigurationProperties("app.mobile-access")
public record MobileAccessProperties(
        @NotBlank @Pattern(regexp = "[0-9]+") String firebaseProjectNumber,
        @NotEmpty Set<String> firebaseAppIds,
        Duration accessTtl,
        Duration sessionTtl,
        @Positive int sessionCreationsPerMinute,
        @Positive int sessionRefreshesPerMinute,
        @Positive int guestAnalysesPerDay,
        @Positive int guestIllustrationsPerDay,
        @Positive int totalAnalysesPerDay,
        @Positive int totalIllustrationsPerDay) {

    public MobileAccessProperties {
        if (accessTtl == null || sessionTtl == null || accessTtl.toSeconds() < 1
                || sessionTtl.compareTo(accessTtl) <= 0) {
            throw new IllegalArgumentException("Guest session TTL must exceed the access token TTL, which must be at least one second");
        }
        if (firebaseProjectNumber != null && firebaseAppIds != null) {
            String appIdFormat = "1:" + java.util.regex.Pattern.quote(firebaseProjectNumber) + ":(android|ios):[0-9a-fA-F]+";
            if (firebaseAppIds.stream().anyMatch(appId -> !appId.matches(appIdFormat))) {
                throw new IllegalArgumentException("App Check requires allowed Android or iOS App IDs from the configured Firebase project");
            }
        }
    }
}
