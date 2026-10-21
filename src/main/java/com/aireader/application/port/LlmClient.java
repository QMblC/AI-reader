package com.aireader.application.port;

/** Provider adapters own transport and credentials and return the model's raw JSON text. */
public interface LlmClient {
    String analyze(LlmPrompt prompt);
}
