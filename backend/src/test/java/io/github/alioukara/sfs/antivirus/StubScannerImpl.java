package io.github.alioukara.sfs.antivirus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class StubScannerImpl implements AntivirusScanner {

    /**
     * Assembled at runtime so the full marker never appears in this source file:
     * an antivirus scanning the repository, the IDE or the CI would quarantine it.
     */
    static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$"
            + "EICAR-STANDARD-ANTIVIRUS-"
            + "TEST-FILE!$H+H*";

    static final String EICAR_SIGNATURE = "Eicar-Test-Signature";

    private static final byte[] MARKER = EICAR.getBytes(StandardCharsets.US_ASCII);
    private static final int BUFFER_SIZE = 8192;

    private final long maxScannableSize;
    private final int maxConcurrentScans;

    public StubScannerImpl() {
        this(Long.MAX_VALUE, 4);
    }

    public StubScannerImpl(long maxScannableSize, int maxConcurrentScans) {
        this.maxScannableSize = maxScannableSize;
        this.maxConcurrentScans = maxConcurrentScans;
    }

    @Override
    public ScanResult scan(InputStream content) {
        int overlap = MARKER.length - 1;
        byte[] window = new byte[overlap + BUFFER_SIZE];
        byte[] chunk = new byte[BUFFER_SIZE];
        int carried = 0;
        long total = 0;

        try {
            int read;
            while ((read = content.read(chunk)) != -1) {
                total += read;
                if (total > maxScannableSize) {
                    return ScanResult.inconclusive(
                            "Content exceeds " + maxScannableSize + " bytes, not analysed in full");
                }

                System.arraycopy(chunk, 0, window, carried, read);
                int windowLength = carried + read;

                if (indexOfMarker(window, windowLength) >= 0) {
                    return ScanResult.infected(EICAR_SIGNATURE);
                }

                // Carry the last MARKER.length - 1 bytes over: a marker straddling
                // two reads is only visible if the tail of the previous one stays.
                carried = Math.min(overlap, windowLength);
                System.arraycopy(window, windowLength - carried, window, 0, carried);
            }
        } catch (IOException e) {
            throw new ScannerUnavailableException("Failed to read content while scanning", e);
        }

        return ScanResult.clean();
    }

    @Override
    public long maxScannableSize() {
        return maxScannableSize;
    }

    @Override
    public int maxConcurrentScans() {
        return maxConcurrentScans;
    }

    private static int indexOfMarker(byte[] window, int windowLength) {
        int last = windowLength - MARKER.length;
        for (int start = 0; start <= last; start++) {
            int offset = 0;
            while (offset < MARKER.length && window[start + offset] == MARKER[offset]) {
                offset++;
            }
            if (offset == MARKER.length) {
                return start;
            }
        }
        return -1;
    }
}
