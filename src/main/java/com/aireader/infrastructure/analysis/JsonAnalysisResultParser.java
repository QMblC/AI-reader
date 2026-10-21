package com.aireader.infrastructure.analysis;

import com.aireader.application.port.AnalysisResultParser;
import com.aireader.application.port.InvalidLlmResponseException;
import com.aireader.domain.AnalysisResult;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict model-output validation; never changes the application's HTTP JSON settings. */
public final class JsonAnalysisResultParser implements AnalysisResultParser {
    private final ObjectMapper mapper;

    public JsonAnalysisResultParser(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public AnalysisResult parse(String response) {
        if (response == null || response.isBlank()) {
            throw new InvalidLlmResponseException();
        }

        JsonNode root;
        try {
            root = mapper.readTree(response);
        } catch (JsonProcessingException ex) {
            throw new InvalidLlmResponseException();
        }

        if (root == null || !root.isObject() || root.size() != 2) {
            throw new InvalidLlmResponseException();
        }
        var translation = root.path("translation");
        var explanation = root.path("explanation");
        if (!translation.isTextual() || translation.textValue().isBlank()
                || !explanation.isTextual() || explanation.textValue().isBlank()) {
            throw new InvalidLlmResponseException();
        }
        return new AnalysisResult(translation.textValue(), explanation.textValue());
    }
}
