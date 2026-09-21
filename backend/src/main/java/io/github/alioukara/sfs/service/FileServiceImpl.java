package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.antivirus.AntivirusScanner;
import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;
import io.github.alioukara.sfs.repository.StoredFileRepository;
import io.github.alioukara.sfs.storage.FileStorage;
import io.github.alioukara.sfs.storage.StorageZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
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
    private final ScanDispatcher dispatcher;
    private final long automaticQuotaBytes;
    private final long onActionQuotaBytes;

    public FileServiceImpl(StoredFileRepository repository,
                           FileStorage storage,
                           AntivirusScanner scanner,
                           ApplicationEventPublisher events,
                           TransactionTemplate transactions,
                           ScanDispatcher dispatcher,
                           @Value("${sfs.storage.quota.automatic}") DataSize automaticQuota,
                           @Value("${sfs.storage.quota.on-action}") DataSize onActionQuota) {
        this.repository = repository;
        this.storage = storage;
        this.scanner = scanner;
        this.events = events;
        this.transactions = transactions;
        this.dispatcher = dispatcher;
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

    @Override
    public DownloadableFile download(UUID fileId) {
        StoredFile file = repository.findById(fileId)
                .orElseThrow(() -> new StoredFileNotFoundException(fileId));

        if (!file.isDownloadable()) {
            throw new FileNotDownloadableException(file.getStatus());
        }

        return new DownloadableFile(
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSizeBytes(),
                storage.retrieve(StorageZone.SERVABLE, fileId));
    }

    @Override
    public StoredFile status(UUID fileId) {
        return repository.findById(fileId)
                .orElseThrow(() -> new StoredFileNotFoundException(fileId));
    }

    /**
     * The relaunch happens AFTER the commit, never inside it: called from within,
     * the async thread would read the row still in SCAN_FAILED_EXHAUSTED and skip
     * it in silence. This is the same guarantee AFTER_COMMIT gives on upload.
     *
     * <p>Not a shortcut either — the sweep already dispatches through the very
     * same component. A request triggers it here instead of a timer, and the
     * sweep stays the guarantee.
     */
    @Override
    public StoredFile requestRescan(UUID fileId) {
        StoredFile requeued = transactions.execute(status -> {
            StoredFile file = repository.findById(fileId)
                    .orElseThrow(() -> new StoredFileNotFoundException(fileId));

            if (file.getStatus() != FileStatus.SCAN_FAILED_EXHAUSTED) {
                throw new RescanNotAllowedException(file.getStatus());
            }
            file.requestRescan();
            return repository.save(file);
        });

        dispatcher.submit(fileId);
        return requeued;
    }

    @Override
    public Page<StoredFile> list(Collection<FileStatus> statuses, Pageable pageable) {
        return statuses == null || statuses.isEmpty()
                ? repository.findAll(pageable)
                : repository.findByStatusIn(statuses, pageable);
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
