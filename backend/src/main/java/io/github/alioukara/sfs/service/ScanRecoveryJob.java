package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.domain.StoredFile;
import io.github.alioukara.sfs.repository.StoredFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * What makes the event an accelerator rather than the mechanism. Three cases
 * would stay stuck without it: a PENDING that found no free permit, a SCANNING
 * whose worker died, and a SCAN_FAILED waiting out its backoff.
 */
@Component
public class ScanRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(ScanRecoveryJob.class);

    private final StoredFileRepository repository;
    private final ScanDispatcher dispatcher;
    private final TransactionTemplate transactions;
    private final Duration leaseTimeout;
    private final Duration retryBackoff;
    private final int batchSize;

    public ScanRecoveryJob(StoredFileRepository repository,
                           ScanDispatcher dispatcher,
                           TransactionTemplate transactions,
                           @Value("${sfs.scan.lease-timeout}") Duration leaseTimeout,
                           @Value("${sfs.scan.retry-backoff}") Duration retryBackoff,
                           @Value("${sfs.scan.sweep-batch-size}") int batchSize) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.transactions = transactions;
        this.leaseTimeout = leaseTimeout;
        this.retryBackoff = retryBackoff;
        this.batchSize = batchSize;
    }

    /**
     * fixedDelay and not a cron: a tick only starts once the previous one is
     * done, so two sweeps never overlap on the same rows.
     */
    @Scheduled(fixedDelayString = "${sfs.scan.sweep-interval-millis}")
    public void sweep() {
        reclaim();
        dispatch();
    }

    /**
     * Reclaiming comes first: a file whose lease just expired goes back to
     * PENDING and is dispatched by the same tick instead of waiting for the next.
     */
    private void reclaim() {
        Instant now = Instant.now();

        int expired = apply(repository.lockExpiredLeases(now.minus(leaseTimeout), batchSize),
                StoredFile::expireLease);
        int retried = apply(repository.lockRetryable(now.minus(retryBackoff), batchSize),
                StoredFile::requeueAfterBackoff);

        if (expired > 0 || retried > 0) {
            log.info("Sweep reclaimed {} expired leases and {} failed scans", expired, retried);
        }
    }

    private void dispatch() {
        List<UUID> queued = transactions.execute(status -> repository.lockQueued(batchSize));
        if (queued == null || queued.isEmpty()) {
            return;
        }
        log.debug("Sweep dispatching {} queued files", queued.size());
        queued.forEach(dispatcher::submit);
    }

    /**
     * One transaction per file: a row that changed between the lock and the
     * transition — another instance, a verdict landing — must not take the whole
     * batch down with it.
     */
    private int apply(List<UUID> ids, Consumer<StoredFile> transition) {
        int applied = 0;
        for (UUID id : ids) {
            Boolean done = transactions.execute(status -> {
                StoredFile file = repository.findById(id).orElse(null);
                if (file == null) {
                    return false;
                }
                try {
                    transition.accept(file);
                    repository.save(file);
                    return true;
                } catch (RuntimeException e) {
                    log.debug("Skipping {}: it moved on before the sweep reached it", id);
                    return false;
                }
            });
            if (Boolean.TRUE.equals(done)) {
                applied++;
            }
        }
        return applied;
    }
}
