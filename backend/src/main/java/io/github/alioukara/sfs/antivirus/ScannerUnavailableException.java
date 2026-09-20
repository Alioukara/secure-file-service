package io.github.alioukara.sfs.antivirus;

/** Transport failure, never a verdict: it says nothing about the file. */
public class ScannerUnavailableException extends RuntimeException {

    public ScannerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
