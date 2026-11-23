package com.aireader.application.port;

import com.aireader.domain.Illustration;

public interface GenerationQuota {
    void reserveAnalysis(String scope);

    boolean claimIllustration(Illustration job);
}
