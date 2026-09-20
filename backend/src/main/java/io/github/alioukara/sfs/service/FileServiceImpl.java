package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.antivirus.AntivirusScanner;
import io.github.alioukara.sfs.domain.StoredFile;
import io.github.alioukara.sfs.repository.StoredFileRepository;
import io.github.alioukara.sfs.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private final StoredFileRepository repository;
    private final FileStorage storage;
    private final AntivirusScanner scanner;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final long automaticQuotaBytes;
    private final long onActionQuotaBytes;

    public FileServiceImpl(StoredFileRepository repository,
                           FileStorage storage,
                           AntivirusScanner scanner,
                           ApplicationEventPublisher events,
                           TransactionTemplate transactions,
                           @Value("${sfs.storage.quota.automatic}") DataSize automaticQuota,
                           @Value("${sfs.storage.quota.on-action}") DataSize onActionQuota) {
        this.repository = repository;
        this.storage = storage;
        this.scanner = scanner;
        this.events = events;
        this.transactions = transactions;
        this.automaticQuotaBytes = automaticQuota.toBytes();
        this.onActionQuotaBytes = onActionQuota.toBytes();
    }

    @Override
    public StoredFile upload(String originalFilename, String contentType, long sizeBytes, InputStream content) {
        requireScannerCapacity(sizeBytes);
        requireQuota(QuotaAccount.AUTOMATIC, automaticQuotaBytes, sizeBytes);
        requireQuota(QuotaAccount.ON_ACTION, onActionQuotaBytes, sizeBytes);

        UUID key = UUID.randomUUID();
        MessageDigest digest = newDigest();
        long written = storage.store(key, new DigestInputStream(content, digest), sizeBytes);
        String checksum = HexFormat.of().formatHex(digest.digest());

        StoredFile file = StoredFile.pending(key, originalFilename, contentType, written, checksum);
        return persistAndAnnounce(file);
    }

    /**
     * The commit happens inside execute(), so a failure at commit time surfaces
     * here rather than after the method returns. A database rollback does not
     * roll back the file system: discarding the content is a manual
     * compensation, and it leaves the quotas consistent with what is on disk.
     */
    private StoredFile persistAndAnnounce(StoredFile file) {
        try {
            return transactions.execute(status -> {
                StoredFile saved = repository.save(file);
                events.publishEvent(new FileUploadedEvent(saved.getId()));
                return saved;
            });
        } catch (RuntimeException e) {
            log.error("Discarding {} from quarantine: its row could not be written", file.getId(), e);
            discardQuietly(file.getId(), e);
            throw e;
        }
    }

    /** The compensation must not replace the failure that triggered it. */
    private void discardQuietly(UUID key, RuntimeException cause) {
        try {
            storage.discardFromQuarantine(key);
        } catch (RuntimeException discardFailure) {
            cause.addSuppressed(discardFailure);
        }
    }

    private void requireScannerCapacity(long sizeBytes) {
        long max = scanner.maxScannableSize();
        if (sizeBytes > max) {
            throw new ScannerCapacityExceededException(sizeBytes, max);
        }
    }

    /**
     * Refuses to go over the limit rather than noticing afterwards: the incoming
     * size counts before the file is written.
     *
     * <p>Approximate under concurrency: other uploads commit between this check
     * and the write. Assumed — a reservation would cost a lock on the hot path.
     */
    private void requireQuota(QuotaAccount account, long limitBytes, long incomingBytes) {
        long used = repository.sumSizeByStatusIn(account.statuses());
        if (used + incomingBytes > limitBytes) {
            throw new QuotaExceededException(account, used, limitBytes);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is required but unavailable", e);
        }
    }
}
