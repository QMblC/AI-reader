package com.aireader.application;

import com.aireader.domain.AnalysisResult;
import com.aireader.domain.TextContext;

public interface AnalyzeTextUseCase {
    AnalysisResult analyze(TextContext context);
}
