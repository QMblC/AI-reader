package com.aireader.infrastructure.ai.prompt;

import com.aireader.application.port.LlmPrompt;
import com.aireader.domain.TextContext;

import java.util.Objects;

public final class ContextPromptBuilder {
    private final String systemPrompt;

    public ContextPromptBuilder(String systemPrompt) {
        this.systemPrompt = Objects.requireNonNull(systemPrompt);
    }

    public LlmPrompt build(TextContext context) {
        return new LlmPrompt(systemPrompt, context);
    }
}
