package io.github.alioukara.sfs.api;

import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;

import java.time.Instant;
import java.util.UUID;

public record FileSummary(UUID fileId,
                          String originalFilename,
                          String contentType,
                          long sizeBytes,
                          FileStatus status,
                          Instant createdAt) {

    public static FileSummary from(StoredFile file) {
        return new FileSummary(
                file.getId(),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSizeBytes(),
                file.getStatus(),
                file.getCreatedAt());
    }
}
