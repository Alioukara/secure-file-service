package io.github.alioukara.sfs.api;

import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;

import java.util.UUID;

public record UploadResponse(UUID fileId, FileStatus status) {

    public static UploadResponse from(StoredFile file) {
        return new UploadResponse(file.getId(), file.getStatus());
    }
}
