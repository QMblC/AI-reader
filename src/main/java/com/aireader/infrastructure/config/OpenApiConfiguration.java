package com.aireader.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    OpenAPI readerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("AI Reader API")
                .version("1.0")
                .description("API контекстного анализа текста. Перевод и объяснение — на русском. "
                        + "В зависимости от конфигурации сервера используется Yandex "
                        + "или локальная заглушка в демонстрационном режиме. Авторизация не требуется."));
    }
}
