package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.antivirus.AntivirusScanner;
import io.github.alioukara.sfs.antivirus.ScanResult;
import io.github.alioukara.sfs.antivirus.ScannerUnavailableException;
import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;
import io.github.alioukara.sfs.repository.StoredFileRepository;
import io.github.alioukara.sfs.storage.FileStorage;
import io.github.alioukara.sfs.storage.StorageZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanServiceImplTest {

    @Mock
    private StoredFileRepository repository;

    @Mock
    private FileStorage storage;

    @Mock
    private AntivirusScanner scanner;

    @Mock
    private TransactionTemplate transactions;

    private Semaphore permits;
    private ScanServiceImpl service;
    private StoredFile file;

    @BeforeEach
    void setUp() {
        permits = new Semaphore(1);
        service = new ScanServiceImpl(repository, storage, scanner, transactions, permits);
        file = StoredFile.pending(UUID.randomUUID(), "report.pdf", "application/pdf", 5L, "abc");
    }

    @SuppressWarnings("unchecked")
    private void givenPhaseOneRunsInline() {
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
    }

    @SuppressWarnings("unchecked")
    private void givenPhaseThreeRunsInline() {
        doAnswer(call -> {
            ((Consumer<TransactionStatus>) call.getArgument(0)).accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    private void givenTransactionsRunInline() {
        givenPhaseOneRunsInline();
        givenPhaseThreeRunsInline();
    }

    private void givenFileIsFound() {
        when(repository.findById(file.getId())).thenReturn(Optional.of(file));
        when(repository.save(any(StoredFile.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void givenContentIsInQuarantine() {
        when(storage.exists(StorageZone.QUARANTINE, file.getId())).thenReturn(true);
        when(storage.retrieve(StorageZone.QUARANTINE, file.getId())).thenReturn(content());
    }

    private static InputStream content() {
        return new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void scan_devraitPromouvoirPuisMarquerClean_quandVerdictPropre() {
        givenTransactionsRunInline();
        givenFileIsFound();
        givenContentIsInQuarantine();
        when(scanner.scan(any())).thenReturn(ScanResult.clean());

        service.scan(file.getId());

        verify(storage).promote(file.getId());
        assertThat(file.getStatus()).isEqualTo(FileStatus.CLEAN);
        assertThat(file.isDownloadable()).isTrue();
    }

    @Test
    void scan_devraitMarquerInfected_quandSignatureDetectee() {
        givenTransactionsRunInline();
        givenFileIsFound();
        givenContentIsInQuarantine();
        when(scanner.scan(any())).thenReturn(ScanResult.infected("Eicar-Test-Signature"));

        service.scan(file.getId());

        assertThat(file.getStatus()).isEqualTo(FileStatus.INFECTED);
        verify(storage, never()).promote(any());
    }

    @Test
    void scan_devraitMarquerUnscannable_quandVerdictNonConcluant() {
        givenTransactionsRunInline();
        givenFileIsFound();
        givenContentIsInQuarantine();
        when(scanner.scan(any())).thenReturn(ScanResult.inconclusive("encrypted archive"));

        service.scan(file.getId());

        assertThat(file.getStatus()).isEqualTo(FileStatus.UNSCANNABLE);
        assertThat(file.getFailureReason()).isEqualTo("encrypted archive");
        verify(storage, never()).promote(any());
    }

    @Test
    void scan_devraitMarquerScanFailed_quandAntivirusInjoignable() {
        givenTransactionsRunInline();
        givenFileIsFound();
        givenContentIsInQuarantine();
        when(scanner.scan(any())).thenThrow(new ScannerUnavailableException("connection refused", null));

        service.scan(file.getId());

        assertThat(file.getStatus()).isEqualTo(FileStatus.SCAN_FAILED);
        assertThat(file.getScanAttempts()).isOne();
        verify(storage, never()).promote(any());
    }

    @Test
    void scan_devraitConclureClean_quandContenuDejaEnZoneServable() {
        givenTransactionsRunInline();
        givenFileIsFound();
        when(storage.exists(StorageZone.QUARANTINE, file.getId())).thenReturn(false);
        when(storage.exists(StorageZone.SERVABLE, file.getId())).thenReturn(true);

        service.scan(file.getId());

        assertThat(file.getStatus()).isEqualTo(FileStatus.CLEAN);
        verify(scanner, never()).scan(any());
        verify(storage, never()).retrieve(any(), any());
    }

    @Test
    void scan_devraitLaisserEnPending_quandAucunPermisLibre() throws Exception {
        permits.acquire();

        service.scan(file.getId());

        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        verify(transactions, never()).execute(any());
        verify(scanner, never()).scan(any());
    }

    @Test
    void scan_devraitRelacherLePermis_quandScanTermine() {
        givenTransactionsRunInline();
        givenFileIsFound();
        givenContentIsInQuarantine();
        when(scanner.scan(any())).thenReturn(ScanResult.clean());

        service.scan(file.getId());

        assertThat(permits.availablePermits()).isOne();
    }

    @Test
    void scan_devraitRelacherLePermis_quandScanEchoue() {
        givenPhaseOneRunsInline();
        when(repository.findById(file.getId())).thenThrow(new IllegalStateException("database down"));

        try {
            service.scan(file.getId());
        } catch (IllegalStateException expected) {
            // le permis doit revenir meme sur ce chemin
        }

        assertThat(permits.availablePermits()).isOne();
    }

    @Test
    void scan_devraitAbandonner_quandFichierPlusEnPending() {
        givenPhaseOneRunsInline();
        file.startScanning();
        when(repository.findById(file.getId())).thenReturn(Optional.of(file));

        service.scan(file.getId());

        verify(scanner, never()).scan(any());
        verify(repository, never()).save(any());
    }

    @Test
    void scan_devraitJeterLeVerdict_quandBailRepris() {
        givenTransactionsRunInline();
        givenFileIsFound();
        givenContentIsInQuarantine();
        when(scanner.scan(any())).thenAnswer(call -> {
            // Pendant la phase 2, le balayage reprend le bail expire et un autre
            // worker relance un scan : le jeton n'est plus celui de la phase 1.
            file.expireLease();
            file.startScanning();
            return ScanResult.clean();
        });

        service.scan(file.getId());

        assertThat(file.getStatus()).isEqualTo(FileStatus.SCANNING);
        assertThat(file.getLeaseExpiries()).isOne();
        verify(storage, never()).promote(any());
    }
}
