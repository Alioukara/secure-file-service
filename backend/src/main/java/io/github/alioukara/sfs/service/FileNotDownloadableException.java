package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.domain.FileStatus;

/** Carries the status so the API layer can decide how to say no, not whether to. */
public class FileNotDownloadableException extends RuntimeException {

    private final FileStatus status;

    public FileNotDownloadableException(FileStatus status) {
        super("File is not downloadable in status " + status);
        this.status = status;
    }

    public FileStatus getStatus() {
        return status;
    }
}
