package com.aireader.application.port;

import com.aireader.domain.*;

public interface IllustrationGenerator {
    GeneratedImage generate(TextContext context, IllustrationOptions options);
    String fingerprint();
}
