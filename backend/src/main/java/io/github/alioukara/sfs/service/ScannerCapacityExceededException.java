package io.github.alioukara.sfs.service;

public class ScannerCapacityExceededException extends RuntimeException {

    private final long sizeBytes;
    private final long maxScannableSize;

    public ScannerCapacityExceededException(long sizeBytes, long maxScannableSize) {
        super("File of " + sizeBytes + " bytes exceeds the active scanner limit of "
                + maxScannableSize + " bytes");
        this.sizeBytes = sizeBytes;
        this.maxScannableSize = maxScannableSize;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getMaxScannableSize() {
        return maxScannableSize;
    }
}
