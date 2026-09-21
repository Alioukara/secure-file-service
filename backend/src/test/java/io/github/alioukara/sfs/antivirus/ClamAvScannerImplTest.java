package io.github.alioukara.sfs.antivirus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClamAvScannerImplTest {

    private static final byte[] PAYLOAD = "hello world".getBytes(StandardCharsets.UTF_8);

    private FakeClamd clamd;

    @AfterEach
    void tearDown() throws IOException {
        if (clamd != null) {
            clamd.close();
        }
    }

    private ClamAvScannerImpl scannerTalkingTo(FakeClamd server) {
        return new ClamAvScannerImpl("127.0.0.1", server.port(), 2000, 2000, 128L * 1024 * 1024, 8);
    }

    private ScanResult scan(String reply) throws Exception {
        clamd = FakeClamd.replying(reply, false);
        return scannerTalkingTo(clamd).scan(new ByteArrayInputStream(PAYLOAD));
    }

    @Test
    void scan_devraitRendreClean_quandStreamOk() throws Exception {
        assertThat(scan("stream: OK").verdict()).isEqualTo(Verdict.CLEAN);
    }

    @Test
    void scan_devraitRendreInfected_quandSignatureTrouvee() throws Exception {
        ScanResult result = scan("stream: Eicar-Test-Signature FOUND");

        assertThat(result.verdict()).isEqualTo(Verdict.INFECTED);
        assertThat(result.signature()).isEqualTo("Eicar-Test-Signature");
    }

    /**
     * clamd reports an exceeded limit as a signature name. Read naively this is
     * an infection, which would move the file to a terminal INFECTED state while
     * it was simply never analysed in full.
     */
    @Test
    void scan_devraitRendreInconclusive_quandLimiteDepassee() throws Exception {
        ScanResult result = scan("stream: Heuristics.Limits.Exceeded.MaxFileSize FOUND");

        assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(result.reason()).contains("Heuristics.Limits.Exceeded");
    }

    @Test
    void scan_devraitRendreInconclusive_quandErreurDeScan() throws Exception {
        assertThat(scan("stream: Can't allocate memory ERROR").verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void scan_devraitRendreInconclusive_quandLimiteDeFluxDepassee() throws Exception {
        assertThat(scan("INSTREAM size limit exceeded").verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void scan_devraitLever_quandReponseIncomprehensible() throws Exception {
        clamd = FakeClamd.replying("something entirely different", false);

        assertThatThrownBy(() -> scannerTalkingTo(clamd).scan(new ByteArrayInputStream(PAYLOAD)))
                .isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void scan_devraitLever_quandServeurInjoignable() {
        ClamAvScannerImpl scanner = new ClamAvScannerImpl("127.0.0.1", 1, 500, 500, 128L * 1024 * 1024, 8);

        assertThatThrownBy(() -> scanner.scan(new ByteArrayInputStream(PAYLOAD)))
                .isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void scan_devraitLever_quandServeurNeRepondJamais() throws Exception {
        clamd = FakeClamd.silent();

        assertThatThrownBy(() -> scannerTalkingTo(clamd).scan(new ByteArrayInputStream(PAYLOAD)))
                .isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void scan_devraitEmettreUneTrameInstreamValide_quandContenuEnvoye() throws Exception {
        clamd = FakeClamd.replying("stream: OK", false);

        scannerTalkingTo(clamd).scan(new ByteArrayInputStream(PAYLOAD));

        byte[] frame = clamd.received();
        assertThat(new String(frame, 0, 10, StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");

        DataInputStream body = new DataInputStream(
                new ByteArrayInputStream(frame, 10, frame.length - 10));
        assertThat(body.readInt()).as("prefixe de taille du premier chunk").isEqualTo(PAYLOAD.length);

        byte[] chunk = new byte[PAYLOAD.length];
        body.readFully(chunk);
        assertThat(chunk).isEqualTo(PAYLOAD);

        assertThat(body.readInt()).as("terminateur").isZero();
    }

    @Test
    void scan_devraitRendreLeVerdict_quandClamdRepondAvantLaFinDuTransfert() throws Exception {
        clamd = FakeClamd.replying("stream: Eicar-Test-Signature FOUND", true);
        byte[] large = new byte[512 * 1024];

        ScanResult result = scannerTalkingTo(clamd).scan(new ByteArrayInputStream(large));

        assertThat(result.verdict()).isEqualTo(Verdict.INFECTED);
    }

    @Test
    void scan_devraitLever_quandClamdCesseDeConsommerLeFlux() throws Exception {
        clamd = FakeClamd.stalling();
        // A blocking socket write has no timeout in Java. Only closing the socket
        // from another thread unblocks it — without that, this thread would never
        // come back and would keep its scan permit for good.
        ClamAvScannerImpl scanner = new ClamAvScannerImpl("127.0.0.1", clamd.port(), 2000, 1000, 128L * 1024 * 1024, 8);

        assertThatThrownBy(() -> scanner.scan(new ByteArrayInputStream(new byte[64 * 1024 * 1024])))
                .isInstanceOf(ScannerUnavailableException.class);
    }

@Test
    void scan_devraitRendreInconclusive_quandContenuDepasseSaPropreLimite() throws Exception {
        clamd = FakeClamd.replying("stream: OK", false);
        // This fake answers OK whatever it receives, exactly like a clamd whose
        // AlertExceedsMax was lost with a bad volume mount. The verdict must come
        // from our own count, never from its answer.
        ClamAvScannerImpl scanner = new ClamAvScannerImpl("127.0.0.1", clamd.port(), 2000, 2000, 10L, 8);

        ScanResult result = scanner.scan(new ByteArrayInputStream(new byte[64]));

        assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(result.reason()).contains("not analysed in full");
    }

    @Test
    void scan_devraitAccepter_quandContenuTientPileDansLaLimite() throws Exception {
        clamd = FakeClamd.replying("stream: OK", false);
        ClamAvScannerImpl scanner = new ClamAvScannerImpl("127.0.0.1", clamd.port(), 2000, 2000, 64L, 8);

        assertThat(scanner.scan(new ByteArrayInputStream(new byte[64])).verdict())
                .isEqualTo(Verdict.CLEAN);
    }

    @Test
    void constructeur_devraitLever_quandConfigurationNonViable() {
        assertThatThrownBy(() -> new ClamAvScannerImpl("h", 3310, 1, 1, 100L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-concurrent-scans");

        assertThatThrownBy(() -> new ClamAvScannerImpl("h", 3310, 1, 1, 0L, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-scannable-size");
    }

        /** Answers a canned reply, optionally before the client has finished sending. */
    private static final class FakeClamd implements AutoCloseable {

        private final ServerSocket server;
        private final Thread thread;
        private final AtomicReference<byte[]> received = new AtomicReference<>(new byte[0]);
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean stalling;

        private FakeClamd(String reply, boolean replyEarly, boolean silent) throws IOException {
            this.server = new ServerSocket(0);
            this.thread = new Thread(() -> serve(reply, replyEarly, silent));
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static FakeClamd replying(String reply, boolean replyEarly) throws IOException {
            return new FakeClamd(reply, replyEarly, false);
        }

        static FakeClamd silent() throws IOException {
            return new FakeClamd(null, false, true);
        }

        /**
         * Accepts the connection, then never reads a byte nor answers. It stalls
         * far longer than the client budget on purpose: the test can only pass if
         * the watchdog unblocks the write, never because this fake gave up.
         */
        static FakeClamd stalling() throws IOException {
            FakeClamd fake = new FakeClamd(null, false, true);
            fake.stalling = true;
            return fake;
        }

        private void serve(String reply, boolean replyEarly, boolean silent) {
            try (Socket socket = server.accept();
                 DataInputStream in = new DataInputStream(socket.getInputStream());
                 OutputStream out = socket.getOutputStream()) {

                ByteArrayOutputStream seen = new ByteArrayOutputStream();

                if (silent) {
                    // Reads nothing and answers nothing: the client must give up
                    // on its own budget rather than hang forever.
                    Thread.sleep(stalling ? 30000 : 5000);
                    return;
                }

                // The command, up to its null terminator.
                int b;
                while ((b = in.read()) != -1) {
                    seen.write(b);
                    if (b == 0) {
                        break;
                    }
                }

                // Then length-prefixed chunks, until the zero-length one. Waiting
                // for EOF instead would deadlock: the client holds its socket open
                // while waiting for this very answer.
                while (true) {
                    byte[] length = new byte[4];
                    in.readFully(length);
                    seen.write(length);
                    int size = ByteBuffer.wrap(length).getInt();
                    if (size == 0) {
                        break;
                    }
                    byte[] chunk = new byte[size];
                    in.readFully(chunk);
                    seen.write(chunk);
                    if (replyEarly && seen.size() > 64 * 1024) {
                        break;
                    }
                }
                received.set(seen.toByteArray());

                out.write(reply.getBytes(StandardCharsets.US_ASCII));
                out.write(0);
                out.flush();
            } catch (Exception ignored) {
                // The client closing first is a normal end for this fake.
            } finally {
                done.countDown();
            }
        }

        int port() {
            return server.getLocalPort();
        }

        byte[] received() throws InterruptedException {
            done.await(5, TimeUnit.SECONDS);
            return received.get();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
