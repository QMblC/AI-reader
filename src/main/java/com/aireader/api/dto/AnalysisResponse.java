package com.aireader.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Перевод и контекстное объяснение на русском.")
public record AnalysisResponse(
        @Schema(description = "Перевод выделенной фразы",
                example = "Сыграл в ящик",
                requiredMode = Schema.RequiredMode.REQUIRED) String translation,
        @Schema(description = "Объяснение смысла фразы с учётом контекста",
                example = "Выражение 'kick the bucket' — английская идиома, означающая смерть. "
                        + "В данном контексте речь идет о человеке, который умер после продолжительной болезни.",
                requiredMode = Schema.RequiredMode.REQUIRED) String explanation) {
}
