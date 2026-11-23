package com.aireader.infrastructure.security;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

final class FirebaseAppCheck {
    private static final String ORIGIN = "https://firebaseappcheck.googleapis.com";
    private final JwtDecoder decoder;

    FirebaseAppCheck(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    String verify(String token) {
        return decoder.decode(token).getSubject();
    }

    static FirebaseAppCheck create(MobileAccessProperties properties, Clock clock) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        var decoder = NimbusJwtDecoder.withJwkSetUri(ORIGIN + "/v1/jwks")
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requestFactory))
                .build();
        decoder.setJwtValidator(validator(properties.firebaseProjectNumber(), properties.firebaseAppIds(), clock));
        return new FirebaseAppCheck(decoder);
    }

    static OAuth2TokenValidator<Jwt> validator(String project, Set<String> allowedApps, Clock clock) {
        var timestamp = new JwtTimestampValidator(Duration.ZERO);
        timestamp.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(timestamp, new JwtIssuerValidator(ORIGIN + "/" + project), jwt -> {
            var audience = jwt.getAudience();
            var expiresAt = jwt.getExpiresAt();
            var appId = jwt.getSubject();
            if ("JWT".equals(jwt.getHeaders().get("typ")) && audience != null
                    && audience.contains("projects/" + project) && expiresAt != null && expiresAt.isAfter(clock.instant())
                    && appId != null && allowedApps.contains(appId)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        });
    }
}
