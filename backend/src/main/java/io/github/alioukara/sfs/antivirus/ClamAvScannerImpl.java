package io.github.alioukara.sfs.antivirus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "sfs.antivirus.implementation", havingValue = "clamav")
public class ClamAvScannerImpl implements AntivirusScanner {

    private static final Logger log = LoggerFactory.getLogger(ClamAvScannerImpl.class);

    private static final byte[] INSTREAM_COMMAND = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TERMINATOR = {0, 0, 0, 0};
    private static final int CHUNK_SIZE = 8192;

    private static final String LIMITS_EXCEEDED = "Heuristics.Limits.Exceeded";
    private static final String FOUND = "FOUND";
    private static final String OK = "OK";
    private static final String ERROR = "ERROR";

    private final String host;
    private final int port;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final long maxScannableSize;
    private final int maxConcurrentScans;

    public ClamAvScannerImpl(
            @Value("${sfs.antivirus.clamav.host}") String host,
            @Value("${sfs.antivirus.clamav.port}") int port,
            @Value("${sfs.antivirus.clamav.connect-timeout-millis}") int connectTimeoutMillis,
            @Value("${sfs.antivirus.clamav.read-timeout-millis}") int readTimeoutMillis,
            @Value("${sfs.antivirus.clamav.max-scannable-size-bytes}") long maxScannableSize,
            @Value("${sfs.antivirus.clamav.max-concurrent-scans}") int maxConcurrentScans) {
        // Refuse to start rather than fail silently: zero permits would mean no
        // file is ever scanned, and a non positive size would refuse them all.
        if (maxConcurrentScans <= 0) {
            throw new IllegalArgumentException(
                    "sfs.antivirus.clamav.max-concurrent-scans must be positive, was " + maxConcurrentScans);
        }
        if (maxScannableSize <= 0) {
            throw new IllegalArgumentException(
                    "sfs.antivirus.clamav.max-scannable-size-bytes must be positive, was " + maxScannableSize);
        }
        this.host = host;
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxScannableSize = maxScannableSize;
        this.maxConcurrentScans = maxConcurrentScans;
    }

    @Override
    public ScanResult scan(InputStream content) {
        AtomicBoolean abandoned = new AtomicBoolean(false);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);

            Thread watchdog = startWatchdog(socket, abandoned);
            try (OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream()) {

                out.write(INSTREAM_COMMAND);
                out.flush();

                Transfer transfer = streamContent(content, out, in, abandoned);
                if (transfer == Transfer.OVERSIZED) {
                    return ScanResult.inconclusive(
                            "Content exceeds " + maxScannableSize + " bytes, not analysed in full");
                }
                if (transfer == Transfer.COMPLETE) {
                    out.write(TERMINATOR);
                    out.flush();
                }
                return interpret(readReply(in));
            } finally {
                watchdog.interrupt();
            }
        } catch (IOException e) {
            if (abandoned.get()) {
                throw new ScannerUnavailableException(
                        "ClamAV stopped consuming the stream after " + readTimeoutMillis + "ms", e);
            }
            throw new ScannerUnavailableException("ClamAV is unreachable at " + host + ":" + port, e);
        }
    }

    /**
     * setSoTimeout only bounds reads. A blocking socket write has no timeout at
     * all in Java, so a daemon that stops consuming would hold this thread — and
     * its scan permit — forever. Eight of those and the service stops scanning
     * for good.
     *
     * <p>Closing the socket from another thread is what actually unblocks a
     * blocked write. A virtual thread makes one guard per scan free.
     */
    private Thread startWatchdog(Socket socket, AtomicBoolean abandoned) {
        return Thread.ofVirtual().name("clamav-watchdog").start(() -> {
            try {
                Thread.sleep(readTimeoutMillis);
            } catch (InterruptedException e) {
                return;
            }
            abandoned.set(true);
            try {
                socket.close();
            } catch (IOException e) {
                log.warn("Could not close a stalled ClamAV socket", e);
            }
        });
    }

    private enum Transfer {
        /** Everything was sent, the terminator is still to write. */
        COMPLETE,
        /** clamd answered and hung up before the end: its verdict is already waiting. */
        ANSWERED_EARLY,
        /** More bytes than this scanner can analyse: no verdict may be trusted. */
        OVERSIZED
    }

    private Transfer streamContent(InputStream content, OutputStream out, InputStream in,
                                   AtomicBoolean abandoned) throws IOException {
        byte[] buffer = new byte[CHUNK_SIZE];
        long sent = 0;
        int read;
        while ((read = content.read(buffer)) != -1) {
            // Counted here and not left to clamd alone. Its own MaxFileSize only
            // yields a verdict when AlertExceedsMax is on in a config file we
            // mount: a wrong volume, an edited line, and clamd would answer OK on
            // content it never analysed. This makes the contract unconditional.
            sent += read;
            if (sent > maxScannableSize) {
                return Transfer.OVERSIZED;
            }
            try {
                out.write(ByteBuffer.allocate(4).putInt(read).array());
                out.write(buffer, 0, read);
                out.flush();
            } catch (IOException e) {
                if (abandoned.get()) {
                    throw e;
                }
                // Broken pipe: clamd has already answered and hung up. This is
                // what actually protects the transfer, available() below is only
                // a shortcut and guarantees nothing.
                log.debug("ClamAV closed the stream early, reading its verdict");
                return Transfer.ANSWERED_EARLY;
            }
            if (in.available() > 0) {
                return Transfer.ANSWERED_EARLY;
            }
        }
        return Transfer.COMPLETE;
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0 && b != '\n') {
            reply.write(b);
        }
        return reply.toString(StandardCharsets.US_ASCII).trim();
    }

    /**
     * Order matters: clamd reports an exceeded limit as a signature name, so
     * {@code Heuristics.Limits.Exceeded.MaxFileSize FOUND} would otherwise read
     * as an infection. The file is not infected, it was not analysed in full.
     */
    private ScanResult interpret(String reply) {
        if (reply.contains(LIMITS_EXCEEDED)) {
            return ScanResult.inconclusive(reply);
        }
        if (reply.endsWith(FOUND)) {
            return ScanResult.infected(signatureOf(reply));
        }
        if (reply.endsWith(OK)) {
            return ScanResult.clean();
        }
        if (reply.endsWith(ERROR) || reply.contains("size limit exceeded")) {
            return ScanResult.inconclusive(reply);
        }
        // An unparseable answer says nothing about the file, only that we no
        // longer understand the scanner.
        throw new ScannerUnavailableException("Unexpected ClamAV reply: " + reply, null);
    }

    private static String signatureOf(String reply) {
        int start = reply.indexOf(':');
        int end = reply.lastIndexOf(FOUND);
        String signature = start < 0 ? reply.substring(0, end) : reply.substring(start + 1, end);
        return signature.trim();
    }

    @Override
    public long maxScannableSize() {
        return maxScannableSize;
    }

    @Override
    public int maxConcurrentScans() {
        return maxConcurrentScans;
    }
}
