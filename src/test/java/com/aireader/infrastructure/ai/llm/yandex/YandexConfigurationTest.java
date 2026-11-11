package com.aireader.infrastructure.ai.llm.yandex;

import com.aireader.application.port.LlmClient;
import com.aireader.infrastructure.ai.AiConfiguration;
import com.aireader.infrastructure.ai.llm.StubLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class YandexConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AiConfiguration.class, YandexConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void usesStubWithoutCredentialsByDefault() {
        context.run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(LlmClient.class);
            assertThat(app.getBean("analysisLlmClient")).isInstanceOf(StubLlmClient.class);
        });
    }

    @Test
    void selectsOnlyYandexWhenEnabled() {
        configuredContext().run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(LlmClient.class);
            assertThat(app.getBean("analysisLlmClient")).isInstanceOf(YandexLlmClient.class);
        });
    }

    @Test
    void failsAtStartupWithoutKeyOrWithFullModelUriAsFolder() {
        configuredContext().withPropertyValues("yandex.api-key=")
                .run(app -> assertThat(app).hasFailed());
        configuredContext().withPropertyValues("yandex.folder-id=gpt://folder/model")
                .run(app -> assertThat(app).hasFailed());
    }

    private ApplicationContextRunner configuredContext() {
        return context.withPropertyValues("yandex.enabled=true", "yandex.api-key=test-key",
                "yandex.folder-id=test-folder", "yandex.model-name=yandexgpt-5.1",
                "yandex.base-url=https://ai.api.cloud.yandex.net", "yandex.temperature=0.2",
                "yandex.max-tokens=1500", "yandex.connect-timeout=5s", "yandex.read-timeout=60s");
    }
}
