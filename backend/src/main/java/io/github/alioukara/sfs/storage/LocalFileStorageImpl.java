package io.github.alioukara.sfs.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

@Component
public class LocalFileStorageImpl implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageImpl.class);

    private static final String QUARANTINE_DIRECTORY = "quarantine";
    private static final String SERVABLE_DIRECTORY = "servable";

    private static final Set<PosixFilePermission> QUARANTINE_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");

    private final Path quarantineRoot;
    private final Path servableRoot;

    public LocalFileStorageImpl(@Value("${sfs.storage.base-directory}") Path baseDirectory) {
        this.quarantineRoot = baseDirectory.resolve(QUARANTINE_DIRECTORY);
        this.servableRoot = baseDirectory.resolve(SERVABLE_DIRECTORY);
        createZones();
        requireSameFileStore();
    }

    @Override
    public long store(UUID key, InputStream content, long sizeHint) {
        Path target = pathFor(StorageZone.QUARANTINE, key);
        createParent(target);

        long written;
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            written = content.transferTo(out);
        } catch (FileAlreadyExistsException e) {
            throw new StorageException("Key already stored: " + key, e);
        } catch (IOException e) {
            deletePartial(target);
            throw new StorageException("Failed to store " + key, e);
        }

        if (sizeHint >= 0 && written != sizeHint) {
            deletePartial(target);
            throw new StorageException(
                    "Truncated transfer for " + key + ": expected " + sizeHint + " bytes, wrote " + written);
        }
        return written;
    }

    @Override
    public InputStream retrieve(StorageZone zone, UUID key) {
        Path source = pathFor(zone, key);
        try {
            return Files.newInputStream(source);
        } catch (NoSuchFileException e) {
            throw new FileNotStoredException(zone, key);
        } catch (IOException e) {
            throw new StorageException("Failed to read " + key + " from " + zone, e);
        }
    }

    @Override
    public void promote(UUID key) {
        Path source = pathFor(StorageZone.QUARANTINE, key);
        Path target = pathFor(StorageZone.SERVABLE, key);
        createParent(target);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchFileException e) {
            throw new FileNotStoredException(StorageZone.QUARANTINE, key);
        } catch (IOException e) {
            throw new StorageException("Failed to promote " + key, e);
        }
    }

    @Override
    public boolean exists(StorageZone zone, UUID key) {
        return Files.isRegularFile(pathFor(zone, key));
    }

    @Override
    public void discardFromQuarantine(UUID key) {
        Path target = pathFor(StorageZone.QUARANTINE, key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new StorageException("Failed to discard " + key + " from quarantine", e);
        }
    }

    private Path pathFor(StorageZone zone, UUID key) {
        String name = key.toString();
        Path root = zone == StorageZone.QUARANTINE ? quarantineRoot : servableRoot;
        return root.resolve(name.substring(0, 2)).resolve(name.substring(2, 4)).resolve(name);
    }

    private void createZones() {
        try {
            Files.createDirectories(quarantineRoot);
            Files.createDirectories(servableRoot);
        } catch (IOException e) {
            throw new StorageException("Could not create storage zones", e);
        }
        restrictQuarantine();
    }

    /**
     * Only the root needs restricting: a subtree under a 700 directory cannot be
     * traversed by anyone else, whatever the modes below it.
     */
    private void restrictQuarantine() {
        try {
            if (Files.getFileStore(quarantineRoot).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(quarantineRoot, QUARANTINE_PERMISSIONS);
            } else {
                log.warn("Quarantine permissions not applied: {} has no POSIX support", quarantineRoot);
            }
        } catch (IOException e) {
            throw new StorageException("Could not restrict quarantine permissions", e);
        }
    }

    /**
     * ATOMIC_MOVE fails across file stores, so a misconfiguration here would only
     * surface on the first file that turns out clean — in production.
     */
    private void requireSameFileStore() {
        try {
            if (!Files.getFileStore(quarantineRoot).equals(Files.getFileStore(servableRoot))) {
                throw new StorageException(
                        "Storage zones must share one file store, promote() would not be atomic: "
                                + quarantineRoot + " and " + servableRoot);
            }
        } catch (IOException e) {
            throw new StorageException("Could not compare storage zone file stores", e);
        }
    }

    private void createParent(Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new StorageException("Could not create directory for " + target, e);
        }
    }

    private void deletePartial(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Could not remove partial file {}", target, e);
        }
    }
}
