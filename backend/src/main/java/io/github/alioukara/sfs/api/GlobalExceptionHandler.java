package io.github.alioukara.sfs.api;

import io.github.alioukara.sfs.service.FileNotDownloadableException;
import io.github.alioukara.sfs.service.QuotaAccount;
import io.github.alioukara.sfs.service.RescanNotAllowedException;
import io.github.alioukara.sfs.service.QuotaExceededException;
import io.github.alioukara.sfs.service.ScannerCapacityExceededException;
import io.github.alioukara.sfs.service.StoredFileNotFoundException;
import io.github.alioukara.sfs.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final long retryAfterSeconds;

    public GlobalExceptionHandler(@Value("${sfs.api.retry-after-seconds}") long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @ExceptionHandler(InvalidUploadException.class)
    public ProblemDetail onInvalidUpload(InvalidUploadException e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage(), "INVALID_UPLOAD");
    }

    /**
     * Raised by the container before the controller is ever reached, on the
     * infrastructure ceiling. The one below comes from the active scanner's own
     * limit: same status, two different causes.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail onContainerLimit(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE,
                "File exceeds the maximum accepted upload size",
                "UPLOAD_LIMIT_EXCEEDED");
    }

    @ExceptionHandler(ScannerCapacityExceededException.class)
    public ProblemDetail onScannerCapacity(ScannerCapacityExceededException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage(), "SCANNER_CAPACITY_EXCEEDED");
    }

    /**
     * Retry-After only on the automatic account: it drains on its own. The other
     * one never does, so advertising a delay would promise something false.
     */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ProblemDetail> onQuota(QuotaExceededException e) {
        boolean drainsOnItsOwn = e.getAccount() == QuotaAccount.AUTOMATIC;

        ProblemDetail body = problem(HttpStatus.SERVICE_UNAVAILABLE,
                drainsOnItsOwn
                        ? "Too many files awaiting a scan, try again later"
                        : "Retained files fill the quarantine, operator action is required",
                drainsOnItsOwn ? "LOAD_QUOTA_EXCEEDED" : "RETENTION_QUOTA_EXCEEDED");

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        if (drainsOnItsOwn) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        }
        return response.body(body);
    }

    @ExceptionHandler(StoredFileNotFoundException.class)
    public ProblemDetail onUnknownFile(StoredFileNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage(), "FILE_NOT_FOUND");
    }

    /**
     * Only decides how to say no; the service has already decided that we do.
     * The switch is exhaustive and has no {@code default}: an eighth status must
     * break the compilation rather than fall into a branch that serves it.
     */
    @ExceptionHandler(FileNotDownloadableException.class)
    public ResponseEntity<ProblemDetail> onNotDownloadable(FileNotDownloadableException e) {
        return switch (e.getStatus()) {
            case PENDING, SCANNING, SCAN_FAILED -> retryLater(
                    "The file is still being processed", "SCAN_IN_PROGRESS");

            case SCAN_FAILED_EXHAUSTED -> definitive(HttpStatus.CONFLICT,
                    "The scan could not complete, a rescan must be requested", "SCAN_GAVE_UP");

            case UNSCANNABLE -> definitive(HttpStatus.CONFLICT,
                    "The file cannot be analysed and will never be served", "FILE_UNSCANNABLE");

            case INFECTED -> definitive(HttpStatus.FORBIDDEN,
                    "The file was found infected", "FILE_INFECTED");

            case CLEAN -> throw new IllegalStateException(
                    "CLEAN is downloadable and cannot reach this handler");
        };
    }

    private ResponseEntity<ProblemDetail> retryLater(String detail, String reason) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(problem(HttpStatus.CONFLICT, detail, reason));
    }

    private static ResponseEntity<ProblemDetail> definitive(HttpStatus status, String detail, String reason) {
        return ResponseEntity.status(status).body(problem(status, detail, reason));
    }

    /** Says why, not just no: the current status is what the client needs. */
    @ExceptionHandler(RescanNotAllowedException.class)
    public ProblemDetail onRescanRefused(RescanNotAllowedException e) {
        return problem(HttpStatus.CONFLICT, e.getMessage(), "RESCAN_NOT_ALLOWED");
    }

    @ExceptionHandler(StorageException.class)
    public ProblemDetail onStorage(StorageException e) {
        log.error("Storage failure", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the file", "STORAGE_FAILURE");
    }

    /** Reading the uploaded part can fail on a client that disconnects mid-transfer. */
    @ExceptionHandler(IOException.class)
    public ProblemDetail onUploadRead(IOException e) {
        log.error("Could not read the uploaded part", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read the uploaded file", "UPLOAD_READ_FAILED");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String reason) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("reason", reason);
        return body;
    }
}
