package com.aireader.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    OpenAPI readerOpenApi() {
        return new OpenAPI().components(new Components()
                .addSecuritySchemes("appCheck", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER).name("X-Firebase-AppCheck"))
                .addSecuritySchemes("guestToken", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
                .info(new Info()
                .title("AI Reader API")
                .version("1.0")
                .description("API контекстного анализа и иллюстраций. Перевод и объяснение — на русском. "
                        + "В зависимости от конфигурации сервера используется Yandex "
                        + "или локальная заглушка анализа в демонстрационном режиме. Иллюстрации включаются отдельно. "
                        + "PostgreSQL и локальные файлы хранят результаты до 48 часов. "
                        + "Мобильный клиент подтверждает приложение и открывает гостевую сессию перед использованием ИИ."));
    }

    @Bean
    OpenApiCustomizer mobileApiSecurity() {
        return api -> api.getPaths().forEach((path, item) -> {
            if (path.startsWith("/api/v1/")) {
                var requirement = new SecurityRequirement().addList("appCheck");
                if (!"/api/v1/guest-sessions".equals(path)) {
                    requirement.addList("guestToken");
                }
                item.readOperations().forEach(operation -> operation.addSecurityItem(requirement));
            }
        });
    }
}
