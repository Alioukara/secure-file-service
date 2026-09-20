package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.domain.StoredFile;

import java.io.InputStream;

public interface FileService {

    StoredFile upload(String originalFilename, String contentType, long sizeBytes, InputStream content);
}
