package io.github.alioukara.sfs.antivirus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubScannerImplTest {

    private static final byte[] MARKER = StubScannerImpl.EICAR.getBytes(StandardCharsets.US_ASCII);

    private final StubScannerImpl scanner = new StubScannerImpl();

    /** Forces short reads so a marker longer than the chunk necessarily straddles two of them. */
    private static InputStream chunked(byte[] data, int chunkSize) {
        return new FilterInputStream(new ByteArrayInputStream(data)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, chunkSize));
            }
        };
    }

    private static byte[] payloadWithMarkerAt(int offset, int totalLength) {
        byte[] payload = new byte[totalLength];
        java.util.Arrays.fill(payload, (byte) 'a');
        System.arraycopy(MARKER, 0, payload, offset, MARKER.length);
        return payload;
    }

    @Test
    void scan_devraitRendreClean_quandContenuSain() {
        InputStream content = new ByteArrayInputStream("a harmless report".getBytes(StandardCharsets.UTF_8));

        ScanResult result = scanner.scan(content);

        assertThat(result.verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(result.signature()).isNull();
    }

    @Test
    void scan_devraitRendreClean_quandContenuVide() {
        ScanResult result = scanner.scan(new ByteArrayInputStream(new byte[0]));

        assertThat(result.verdict()).isEqualTo(Verdict.CLEAN);
    }

    @Test
    void scan_devraitDetecter_quandMarqueurSeulEtEntier() {
        ScanResult result = scanner.scan(new ByteArrayInputStream(MARKER));

        assertThat(result.verdict()).isEqualTo(Verdict.INFECTED);
        assertThat(result.signature()).isEqualTo(StubScannerImpl.EICAR_SIGNATURE);
    }

    @Test
    void scan_devraitDetecter_quandMarqueurCoupeEnDeuxEntreDeuxLectures() {
        int offset = 20;
        int chunkSize = 40;
        byte[] payload = payloadWithMarkerAt(offset, 200);

        assertThat(offset).isLessThan(chunkSize);
        assertThat(offset + MARKER.length).isGreaterThan(chunkSize);

        ScanResult result = scanner.scan(chunked(payload, chunkSize));

        assertThat(result.verdict()).isEqualTo(Verdict.INFECTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 13, 67, 68, 69, 128})
    void scan_devraitDetecter_quelleQueSoitLaTailleDesLectures(int chunkSize) {
        byte[] payload = payloadWithMarkerAt(137, 400);

        ScanResult result = scanner.scan(chunked(payload, chunkSize));

        assertThat(result.verdict()).as("chunk de %d octets", chunkSize).isEqualTo(Verdict.INFECTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 67, 500, 8191, 8192, 8193, 16384})
    void scan_devraitDetecter_quelleQueSoitLaPositionDuMarqueur(int offset) {
        byte[] payload = payloadWithMarkerAt(offset, offset + MARKER.length + 100);

        ScanResult result = scanner.scan(chunked(payload, 3));

        assertThat(result.verdict()).as("marqueur a l'offset %d", offset).isEqualTo(Verdict.INFECTED);
    }

    @Test
    void scan_devraitRendreClean_quandMarqueurIncomplet() {
        byte[] truncated = new byte[MARKER.length - 1];
        System.arraycopy(MARKER, 0, truncated, 0, truncated.length);

        ScanResult result = scanner.scan(chunked(truncated, 5));

        assertThat(result.verdict()).isEqualTo(Verdict.CLEAN);
    }

    @Test
    void scan_devraitRendreInconclusive_quandContenuDepasseLaLimite() {
        StubScannerImpl limited = new StubScannerImpl(50, 4);
        byte[] payload = payloadWithMarkerAt(0, 200);

        ScanResult result = limited.scan(new ByteArrayInputStream(payload));

        assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(result.reason()).contains("not analysed in full");
    }

    @Test
    void scan_devraitLever_quandLectureImpossible() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("connection reset");
            }
        };

        assertThatThrownBy(() -> scanner.scan(broken))
                .isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void limites_devraientEtreDeclareesParLeScanner_quandConstruitAvecDesValeurs() {
        StubScannerImpl configured = new StubScannerImpl(1024, 2);

        assertThat(configured.maxScannableSize()).isEqualTo(1024);
        assertThat(configured.maxConcurrentScans()).isEqualTo(2);
    }
}
