package com.aireader.domain;

import java.util.Objects;

public record AnalysisResult(String translation, String explanation) {
    public AnalysisResult {
        Objects.requireNonNull(translation);
        Objects.requireNonNull(explanation);
        if (translation.isBlank() || explanation.isBlank()) {
            throw new IllegalArgumentException("Analysis result must not be blank");
        }
    }
}
