package com.aireader.infrastructure.ai.image;

import com.aireader.application.port.IllustrationException;
import com.aireader.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class YandexIllustrationGeneratorTest {
    private MockRestServiceServer server;
    private YandexIllustrationGenerator generator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TextContext context = new TextContext("Book", "", "", "Castle", "Forest");
    private final IllustrationOptions options = new IllustrationOptions(null, null);
    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("http://localhost");
        server = MockRestServiceServer.bindTo(builder).build();
        generator = new YandexIllustrationGenerator(builder.build(), mapper,
                new IllustrationProperties(true, "test-key", "folder", URI.create("http://localhost"),
                        "aliceai-image-art-3.0", "yandexgpt-lite", Path.of("data"), Duration.ofSeconds(120), 10485760),
                "Describe a scene, at most 450 characters");
    }
    private String scene(String text) throws Exception {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("role", "assistant", "content", text)))));
    }
    @Test void callsCheapSceneModelThenOneImageAndValidatesActualFormat() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output);
        server.expect(requestTo("http://localhost/v1/chat/completions"))
                .andExpect(header("Authorization", "Api-Key test-key"))
                .andExpect(header("x-data-logging-enabled", "false"))
                .andExpect(jsonPath("$.model").value("gpt://folder/yandexgpt-lite"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andRespond(withSuccess(scene("Замок на холме, книжная иллюстрация"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost/v1/images/generations"))
                .andExpect(jsonPath("$.model").value("art://folder/aliceai-image-art-3.0"))
                .andExpect(jsonPath("$.size").value("1024x1024"))
                .andExpect(jsonPath("$.prompt").value("Замок на холме, книжная иллюстрация"))
                .andExpect(jsonPath("$.quality").doesNotExist())
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("data", List.of(Map.of("b64_json",
                        Base64.getEncoder().encodeToString(output.toByteArray()))))), MediaType.APPLICATION_JSON));
        var image = generator.generate(context, options);
        assertThat(image.bytes()).isEqualTo(output.toByteArray());
        assertThat(image.contentType()).isEqualTo("image/png");
        server.verify();
    }
    @Test void oversizedSceneDoesNotTriggerPaidImageCall() throws Exception {
        server.expect(requestTo("http://localhost/v1/chat/completions"))
                .andRespond(withSuccess(scene("a".repeat(501)), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> generator.generate(context, options)).isInstanceOfSatisfying(
                IllustrationException.class, ex -> assertThat(ex.code()).isEqualTo("SCENE_PROMPT_TOO_LONG"));
        server.verify();
    }
    @Test void provider403IsSanitizedAndNeverRetried() {
        server.expect(requestTo("http://localhost/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("sensitive provider error"));
        assertThatThrownBy(() -> generator.generate(context, options))
                .isInstanceOfSatisfying(IllustrationException.class,
                        ex -> assertThat(ex.code()).isEqualTo("IMAGE_PROVIDER_ACCESS_DENIED"))
                .hasMessageNotContaining("sensitive");
        server.verify();
    }
}
