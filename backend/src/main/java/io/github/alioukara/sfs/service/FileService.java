package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.io.InputStream;
import java.util.Collection;
import java.util.UUID;

public interface FileService {

    StoredFile upload(String originalFilename, String contentType, long sizeBytes, InputStream content);

    /**
     * @throws StoredFileNotFoundException   unknown id
     * @throws FileNotDownloadableException  anything but CLEAN
     */
    DownloadableFile download(UUID fileId);

    /** @throws StoredFileNotFoundException unknown id */
    StoredFile status(UUID fileId);

    /**
     * @throws StoredFileNotFoundException unknown id
     * @throws RescanNotAllowedException   anything but SCAN_FAILED_EXHAUSTED
     */
    StoredFile requestRescan(UUID fileId);

    /** @param statuses null or empty lists everything */
    Page<StoredFile> list(Collection<FileStatus> statuses, Pageable pageable);
}
