package com.aireader.infrastructure.ai;

import com.aireader.application.port.AiAnalysisModule;
import com.aireader.application.port.LlmClient;
import com.aireader.domain.TextContext;
import com.aireader.infrastructure.ai.prompt.ContextPromptBuilder;

/** Owns prompt preparation and model calls, while the application validates the returned JSON. */
public final class DefaultAiAnalysisModule implements AiAnalysisModule {
    private final ContextPromptBuilder promptBuilder;
    private final LlmClient llmClient;

    public DefaultAiAnalysisModule(ContextPromptBuilder promptBuilder, LlmClient llmClient) {
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
    }

    @Override
    public String analyze(TextContext context) {
        var prompt = promptBuilder.build(context);
        return llmClient.analyze(prompt);
    }
}
