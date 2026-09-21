package io.github.alioukara.sfs.service;

import java.util.UUID;

public class StoredFileNotFoundException extends RuntimeException {

    public StoredFileNotFoundException(UUID fileId) {
        super("No file with id " + fileId);
    }
}
