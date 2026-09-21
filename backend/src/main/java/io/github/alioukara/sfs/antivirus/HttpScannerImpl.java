package io.github.alioukara.sfs.antivirus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "sfs.antivirus.implementation", havingValue = "http")
public class HttpScannerImpl implements AntivirusScanner {

    private static final Logger log = LoggerFactory.getLogger(HttpScannerImpl.class);

    private static final int INFECTED = 406;
    private static final int UNPROCESSABLE = 412;
    private static final int TOO_LARGE = 413;

    private static final String SIGNATURE_FIELD = "Description";
    private static final String UNNAMED_SIGNATURE = "unnamed signature";

    /** The verdict body is a short JSON document, never the file itself. */
    private static final int MAX_BODY_BYTES = 8192;

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String scanPath;
    private final long maxScannableSize;
    private final int maxConcurrentScans;

    public HttpScannerImpl(
            @Value("${sfs.antivirus.http.base-url}") String baseUrl,
            @Value("${sfs.antivirus.http.scan-path}") String scanPath,
            @Value("${sfs.antivirus.http.api-key}") String apiKey,
            @Value("${sfs.antivirus.http.api-key-header}") String apiKeyHeader,
            @Value("${sfs.antivirus.http.connect-timeout-millis}") int connectTimeoutMillis,
            @Value("${sfs.antivirus.http.read-timeout-millis}") int readTimeoutMillis,
            @Value("${sfs.antivirus.http.max-scannable-size-bytes}") long maxScannableSize,
            @Value("${sfs.antivirus.http.max-concurrent-scans}") int maxConcurrentScans,
            ObjectMapper mapper) {

        if (maxConcurrentScans <= 0) {
            throw new IllegalArgumentException(
                    "sfs.antivirus.http.max-concurrent-scans must be positive, was " + maxConcurrentScans);
        }
        if (maxScannableSize <= 0) {
            throw new IllegalArgumentException(
                    "sfs.antivirus.http.max-scannable-size-bytes must be positive, was " + maxScannableSize);
        }

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                        .build());
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));

        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (!apiKey.isBlank()) {
            builder.defaultHeader(apiKeyHeader, apiKey);
        }

        this.client = builder.build();
        this.mapper = mapper;
        this.scanPath = scanPath;
        this.maxScannableSize = maxScannableSize;
        this.maxConcurrentScans = maxConcurrentScans;
    }

    @Override
    public ScanResult scan(InputStream content) {
        BoundedStream bounded = new BoundedStream(content, maxScannableSize);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new StreamedPart(bounded));

        try {
            return client.post()
                    .uri(scanPath)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .exchange((request, response) -> interpret(response));
        } catch (RuntimeException e) {
            if (bounded.exceeded()) {
                return ScanResult.inconclusive(
                        "Content exceeds " + maxScannableSize + " bytes, not analysed in full");
            }
            throw e instanceof ScannerUnavailableException unavailable
                    ? unavailable
                    : new ScannerUnavailableException("HTTP scanner is unreachable", e);
        }
    }

    /**
     * An infected file comes back as 406, which is a client error: left to the
     * default handling it would surface as a transport failure, and the file
     * would be retried instead of being refused.
     */
    private ScanResult interpret(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();

        if (status.is2xxSuccessful()) {
            return ScanResult.clean();
        }
        if (status.value() == INFECTED) {
            return ScanResult.infected(signatureOf(readBody(response)));
        }
        if (status.value() == UNPROCESSABLE || status.value() == TOO_LARGE) {
            return ScanResult.inconclusive("Scanner answered " + status.value() + ": " + readBody(response));
        }
        throw new ScannerUnavailableException("HTTP scanner answered " + status.value(), null);
    }

    /**
     * clamav-rest answers with an array of per file verdicts, other APIs with a
     * single object. Both shapes are read rather than assuming either one.
     */
    private String signatureOf(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            String description = (root.isArray() ? root.path(0) : root).path(SIGNATURE_FIELD).asText();
            if (!description.isBlank()) {
                return description;
            }
        } catch (IOException e) {
            log.warn("Could not read the signature out of an infected verdict", e);
        }
        return UNNAMED_SIGNATURE;
    }

    private static String readBody(ClientHttpResponse response) throws IOException {
        try (InputStream in = response.getBody()) {
            return new String(in.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8).trim();
        }
    }

    @Override
    public long maxScannableSize() {
        return maxScannableSize;
    }

    @Override
    public int maxConcurrentScans() {
        return maxConcurrentScans;
    }

    /**
     * Nothing bounds the request on the remote side, so the count is kept here:
     * an API that silently truncates and answers OK would otherwise produce a
     * CLEAN verdict on content nobody analysed in full.
     */
    private static final class BoundedStream extends InputStream {

        private final InputStream delegate;
        private final long limit;
        private long read;
        private boolean exceeded;

        private BoundedStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        private boolean exceeded() {
            return exceeded;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws IOException {
            read += bytes;
            if (read > limit) {
                exceeded = true;
                throw new IOException("Content exceeds " + limit + " bytes");
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /**
     * An unknown length forces a chunked request, which is what keeps the file
     * streaming: asked for its size, Spring would read the whole part into
     * memory to measure it.
     */
    private static final class StreamedPart extends InputStreamResource {

        private StreamedPart(InputStream content) {
            super(content);
        }

        @Override
        public String getFilename() {
            return "content";
        }

        @Override
        public long contentLength() {
            return -1;
        }
    }
}
