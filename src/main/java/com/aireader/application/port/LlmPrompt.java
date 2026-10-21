package com.aireader.application.port;

import com.aireader.domain.TextContext;

import java.util.Objects;

/** Instructions and request data stay separate, including when the instructions are empty. */
public record LlmPrompt(String systemMessage, TextContext context) {
    public LlmPrompt {
        Objects.requireNonNull(systemMessage);
        Objects.requireNonNull(context);
    }
}
