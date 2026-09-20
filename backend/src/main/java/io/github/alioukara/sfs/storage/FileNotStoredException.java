package io.github.alioukara.sfs.storage;

import java.util.UUID;

public class FileNotStoredException extends StorageException {

    public FileNotStoredException(StorageZone zone, UUID key) {
        super("No content for key " + key + " in zone " + zone);
    }
}
