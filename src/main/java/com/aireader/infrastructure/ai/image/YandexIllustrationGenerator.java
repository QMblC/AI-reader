package com.aireader.infrastructure.ai.image;

import com.aireader.application.port.*;
import com.aireader.application.service.CacheKeys;
import com.aireader.domain.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.MediaType;
import org.springframework.web.client.*;
import java.io.IOException;
import java.util.Base64;

/** The scene prompt only lives in memory for the duration of a single job. Never retry a paid POST. */
public final class YandexIllustrationGenerator implements IllustrationGenerator {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final IllustrationProperties properties;
    private final String systemPrompt;

    public YandexIllustrationGenerator(RestClient client, ObjectMapper mapper,
            IllustrationProperties properties, String systemPrompt) {
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
        this.systemPrompt = systemPrompt;
    }
    public String fingerprint() {
        return CacheKeys.hash(properties.model(), properties.sceneModel(), properties.baseUrl().toString(),
                properties.folderId(), systemPrompt, "scene-temperature-0.2-max-tokens-300-v1");
    }
    public GeneratedImage generate(TextContext context, IllustrationOptions options) {
        var request = mapper.createObjectNode();
        request.put("model", "gpt://" + properties.folderId() + "/" + properties.sceneModel());
        request.put("temperature", 0.2);
        request.put("max_tokens", 300);
        request.put("stream", false);
        var messages = request.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        var data = mapper.createObjectNode();
        data.set("text", mapper.valueToTree(context));
        data.put("style", switch (options.style()) {
            case BOOK -> "классическая книжная иллюстрация";
            case WATERCOLOR -> "акварель";
            case REALISTIC -> "реалистичная живопись";
            case COMIC -> "комикс без надписей";
        });
        messages.addObject().put("role", "user").put("content", data.toString());
        var choice = post("/v1/chat/completions", request, 65536).path("choices").path(0);
        var message = choice.path("message");
        var content = message.path("content");
        if (!"stop".equals(choice.path("finish_reason").asText())
                || !"assistant".equals(message.path("role").asText())
                || (message.hasNonNull("refusal") && !message.path("refusal").asText().isBlank())
                || !content.isTextual() || content.asText().isBlank()) {
            throw new IllustrationException("INVALID_SCENE_RESPONSE", 502);
        }
        String scene = content.asText().strip();
        if (scene.codePointCount(0, scene.length()) > 500) {
            throw new IllustrationException("SCENE_PROMPT_TOO_LONG", 502);
        }
        var imageRequest = mapper.createObjectNode();
        imageRequest.put("model", "art://" + properties.folderId() + "/" + properties.model());
        imageRequest.put("prompt", scene);
        imageRequest.put("size", options.size().apiValue());
        var imageData = post("/v1/images/generations", imageRequest,
                properties.maxImageBytes() * 4 / 3 + 65536).path("data");
        if (!imageData.isArray() || imageData.size() != 1 || !imageData.path(0).path("b64_json").isTextual()) {
            throw new IllustrationException("INVALID_IMAGE_RESPONSE", 502);
        }
        try {
            var bytes = Base64.getDecoder().decode(imageData.get(0).path("b64_json").asText());
            if (bytes.length == 0 || bytes.length > properties.maxImageBytes()) {
                throw new IllustrationException("INVALID_IMAGE_RESPONSE", 502);
            }
            String type = imageType(bytes);
            return new GeneratedImage(bytes, type);
        } catch (IllegalArgumentException ex) {
            throw new IllustrationException("INVALID_IMAGE_RESPONSE", 502);
        }
    }

    private JsonNode post(String path, JsonNode request, int maxBytes) {
        try {
            return client.post().uri(path).header("Authorization", "Api-Key " + properties.apiKey())
                    .header("OpenAI-Project", properties.folderId()).header("x-data-logging-enabled", "false")
                    .contentType(MediaType.APPLICATION_JSON).body(request).exchange((req, response) -> {
                        int status = response.getStatusCode().value();
                        if (status != 200) {
                            String code = switch (status) {
                                case 401 -> "IMAGE_PROVIDER_AUTHENTICATION_FAILED";
                                case 403 -> "IMAGE_PROVIDER_ACCESS_DENIED";
                                case 429 -> "IMAGE_PROVIDER_RATE_LIMIT";
                                default -> "IMAGE_PROVIDER_HTTP_" + status;
                            };
                            throw new IllustrationException(code, 503);
                        }
                        byte[] body = response.getBody().readNBytes(maxBytes + 1);
                        if (body.length > maxBytes) throw new IllustrationException("IMAGE_RESPONSE_TOO_LARGE", 502);
                        return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
                    });
        } catch (RestClientException ex) {
            throw new IllustrationException("IMAGE_PROVIDER_UNAVAILABLE", 503);
        }
    }

    private static String imageType(byte[] bytes) {
        // Validate the actual format and dimensions before serving provider bytes to a browser.
        try (var input = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var readers = javax.imageio.ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllustrationException("INVALID_IMAGE_RESPONSE", 502);
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!java.util.Set.of("png", "jpeg", "jpg").contains(format)
                        || reader.getWidth(0) < 1 || reader.getHeight(0) < 1
                        || reader.getWidth(0) > 4096 || reader.getHeight(0) > 4096) {
                    throw new IllustrationException("INVALID_IMAGE_RESPONSE", 502);
                }
                return format.equals("png") ? "image/png" : "image/jpeg";
            } finally { reader.dispose(); }
        } catch (IOException ex) { throw new IllustrationException("INVALID_IMAGE_RESPONSE", 502); }
    }
}
