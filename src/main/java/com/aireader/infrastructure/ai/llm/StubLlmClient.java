package com.aireader.infrastructure.ai.llm;

import com.aireader.application.port.LlmClient;
import com.aireader.application.port.LlmPrompt;

/** Deterministic offline adapter: no API keys, network calls or translation. */
public final class StubLlmClient implements LlmClient {
    @Override
    public String analyze(LlmPrompt prompt) {
        return """
                {
                  "translation": "Сыграл в ящик",
                  "explanation": "Выражение 'kick the bucket' — английская идиома, означающая смерть. В данном контексте речь идет о человеке, который умер после продолжительной болезни."
                }
                """;
    }
}
