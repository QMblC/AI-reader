package com.aireader.application.port;

import com.aireader.domain.TextContext;

/** Collaboration boundary: request data in, raw analysis JSON out. */
public interface AiAnalysisModule {
    String analyze(TextContext context);
}
