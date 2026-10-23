package com.aireader.infrastructure.ai;

import com.aireader.application.port.AiAnalysisModule;
import com.aireader.application.port.LlmClient;
import com.aireader.infrastructure.ai.llm.StubLlmClient;
import com.aireader.infrastructure.ai.prompt.ContextPromptBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
public class AiConfiguration {
    @Bean({"stubLlmClient", "analysisLlmClient"})
    @ConditionalOnProperty(prefix = "yandex", name = "enabled", havingValue = "false", matchIfMissing = true)
    LlmClient stubLlmClient() {
        return new StubLlmClient();
    }

    @Bean
    ContextPromptBuilder contextPromptBuilder() throws IOException {
        var resource = new ClassPathResource("prompts/analysis-system.txt");
        return new ContextPromptBuilder(resource.getContentAsString(StandardCharsets.UTF_8));
    }

    @Bean
    AiAnalysisModule aiAnalysisModule(ContextPromptBuilder promptBuilder,
            @Qualifier("analysisLlmClient") LlmClient llmClient) {
        return new DefaultAiAnalysisModule(promptBuilder, llmClient);
    }
}
