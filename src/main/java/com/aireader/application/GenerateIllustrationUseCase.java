package com.aireader.application;

import com.aireader.domain.*;
import java.util.UUID;

public interface GenerateIllustrationUseCase {
    Illustration generate(TextContext context, IllustrationOptions options, String scope);
    Illustration get(UUID id, String scope);
    GeneratedImage image(UUID id, String scope);
}
