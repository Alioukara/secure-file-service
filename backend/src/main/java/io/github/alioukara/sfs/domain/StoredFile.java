package io.github.alioukara.sfs.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stored_file")
public class StoredFile {

    public static final int MAX_SCAN_ATTEMPTS = 3;
    public static final int MAX_LEASE_EXPIRIES = 2;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "original_filename", nullable = false, length = 255, updatable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 255, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64, updatable = false)
    private String checksum;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FileStatus status;

    @Column(name = "scan_attempts", nullable = false)
    private int scanAttempts;

    @Column(name = "lease_expiries", nullable = false)
    private int leaseExpiries;

    @Column(name = "scan_started_at")
    private Instant scanStartedAt;

    /**
     * Identifies the scan run that owns the file. A late verdict from a
     * superseded run must not be applied: the update is conditioned on this
     * value, which changes at every {@link #startScanning()}.
     */
    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected StoredFile() {
        // JPA
    }

    private StoredFile(String originalFilename, String contentType, long sizeBytes, String checksum) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.status = FileStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * The id is generated here, not by the database: the file is streamed to
     * quarantine under that name before the row is ever committed.
     */
    public static StoredFile pending(String originalFilename, String contentType, long sizeBytes, String checksum) {
        return new StoredFile(originalFilename, contentType, sizeBytes, checksum);
    }

    public void startScanning() {
        transitionTo(FileStatus.SCANNING);
        this.scanStartedAt = Instant.now();
        this.leaseToken = UUID.randomUUID();
    }

    public void markClean() {
        transitionTo(FileStatus.CLEAN);
        this.failureReason = null;
        releaseLease();
    }

    public void markInfected() {
        transitionTo(FileStatus.INFECTED);
        releaseLease();
    }

    /**
     * Transport failure: the scanner was reached and failed. The file is not
     * implicated, so the state stays recoverable until the automatic budget is
     * spent.
     *
     * <p>The target is computed before mutating, so a call from an invalid
     * state throws without having consumed an attempt.
     */
    public void markScanFailed(String reason) {
        FileStatus target = scanAttempts + 1 < MAX_SCAN_ATTEMPTS
                ? FileStatus.SCAN_FAILED
                : FileStatus.SCAN_FAILED_EXHAUSTED;
        transitionTo(target);
        this.scanAttempts++;
        this.failureReason = reason;
        releaseLease();
    }

    /** The file itself cannot be analysed. Definitive on the first occurrence. */
    public void markUnscannable(String reason) {
        transitionTo(FileStatus.UNSCANNABLE);
        this.failureReason = reason;
        releaseLease();
    }

    public void requeueAfterBackoff() {
        requireCurrent(FileStatus.SCAN_FAILED, FileStatus.PENDING);
        transitionTo(FileStatus.PENDING);
    }

    /**
     * No verdict was ever returned. Not counted as a scan attempt — a redeploy
     * is not the file's fault — but bounded all the same, otherwise a file that
     * kills the scanner would consume a permit forever.
     */
    public void expireLease() {
        FileStatus target = leaseExpiries + 1 < MAX_LEASE_EXPIRIES
                ? FileStatus.PENDING
                : FileStatus.SCAN_FAILED_EXHAUSTED;
        requireCurrent(FileStatus.SCANNING, target);
        transitionTo(target);
        this.leaseExpiries++;
        releaseLease();
    }

    public void requestRescan() {
        requireCurrent(FileStatus.SCAN_FAILED_EXHAUSTED, FileStatus.PENDING);
        transitionTo(FileStatus.PENDING);
        this.scanAttempts = 0;
        this.leaseExpiries = 0;
        this.failureReason = null;
    }

    private void transitionTo(FileStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(status, target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    /**
     * PENDING is the only status with several inbound transitions (T8, T9, T11),
     * so the target alone does not identify which one is being applied. These
     * callers must state their source, otherwise a rescan would be accepted on a
     * file that is merely awaiting its automatic retry — and would silently
     * reset its counters.
     */
    private void requireCurrent(FileStatus expected, FileStatus intendedTarget) {
        if (status != expected) {
            throw new IllegalStateTransitionException(status, intendedTarget);
        }
    }

    private void releaseLease() {
        this.scanStartedAt = null;
        this.leaseToken = null;
    }

    public boolean isDownloadable() {
        return status.isDownloadable();
    }

    public UUID getId() {
        return id;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksum() {
        return checksum;
    }

    public FileStatus getStatus() {
        return status;
    }

    public int getScanAttempts() {
        return scanAttempts;
    }

    public int getLeaseExpiries() {
        return leaseExpiries;
    }

    public Instant getScanStartedAt() {
        return scanStartedAt;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
