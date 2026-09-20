package io.github.alioukara.sfs.api;

import io.github.alioukara.sfs.service.QuotaAccount;
import io.github.alioukara.sfs.service.QuotaExceededException;
import io.github.alioukara.sfs.service.ScannerCapacityExceededException;
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
