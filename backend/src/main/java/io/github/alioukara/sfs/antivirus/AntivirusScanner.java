package io.github.alioukara.sfs.antivirus;

import java.io.InputStream;

public interface AntivirusScanner {

    /** Never CLEAN on content not analysed in full: past its own limit, INCONCLUSIVE. */
    ScanResult scan(InputStream content);

    long maxScannableSize();

    int maxConcurrentScans();
}
