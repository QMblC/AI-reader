package com.aireader.infrastructure.security;

import com.aireader.application.port.IllustrationGenerator;
import com.aireader.domain.GeneratedImage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "AI_READER_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(properties = {
        "app.mobile-access.firebase-project-number=1234", "app.mobile-access.firebase-app-ids=1:1234:android:ab,1:1234:ios:cd",
        "app.mobile-access.session-creations-per-minute=2", "app.mobile-access.guest-analyses-per-day=1",
        "app.mobile-access.total-analyses-per-day=1", "app.mobile-access.guest-illustrations-per-day=1",
        "app.mobile-access.total-illustrations-per-day=1", "app.rate-limit.requests-per-minute=1000",
        "illustration.enabled=true", "yandex.enabled=false"
})
@AutoConfigureMockMvc
class MobileAccessIntegrationTest {
    private static final String APP = "1:1234:android:ab";
    private static final String SESSIONS = "/api/v1/guest-sessions";
    private static final String ANALYSES = "/api/v1/analyses";
    private static final String ILLUSTRATIONS = "/api/v1/illustrations";
    private static final Instant NOW = Instant.parse("2026-11-11T10:00:30Z");
    private static final String REQUEST = """
            {"selectedText":"old castle","context":"They saw the old castle near the river."}
            """;

