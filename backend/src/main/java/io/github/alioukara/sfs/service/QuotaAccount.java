package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.domain.FileStatus;

import java.util.EnumSet;
import java.util.Set;

/**
 * Split by what empties the account, not by whether the state is terminal: a
 * single account would let archived INFECTED files trigger a load 503.
 */
public enum QuotaAccount {

    AUTOMATIC(EnumSet.of(FileStatus.PENDING, FileStatus.SCANNING, FileStatus.SCAN_FAILED)),

    ON_ACTION(EnumSet.of(FileStatus.INFECTED, FileStatus.UNSCANNABLE, FileStatus.SCAN_FAILED_EXHAUSTED));

    private final Set<FileStatus> statuses;

    QuotaAccount(Set<FileStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<FileStatus> statuses() {
        return statuses;
    }
}
