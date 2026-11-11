package com.aireader.api.dto;

import com.aireader.domain.IllustrationOptions;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;

public record IllustrationRequest(
        @Size(max = 500) String book,
        @Size(max = 300) String author,
        @Size(max = 300) String edition,
        @NotBlank @Size(max = 2000) String selectedText,
        @Schema(description = "Только уже прочитанный контекст, без будущих глав")
        @NotBlank @Size(max = 12000) String context,
        IllustrationOptions.Style style,
        IllustrationOptions.Size size) { }
