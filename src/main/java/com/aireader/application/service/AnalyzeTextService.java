package com.aireader.application.service;

import com.aireader.application.AnalyzeTextUseCase;
import com.aireader.application.port.AiAnalysisModule;
import com.aireader.application.port.AnalysisResultParser;
import com.aireader.domain.AnalysisResult;
import com.aireader.domain.TextContext;

public final class AnalyzeTextService implements AnalyzeTextUseCase {
    private final AiAnalysisModule aiModule;
    private final AnalysisResultParser resultParser;

    public AnalyzeTextService(AiAnalysisModule aiModule, AnalysisResultParser resultParser) {
        this.aiModule = aiModule;
        this.resultParser = resultParser;
    }

    @Override
    public AnalysisResult analyze(TextContext context) {
        var response = aiModule.analyze(context);
        return resultParser.parse(response);
    }
}
