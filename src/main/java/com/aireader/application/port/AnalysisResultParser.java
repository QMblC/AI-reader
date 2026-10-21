package com.aireader.application.port;

import com.aireader.domain.AnalysisResult;

public interface AnalysisResultParser {
    AnalysisResult parse(String response);
}
