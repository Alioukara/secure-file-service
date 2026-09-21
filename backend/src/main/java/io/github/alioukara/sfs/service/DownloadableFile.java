package io.github.alioukara.sfs.service;

import java.io.InputStream;

/** The stream is open and belongs to the caller, who closes it once written out. */
public record DownloadableFile(String originalFilename, String contentType, long sizeBytes,
                               InputStream content) {
}
