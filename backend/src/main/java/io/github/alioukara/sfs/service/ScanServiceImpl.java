package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.antivirus.AntivirusScanner;
import io.github.alioukara.sfs.antivirus.ScanResult;
import io.github.alioukara.sfs.antivirus.ScannerUnavailableException;
import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;
import io.github.alioukara.sfs.repository.StoredFileRepository;
import io.github.alioukara.sfs.storage.FileStorage;
import io.github.alioukara.sfs.storage.StorageZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
public class ScanServiceImpl implements ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanServiceImpl.class);

    private final StoredFileRepository repository;
    private final FileStorage storage;
    private final AntivirusScanner scanner;
    private final TransactionTemplate transactions;
    private final Semaphore permits;

    public ScanServiceImpl(StoredFileRepository repository,
                           FileStorage storage,
                           AntivirusScanner scanner,
                           TransactionTemplate transactions,
                           Semaphore scanPermits) {
        this.repository = repository;
        this.storage = storage;
        this.scanner = scanner;
        this.transactions = transactions;
        this.permits = scanPermits;
    }

    @Override
    public void scan(UUID fileId) {
        if (!permits.tryAcquire()) {
            log.debug("No scan permit free, {} stays queued for the sweep", fileId);
            return;
        }
        try {
            claimLease(fileId).ifPresent(lease -> applyVerdict(fileId, lease, produceVerdict(fileId)));
        } finally {
            permits.release();
        }
    }

    /**
     * Phase 1, short transaction. Returns empty when the file is no longer
     * PENDING: the sweep took it first, which is an expected race and not a
     * failure.
     */
    private Optional<UUID> claimLease(UUID fileId) {
        return Optional.ofNullable(transactions.execute(status -> {
            StoredFile file = repository.findById(fileId).orElse(null);
            if (file == null || file.getStatus() != FileStatus.PENDING) {
                log.debug("Skipping {}: no longer queued", fileId);
                return null;
            }
            file.startScanning();
            return repository.save(file).getLeaseToken();
        }));
    }

    /**
     * Phase 2, outside any transaction: the scan may take minutes and must not
     * hold a database connection.
     */
    private ScanOutcome produceVerdict(UUID fileId) {
        if (alreadyPromoted(fileId)) {
            log.info("Reconciling {}: content is already servable, a previous scan concluded clean", fileId);
            return ScanOutcome.verdict(ScanResult.clean());
        }
        try (InputStream content = storage.retrieve(StorageZone.QUARANTINE, fileId)) {
            return ScanOutcome.verdict(scanner.scan(content));
        } catch (ScannerUnavailableException e) {
            return ScanOutcome.transportFailure(e.getMessage());
        } catch (Exception e) {
            return ScanOutcome.transportFailure(e.getMessage());
        }
    }

    /**
     * A crash between the move and the commit leaves the content servable while
     * the row still reads SCANNING. Rescanning would look for it in quarantine,
     * where it no longer is.
     */
    private boolean alreadyPromoted(UUID fileId) {
        return !storage.exists(StorageZone.QUARANTINE, fileId)
                && storage.exists(StorageZone.SERVABLE, fileId);
    }

    /**
     * Phase 3, short transaction, conditioned on the lease: an expired lease may
     * have been reclaimed by the sweep and handed to another scan, whose verdict
     * is the one that counts.
     */
    private void applyVerdict(UUID fileId, UUID lease, ScanOutcome outcome) {
        transactions.executeWithoutResult(status -> {
            StoredFile file = repository.findById(fileId).orElse(null);
            if (file == null || file.getStatus() != FileStatus.SCANNING
                    || !lease.equals(file.getLeaseToken())) {
                log.warn("Dropping a late verdict for {}: the lease is no longer held", fileId);
                return;
            }
            record(file, outcome);
            repository.save(file);
        });
    }

    private void record(StoredFile file, ScanOutcome outcome) {
        if (outcome.transportFailure() != null) {
            file.markScanFailed(outcome.transportFailure());
            return;
        }
        ScanResult result = outcome.result();
        switch (result.verdict()) {
            case CLEAN -> {
                storage.promote(file.getId());
                file.markClean();
            }
            case INFECTED -> file.markInfected();
            case INCONCLUSIVE -> file.markUnscannable(result.reason());
        }
    }

    private record ScanOutcome(ScanResult result, String transportFailure) {

        static ScanOutcome verdict(ScanResult result) {
            return new ScanOutcome(result, null);
        }

        static ScanOutcome transportFailure(String reason) {
            return new ScanOutcome(null, reason == null ? "scanner unavailable" : reason);
        }
    }
}
