package com.aireader.infrastructure.config;

import com.aireader.application.AnalyzeTextUseCase;
import com.aireader.application.port.AiAnalysisModule;
import com.aireader.application.port.AnalysisResultParser;
import com.aireader.application.service.AnalyzeTextService;
import com.aireader.infrastructure.analysis.JsonAnalysisResultParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AnalysisConfiguration {
    @Bean
    AnalysisResultParser analysisResultParser(ObjectMapper mapper) {
        return new JsonAnalysisResultParser(mapper);
    }

    @Bean
    AnalyzeTextUseCase analyzeTextUseCase(AiAnalysisModule aiModule, AnalysisResultParser resultParser) {
        return new AnalyzeTextService(aiModule, resultParser);
    }
}
