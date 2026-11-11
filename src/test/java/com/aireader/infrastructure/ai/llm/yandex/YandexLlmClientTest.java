package com.aireader.infrastructure.ai.llm.yandex;

import com.aireader.application.port.InvalidLlmResponseException;
import com.aireader.application.port.LlmPrompt;
import com.aireader.application.port.LlmUnavailableException;
import com.aireader.domain.TextContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YandexLlmClientTest {
    private MockRestServiceServer server;
    private YandexLlmClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmPrompt prompt = new LlmPrompt("Translate and explain",
            new TextContext("Book", "Author", "Edition", "Break a leg!", "Before the show."));

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://localhost");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new YandexLlmClient(builder.build(), mapper, new YandexProperties(
                "test-key", "test-folder", "yandexgpt-5.1", URI.create("http://localhost"),
                0.2, 1500, Duration.ofSeconds(5), Duration.ofSeconds(60)));
    }

    @Test
    void sendsCredentialsSchemaAndAllContextFieldsSeparatelyFromInstructions() throws Exception {
        String content = "{\"translation\":\"Удачи!\",\"explanation\":\"Пожелание удачи перед выступлением.\"}";
        String response = mapper.writeValueAsString(java.util.Map.of("choices", java.util.List.of(
                java.util.Map.of("finish_reason", "stop", "message", java.util.Map.of(
                        "role", "assistant", "content", content)))));
        server.expect(requestTo("http://localhost/v1/chat/completions"))
                .andExpect(header("Authorization", "Api-Key test-key"))
                .andExpect(header("OpenAI-Project", "test-folder"))
                .andExpect(jsonPath("$.model").value("gpt://test-folder/yandexgpt-5.1"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value(prompt.systemMessage()))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(request -> {
                    var body = (org.springframework.mock.http.client.MockClientHttpRequest) request;
                    var context = mapper.readTree(mapper.readTree(body.getBodyAsString())
                            .path("messages").get(1).path("content").asText());
                    assertThat(context).isEqualTo(mapper.valueToTree(prompt.context()));
                })
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.additionalProperties").value(false))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThat(client.analyze(prompt)).isEqualTo(content);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 503})
    void preservesProviderStatusWithoutRetainingProviderBody(int status) {
        server.expect(requestTo("http://localhost/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("private-provider-body"));
        assertThatThrownBy(() -> client.analyze(prompt))
                .isInstanceOfSatisfying(LlmUnavailableException.class, ex -> {
                    assertThat(ex.providerStatus()).isEqualTo(status);
                    assertThat(ex.getCause()).isNull();
                    assertThat(ex.getMessage()).doesNotContain("private-provider-body", "test-key");
                });
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"choices\":[]}", "not JSON",
            "{\"choices\":[]} {}", "{\"choices\":[],\"choices\":[]}",
            "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"user\",\"content\":\"{}\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\" \"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\",\"refusal\":\"refused\"}}]}"})
    void rejectsMalformedTruncatedOrRefusedResponses(String response) {
        server.expect(requestTo("http://localhost/v1/chat/completions"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.analyze(prompt)).isInstanceOf(InvalidLlmResponseException.class);
        server.verify();
    }

    @Test
    void mapsNetworkFailureToUnavailableWithoutExposingRequest() {
        server.expect(requestTo("http://localhost/v1/chat/completions"))
                .andRespond(request -> { throw new ResourceAccessException("private-transport-details"); });
        assertThatThrownBy(() -> client.analyze(prompt))
                .isInstanceOfSatisfying(LlmUnavailableException.class, ex -> {
                    assertThat(ex.providerStatus()).isNull();
                    assertThat(ex.getCause()).isNull();
                });
        server.verify();
    }
}
