package io.github.alioukara.sfs.service;

import java.util.UUID;

public interface ScanService {

    /** Does nothing if no permit is free: the file stays PENDING for the sweep. */
    void scan(UUID fileId);
}
