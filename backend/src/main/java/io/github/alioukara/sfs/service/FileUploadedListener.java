package io.github.alioukara.sfs.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Its own class on purpose: called from the publishing bean, {@code @Async} would
 * not go through the proxy and would be ignored.
 *
 * <p>Only an accelerator. The sweep is what guarantees a scan eventually happens,
 * so nothing here may assume the event is the single trigger.
 */
@Component
public class FileUploadedListener {

    private static final Logger log = LoggerFactory.getLogger(FileUploadedListener.class);

    private final ScanService scanService;

    public FileUploadedListener(ScanService scanService) {
        this.scanService = scanService;
    }

    /** Swallows nothing silently: on an async void method, an exception would vanish into the executor. */
    @Async
    @TransactionalEventListener
    public void onFileUploaded(FileUploadedEvent event) {
        try {
            scanService.scan(event.fileId());
        } catch (RuntimeException e) {
            log.error("Scan of {} failed, leaving it to the sweep", event.fileId(), e);
        }
    }
}
