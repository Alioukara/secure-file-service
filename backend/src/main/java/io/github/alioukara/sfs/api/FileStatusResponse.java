package io.github.alioukara.sfs.api;

import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;

import java.time.Instant;
import java.util.UUID;

/**
 * The reason matters as much as the status: UNSCANNABLE covers an exceeded
 * limit, an encrypted archive and unreadable content, and SCAN_FAILED_EXHAUSTED
 * covers several transport causes.
 */
public record FileStatusResponse(UUID fileId,
                                 FileStatus status,
                                 String reason,
                                 int scanAttempts,
                                 int leaseExpiries,
                                 Instant createdAt,
                                 Instant updatedAt) {

    public static FileStatusResponse from(StoredFile file) {
        return new FileStatusResponse(
                file.getId(),
                file.getStatus(),
                file.getFailureReason(),
                file.getScanAttempts(),
                file.getLeaseExpiries(),
                file.getCreatedAt(),
                file.getUpdatedAt());
    }
}
