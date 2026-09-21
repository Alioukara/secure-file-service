package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.domain.StoredFile;

import java.io.InputStream;
import java.util.UUID;

public interface FileService {

    StoredFile upload(String originalFilename, String contentType, long sizeBytes, InputStream content);

    /**
     * @throws StoredFileNotFoundException   unknown id
     * @throws FileNotDownloadableException  anything but CLEAN
     */
    DownloadableFile download(UUID fileId);
}
