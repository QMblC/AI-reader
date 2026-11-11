package com.aireader.infrastructure.ai.llm.yandex;

import com.aireader.application.port.InvalidLlmResponseException;
import com.aireader.application.port.LlmClient;
import com.aireader.application.port.LlmPrompt;
import com.aireader.application.port.LlmUnavailableException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;

public final class YandexLlmClient implements LlmClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final YandexProperties properties;
    private final JsonNode schema;

    public YandexLlmClient(RestClient client, ObjectMapper mapper, YandexProperties properties) {
        this.client = client;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.properties = properties;
        try (var input = new ClassPathResource("schemas/analysis-result.schema.json").getInputStream()) {
            this.schema = this.mapper.readTree(input);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot load analysis response schema", ex);
        }
    }

    @Override
    public String analyze(LlmPrompt prompt) {
        var request = mapper.createObjectNode();
        request.put("model", "gpt://" + properties.folderId() + "/" + properties.modelName());
        request.put("temperature", properties.temperature());
        request.put("max_tokens", properties.maxTokens());
        request.put("stream", false);
        var messages = request.putArray("messages");
        if (!prompt.systemMessage().isBlank()) {
            messages.addObject().put("role", "system").put("content", prompt.systemMessage());
        }
        messages.addObject().put("role", "user")
                .put("content", mapper.valueToTree(prompt.context()).toString());
        request.putObject("response_format").put("type", "json_schema")
                .putObject("json_schema").put("name", "reading_analysis").set("schema", schema);

        String body;
        try {
            var response = client.post().uri("/v1/chat/completions")
                    .header("Authorization", "Api-Key " + properties.apiKey())
                    .header("OpenAI-Project", properties.folderId())
                    .contentType(MediaType.APPLICATION_JSON).body(request)
                    .retrieve().toEntity(String.class);
            if (response.getStatusCode().value() != 200) {
                throw new LlmUnavailableException("Yandex request failed", null,
                        response.getStatusCode().value());
            }
            body = response.getBody();
        } catch (RestClientResponseException ex) {
            // Do not retain the provider body, which may contain submitted text.
            throw new LlmUnavailableException("Yandex request failed", null, ex.getStatusCode().value());
        } catch (RestClientException ex) {
            throw new LlmUnavailableException("Yandex request unavailable", null);
        }
        return parseContent(body);
    }

    private String parseContent(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidLlmResponseException();
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw new InvalidLlmResponseException();
        }
        var choices = root == null ? mapper.nullNode() : root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new InvalidLlmResponseException();
        }
        var choice = choices.get(0);
        var message = choice.path("message");
        var content = message.path("content");
        if (!"stop".equals(choice.path("finish_reason").asText())
                || !"assistant".equals(message.path("role").asText())
                || (message.hasNonNull("refusal") && !message.path("refusal").asText().isBlank())
                || !content.isTextual() || content.textValue().isBlank()) {
            throw new InvalidLlmResponseException();
        }
        return content.textValue();
    }
}
