package io.github.alioukara.sfs.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Its own class so {@code @Async} goes through the proxy. Without it the sweep
 * would scan in its own thread, and a single 100MB file would hold the whole
 * tick — recovery after an incident would crawl at one file per period.
 */
@Component
public class ScanDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ScanDispatcher.class);

    private final ScanService scanService;

    public ScanDispatcher(ScanService scanService) {
        this.scanService = scanService;
    }

    @Async
    public void submit(UUID fileId) {
        try {
            scanService.scan(fileId);
        } catch (RuntimeException e) {
            log.error("Scan of {} failed, leaving it to the next sweep", fileId, e);
        }
    }
}
