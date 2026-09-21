package io.github.alioukara.sfs.antivirus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpScannerImplTest {

    private static final long MAX_SIZE = 1_000;
    private static final int MAX_CONCURRENT = 4;
    private static final int READ_TIMEOUT_MILLIS = 1_000;

    private static final byte[] CONTENT = "hello".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"Status\":\"OK\"}");
    private final AtomicReference<String> receivedApiKey = new AtomicReference<>();
    private final AtomicReference<String> receivedContentLength = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicInteger serverDelayMillis = new AtomicInteger(0);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/scan", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        receivedApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
        receivedContentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));

        try (InputStream in = exchange.getRequestBody()) {
            receivedBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            receivedBody.set(null);
        }

        if (serverDelayMillis.get() > 0) {
            try {
                Thread.sleep(serverDelayMillis.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        byte[] payload = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status.get(), payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private HttpScannerImpl scanner() {
        return scanner("");
    }

    private HttpScannerImpl scanner(String apiKey) {
        return new HttpScannerImpl(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "/v2/scan",
                apiKey,
                "X-API-Key",
                1_000,
                READ_TIMEOUT_MILLIS,
                MAX_SIZE,
                MAX_CONCURRENT,
                new ObjectMapper());
    }

    private static InputStream content() {
        return new ByteArrayInputStream(CONTENT);
    }

    @Test
    void scan_devraitRendreClean_quandReponse200() {
        status.set(200);

        assertThat(scanner().scan(content()).verdict()).isEqualTo(Verdict.CLEAN);
    }

    @Test
    void scan_devraitRendreInfected_quandReponse406() {
        status.set(406);
        responseBody.set("{\"Status\":\"NOK\",\"Description\":\"Eicar-Test-Signature\"}");

        ScanResult result = scanner().scan(content());

        assertThat(result.verdict()).isEqualTo(Verdict.INFECTED);
        assertThat(result.signature()).isEqualTo("Eicar-Test-Signature");
    }

    /** The exact body ajilaag/clamav-rest:0.6.6 returns, read from the image. */
    @Test
    void scan_devraitRendreInfected_quandReponse406EstUnTableau() {
        status.set(406);
        responseBody.set(
                "[{\"Status\":\"FOUND\",\"Description\":\"Eicar-Test-Signature\",\"FileName\":\"eicar.txt\"}]");

        ScanResult result = scanner().scan(content());

        assertThat(result.verdict()).isEqualTo(Verdict.INFECTED);
        assertThat(result.signature()).isEqualTo("Eicar-Test-Signature");
    }

    @Test
    void scan_devraitRendreInfected_quandReponse406EstUnTableauVide() {
        status.set(406);
        responseBody.set("[]");

        ScanResult result = scanner().scan(content());

        assertThat(result.verdict()).isEqualTo(Verdict.INFECTED);
        assertThat(result.signature()).isNotBlank();
    }

    @Test
    void scan_devraitRendreInfected_quandReponse406SansDescription() {
        status.set(406);
        responseBody.set("{\"Status\":\"NOK\"}");

        ScanResult result = scanner().scan(content());

        assertThat(result.verdict()).isEqualTo(Verdict.INFECTED);
        assertThat(result.signature()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(ints = {412, 413})
    void scan_devraitRendreInconclusive_quandLeScannerNAPasPuAnalyser(int code) {
        status.set(code);
        responseBody.set("{\"Status\":\"NOK\"}");

        ScanResult result = scanner().scan(content());

        assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(result.reason()).contains(String.valueOf(code));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 500, 503})
    void scan_devraitLever_quandErreurDeTransport(int code) {
        status.set(code);

        assertThatThrownBy(() -> scanner().scan(content()))
                .isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void scan_devraitLever_quandScannerInjoignable() {
        HttpScannerImpl unreachable = scanner();
        server.stop(0);

        assertThatThrownBy(() -> unreachable.scan(content()))
                .isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void scan_devraitLever_quandDelaiDepasse() {
        serverDelayMillis.set(READ_TIMEOUT_MILLIS * 4);

        assertThatThrownBy(() -> scanner().scan(content()))
                .isInstanceOf(ScannerUnavailableException.class);
    }

    /** The clause of the contract: never CLEAN on content not analysed in full. */
    @Test
    void scan_devraitRendreInconclusive_quandContenuDepasseLaLimite() {
        status.set(200);
        byte[] oversized = new byte[(int) MAX_SIZE + 1];

        ScanResult result = scanner().scan(new ByteArrayInputStream(oversized));

        assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(result.reason()).contains(String.valueOf(MAX_SIZE));
    }

    @Test
    void scan_devraitAccepter_quandContenuTientPileDansLaLimite() {
        status.set(200);
        byte[] exact = new byte[(int) MAX_SIZE];

        assertThat(scanner().scan(new ByteArrayInputStream(exact)).verdict()).isEqualTo(Verdict.CLEAN);
    }

    /** No Content-Length means the file was streamed, not measured in memory first. */
    @Test
    void scan_devraitTransmettreEnChunked_quandTailleInconnue() {
        status.set(200);

        scanner().scan(content());

        assertThat(receivedContentLength.get()).isNull();
    }

    @Test
    void scan_devraitEnvoyerLeFichierDansLeChampFile_quandRequeteConstruite() {
        status.set(200);

        scanner().scan(content());

        assertThat(receivedBody.get()).contains("name=\"file\"").contains("hello");
    }

    @Test
    void scan_devraitEnvoyerLaCle_quandElleEstConfiguree() {
        status.set(200);

        scanner("secret-key").scan(content());

        assertThat(receivedApiKey.get()).isEqualTo("secret-key");
    }

    @Test
    void scan_devraitNEnvoyerAucuneCle_quandElleEstVide() {
        status.set(200);

        scanner().scan(content());

        assertThat(receivedApiKey.get()).isNull();
    }

    @Test
    void constructeur_devraitLever_quandPermisNonPositif() {
        assertThatThrownBy(() -> new HttpScannerImpl(
                "http://127.0.0.1:1", "/v2/scan", "", "X-API-Key", 1_000, 1_000, MAX_SIZE, 0,
                new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructeur_devraitLever_quandTailleMaximaleNonPositive() {
        assertThatThrownBy(() -> new HttpScannerImpl(
                "http://127.0.0.1:1", "/v2/scan", "", "X-API-Key", 1_000, 1_000, 0, MAX_CONCURRENT,
                new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
