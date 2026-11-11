package com.aireader.infrastructure.storage;

import com.aireader.application.port.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Clock;

/** Single application instance owns the local disk and the in-process generation queue. */
public final class CacheMaintenance {
    private final AnalysisCache analyses;
    private final IllustrationRepository illustrations;
    private final ImageStorage storage;
    private final Clock clock;
    public CacheMaintenance(AnalysisCache analyses, IllustrationRepository illustrations, ImageStorage storage, Clock clock) {
        this.analyses = analyses;
        this.illustrations = illustrations;
        this.storage = storage;
        this.clock = clock;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        illustrations.failInterrupted(clock.instant().plusSeconds(300));
        clean();
    }
    @Scheduled(fixedDelayString = "${cache.cleanup-interval}", initialDelayString = "${cache.cleanup-interval}")
    public void clean() {
        var now = clock.instant();
        analyses.deleteExpired(now);
        for (var job : illustrations.expired(now)) {
            try {
                storage.delete(job.id());
                illustrations.delete(job.id());
            } catch (java.io.IOException ex) {
                LoggerFactory.getLogger(getClass()).warn("Image cleanup failed; will retry");
            }
        }
        try {
            // Also covers a crash between saving a file and publishing READY, or replacing an expired key.
            for (var id : storage.ids()) if (!illustrations.contains(id)) storage.delete(id);
        } catch (java.io.IOException ex) {
            LoggerFactory.getLogger(getClass()).warn("Orphan image cleanup failed; will retry");
        }
    }
}
