package io.github.alioukara.sfs.storage;

import java.io.InputStream;
import java.util.UUID;

/**
 * {@link #promote(UUID)} is the only way into {@link StorageZone#SERVABLE}.
 * Adding a write method that takes a zone would turn the invariant back into a
 * conditional one.
 */
public interface FileStorage {

    /** @param sizeHint expected byte count, negative if unknown. Returns the bytes written. */
    long store(UUID key, InputStream content, long sizeHint);

    InputStream retrieve(StorageZone zone, UUID key);

    /** Atomic. */
    void promote(UUID key);

    boolean exists(StorageZone zone, UUID key);

    /**
     * Compensation for a row that could not be written: the content would
     * otherwise occupy disk that no quota counts, since both are summed from the
     * database. Deliberately narrow — nothing can ever delete from SERVABLE.
     */
    void discardFromQuarantine(UUID key);
}
