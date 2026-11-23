package com.aireader.infrastructure.security;

import com.aireader.application.port.GenerationQuota;
import com.aireader.application.port.IllustrationRepository;
import com.aireader.domain.Illustration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Component
public class PostgresGenerationQuota implements GenerationQuota {
    private final RequestQuotas quotas;
    private final MobileAccessProperties properties;
    private final IllustrationRepository illustrations;
    private final Clock clock;

    PostgresGenerationQuota(RequestQuotas quotas, MobileAccessProperties properties,
            IllustrationRepository illustrations, Clock clock) {
        this.quotas = quotas;
        this.properties = properties;
        this.illustrations = illustrations;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void reserveAnalysis(String scope) {
        reserve("analysis", scope, properties.guestAnalysesPerDay(), properties.totalAnalysesPerDay());
    }

    @Override
    @Transactional
    public boolean claimIllustration(Illustration job) {
        if (!illustrations.claim(job)) {
            return false;
        }
        reserve("illustration", job.scope(), properties.guestIllustrationsPerDay(), properties.totalIllustrationsPerDay());
        return true;
    }

    private void reserve(String operation, String scope, int guestLimit, int totalLimit) {
        var now = clock.instant();
        quotas.reserve(operation, scope, now, ChronoUnit.DAYS, guestLimit, "GUEST_QUOTA_EXCEEDED");
        quotas.reserve(operation, RequestQuotas.GLOBAL, now, ChronoUnit.DAYS, totalLimit, "GENERATION_BUDGET_EXHAUSTED");
    }
}
