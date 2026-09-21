package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.domain.FileStatus;

/** Carries the current status so the API layer can say why, not just no. */
public class RescanNotAllowedException extends RuntimeException {

    private final FileStatus status;

    public RescanNotAllowedException(FileStatus status) {
        super("A rescan may only be requested on SCAN_FAILED_EXHAUSTED, was " + status);
        this.status = status;
    }

    public FileStatus getStatus() {
        return status;
    }
}
