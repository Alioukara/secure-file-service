package io.github.alioukara.sfs.api;

import io.github.alioukara.sfs.domain.StoredFile;
import io.github.alioukara.sfs.service.FileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final String DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_OCTET_STREAM_VALUE;

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile part) throws IOException {
        String filename = requireFilename(part);

        try (InputStream content = part.getInputStream()) {
            StoredFile stored = fileService.upload(filename, contentTypeOf(part), part.getSize(), content);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(UploadResponse.from(stored));
        }
    }

    private static String requireFilename(MultipartFile part) {
        String filename = part.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new InvalidUploadException("A file name is required");
        }
        if (part.getSize() == 0) {
            throw new InvalidUploadException("An empty file has nothing to scan");
        }
        return filename;
    }

    /**
     * A part carries no mandatory Content-Type in multipart form data. The
     * service never interprets it, so a missing one is not a reason to refuse an
     * upload: the standard fallback for unlabelled data applies.
     */
    private static String contentTypeOf(MultipartFile part) {
        String contentType = part.getContentType();
        return contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType;
    }
}