    @TempDir static Path images;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired RSAKey signingKey;
    @MockitoBean IllustrationGenerator generator;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("AI_READER_POSTGRES_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("AI_READER_POSTGRES_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("AI_READER_POSTGRES_TEST_PASSWORD"));
        registry.add("illustration.directory", () -> images.toString());
    }

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE guest_sessions, guest_access_tokens, api_usage, analysis_cache, illustration_jobs");
    }

    @Test
    void rejectsUnverifiedAppsAndRequiresIssuedCredentials() throws Exception {
        mvc.perform(post(SESSIONS)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("APP_CHECK_REQUIRED"));
        mvc.perform(post(SESSIONS).header("X-Firebase-AppCheck", "malformed"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("APP_CHECK_INVALID"));
        for (String invalidApp : new String[]{"1:1234:android:ff", "1:4321:android:ab"}) {
            send(post(SESSIONS), signedToken(invalidApp, NOW.plusSeconds(300)), null)
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("APP_CHECK_INVALID"));
        }
        send(post(SESSIONS), signedToken(APP, NOW.minusSeconds(1)), null).andExpect(status().isUnauthorized());
        send(post(SESSIONS), signedToken(APP, NOW.plusSeconds(300), "projects/4321"), null)
                .andExpect(status().isUnauthorized());
        String appCheck = validAppCheck();
        int signatureStart = appCheck.lastIndexOf('.') + 1;
        String invalidSignature = appCheck.substring(0, signatureStart) + (appCheck.charAt(signatureStart) == 'A' ? 'B' : 'A')
                + appCheck.substring(signatureStart + 1);
        send(post(SESSIONS), invalidSignature, null).andExpect(status().isUnauthorized());
        send(post(ANALYSES).header("X-Reader-Session", "b135ff63-3a5e-4ece-965c-6bd330632cca"), appCheck, null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GUEST_ACCESS_INVALID"));
        JsonNode guest = guest(appCheck);
        send(post(ANALYSES), appCheck, guest.path("refreshToken").asText()).andExpect(status().isUnauthorized());
        send(post(ANALYSES), signedToken("1:1234:ios:cd", NOW.plusSeconds(300)), guest.path("accessToken").asText())
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void refreshKeepsCacheAndAppliedQuotaAndGlobalLimitSurvivesNewSession() throws Exception {
        String appCheck = validAppCheck();
        JsonNode guest = guest(appCheck);
        String access = guest.path("accessToken").asText();
        send(jsonPost(ANALYSES, REQUEST), appCheck, access).andExpect(status().isOk());

        jdbc.update("UPDATE guest_access_tokens SET expires_at = ?", java.sql.Timestamp.from(NOW.minusSeconds(1)));
        send(jsonPost(ANALYSES, REQUEST), appCheck, access).andExpect(status().isUnauthorized());
        String refreshed = json(send(post(SESSIONS + "/refresh"), appCheck, guest.path("refreshToken").asText())
                .andExpect(status().isOk())).path("accessToken").asText();
        assertThat(refreshed).isNotEqualTo(access);
        send(jsonPost(ANALYSES, REQUEST), appCheck, refreshed).andExpect(status().isOk());
        send(jsonPost(ANALYSES, REQUEST.replace("old castle", "long bridge")), appCheck, refreshed)
                .andExpect(status().isTooManyRequests()).andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("GUEST_QUOTA_EXCEEDED"));

        send(jsonPost(ANALYSES, REQUEST), appCheck, guest(appCheck).path("accessToken").asText())
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("GENERATION_BUDGET_EXHAUSTED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_usage WHERE operation = 'analysis'", Integer.class)).isEqualTo(2);
        send(post(SESSIONS), appCheck, null).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("GUEST_SESSION_RATE_LIMIT"));
    }

    @Test
    void illustrationRetriesShareJobAndBlockedClaimsAreRolledBack() throws Exception {
        when(generator.fingerprint()).thenReturn("test-image");
        when(generator.generate(any(), any())).thenReturn(new GeneratedImage(new byte[]{1, 2, 3}, "image/png"));
        String appCheck = validAppCheck();
        String access = guest(appCheck).path("accessToken").asText();
        String firstJob = json(send(jsonPost(ILLUSTRATIONS, REQUEST), appCheck, access).andExpect(status().isAccepted()))
                .path("id").asText();
        send(jsonPost(ILLUSTRATIONS, REQUEST), appCheck, access).andExpect(jsonPath("$.id").value(firstJob));
        send(get(ILLUSTRATIONS + "/" + firstJob), appCheck, access).andExpect(jsonPath("$.id").value(firstJob));
        await().untilAsserted(() -> send(get(ILLUSTRATIONS + "/" + firstJob + "/image"), appCheck, access)
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1, 2, 3})));

        String otherAccess = guest(appCheck).path("accessToken").asText();
        send(get(ILLUSTRATIONS + "/" + firstJob + "/image"), appCheck, otherAccess).andExpect(status().isNotFound());
        send(jsonPost(ILLUSTRATIONS, REQUEST), appCheck, otherAccess).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("GENERATION_BUDGET_EXHAUSTED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM illustration_jobs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_usage WHERE operation = 'illustration'", Integer.class)).isEqualTo(2);
    }

    private String validAppCheck() throws Exception {
        return signedToken(APP, NOW.plusSeconds(300));
    }

    private String signedToken(String app, Instant expiresAt) throws Exception {
        return signedToken(app, expiresAt, "projects/1234");
    }

    private String signedToken(String app, Instant expiresAt, String audience) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
                new JWTClaimsSet.Builder().issuer("https://firebaseappcheck.googleapis.com/1234").audience(audience)
                        .subject(app).expirationTime(Date.from(expiresAt)).build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private JsonNode guest(String appCheck) throws Exception {
        return json(send(post(SESSIONS), appCheck, null).andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String appCheck, String guestToken) throws Exception {
        request.header("X-Firebase-AppCheck", appCheck);
        if (guestToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + guestToken);
        }
        return mvc.perform(request);
    }

    private MockHttpServletRequestBuilder jsonPost(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalAppCheck {
        @Bean @Primary Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean RSAKey signingKey() throws Exception {
            return new RSAKeyGenerator(2048).generate();
        }

        @Bean @Primary FirebaseAppCheck testAppCheck(RSAKey key, MobileAccessProperties properties, Clock clock) throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
            decoder.setJwtValidator(FirebaseAppCheck.validator(properties.firebaseProjectNumber(), properties.firebaseAppIds(), clock));
            return new FirebaseAppCheck(decoder);
        }
    }
}
