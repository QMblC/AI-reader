package com.aireader.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Сведения о книге, выделенная фраза и окружающий контекст")
public record AnalysisRequest(
        @Schema(description = "Название книги; можно не передавать или оставить пустым",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 500) String book,
        @Schema(description = "Автор книги; можно не передавать или оставить пустым",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 300) String author,
        @Schema(description = "Издание книги; можно не передавать или оставить пустым",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 300) String edition,
        @Schema(description = "Выделенная фраза", example = "He kicked the bucket",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 2000) String selectedText,
        @Schema(description = "Окружающий абзац",
                example = "After a long illness, he finally kicked the bucket.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 12000) String context) {
}
