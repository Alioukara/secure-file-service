package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;
import io.github.alioukara.sfs.repository.StoredFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanRecoveryJobTest {

    private static final int BATCH = 50;

    @Mock
    private StoredFileRepository repository;

    @Mock
    private ScanDispatcher dispatcher;

    @Mock
    private TransactionTemplate transactions;

    private ScanRecoveryJob job;

    @BeforeEach
    void setUp() {
        job = new ScanRecoveryJob(repository, dispatcher, transactions,
                Duration.ofMinutes(10), Duration.ofMinutes(1), BATCH);
    }

    @SuppressWarnings("unchecked")
    private void givenTransactionsRunInline() {
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
    }

    private void givenNothingToReclaim() {
        when(repository.lockExpiredLeases(any(), anyInt())).thenReturn(List.of());
        when(repository.lockRetryable(any(), anyInt())).thenReturn(List.of());
    }

    private void givenNothingQueued() {
        when(repository.lockQueued(anyInt())).thenReturn(List.of());
    }

    private StoredFile file() {
        return StoredFile.pending(UUID.randomUUID(), "report.pdf", "application/pdf", 5L, "abc");
    }

    private StoredFile found(StoredFile file) {
        when(repository.findById(file.getId())).thenReturn(Optional.of(file));
        when(repository.save(any(StoredFile.class))).thenAnswer(call -> call.getArgument(0));
        return file;
    }

    @Test
    void sweep_devraitDistribuerLesFichiersEnFile_quandPendingTrouves() {
        givenTransactionsRunInline();
        givenNothingToReclaim();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.lockQueued(BATCH)).thenReturn(List.of(first, second));

        job.sweep();

        verify(dispatcher).submit(first);
        verify(dispatcher).submit(second);
    }

    @Test
    void sweep_devraitReprendreLeBail_quandScanAbandonne() {
        givenTransactionsRunInline();
        givenNothingQueued();
        StoredFile stuck = file();
        stuck.startScanning();
        when(repository.lockExpiredLeases(any(), eq(BATCH))).thenReturn(List.of(stuck.getId()));
        when(repository.lockRetryable(any(), anyInt())).thenReturn(List.of());
        found(stuck);

        job.sweep();

        assertThat(stuck.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(stuck.getLeaseExpiries()).isOne();
        assertThat(stuck.getLeaseToken()).isNull();
    }

    @Test
    void sweep_devraitEpuiser_quandBailExpireUneFoisDeTrop() {
        givenTransactionsRunInline();
        givenNothingQueued();
        StoredFile stuck = file();
        stuck.startScanning();
        stuck.expireLease();
        stuck.startScanning();
        when(repository.lockExpiredLeases(any(), eq(BATCH))).thenReturn(List.of(stuck.getId()));
        when(repository.lockRetryable(any(), anyInt())).thenReturn(List.of());
        found(stuck);

        job.sweep();

        assertThat(stuck.getStatus()).isEqualTo(FileStatus.SCAN_FAILED_EXHAUSTED);
    }

    @Test
    void sweep_devraitRemettreEnFile_quandScanFailedMur() {
        givenTransactionsRunInline();
        givenNothingQueued();
        StoredFile failed = file();
        failed.startScanning();
        failed.markScanFailed("connection refused");
        when(repository.lockExpiredLeases(any(), anyInt())).thenReturn(List.of());
        when(repository.lockRetryable(any(), eq(BATCH))).thenReturn(List.of(failed.getId()));
        found(failed);

        job.sweep();

        assertThat(failed.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(failed.getScanAttempts()).isOne();
    }

    /**
     * Reclaiming runs before dispatching so a lease that just expired is picked
     * up by the same tick. The queue is read after the reclaim, which is what
     * makes that possible.
     */
    @Test
    void sweep_devraitRecupererAvantDeDistribuer_quandLesDeuxSontNecessaires() {
        givenTransactionsRunInline();
        StoredFile stuck = file();
        stuck.startScanning();
        when(repository.lockExpiredLeases(any(), anyInt())).thenReturn(List.of(stuck.getId()));
        when(repository.lockRetryable(any(), anyInt())).thenReturn(List.of());
        found(stuck);
        when(repository.lockQueued(BATCH)).thenReturn(List.of(stuck.getId()));

        job.sweep();

        assertThat(stuck.getStatus()).isEqualTo(FileStatus.PENDING);
        verify(dispatcher).submit(stuck.getId());
    }

    @Test
    void sweep_devraitPoursuivre_quandUneLigneABougeEntreTemps() {
        givenTransactionsRunInline();
        givenNothingQueued();
        StoredFile moved = file();
        // Verrouille comme expire, mais un verdict a atterri avant que le
        // balayage l'atteigne : la transition leve, sans emporter le lot.
        moved.startScanning();
        moved.markClean();
        when(repository.lockExpiredLeases(any(), anyInt())).thenReturn(List.of(moved.getId()));
        when(repository.lockRetryable(any(), anyInt())).thenReturn(List.of());
        when(repository.findById(moved.getId())).thenReturn(Optional.of(moved));

        job.sweep();

        assertThat(moved.getStatus()).isEqualTo(FileStatus.CLEAN);
        verify(repository, never()).save(any());
    }

    @Test
    void sweep_devraitNeRienFaire_quandRienATraiter() {
        givenTransactionsRunInline();
        givenNothingToReclaim();
        givenNothingQueued();

        job.sweep();

        verify(dispatcher, never()).submit(any());
        verify(repository, never()).save(any());
    }

    @Test
    void sweep_devraitBornerChaqueRequete_quandLotConfigure() {
        givenTransactionsRunInline();
        givenNothingToReclaim();
        givenNothingQueued();

        job.sweep();

        verify(repository).lockQueued(BATCH);
        verify(repository).lockExpiredLeases(any(), eq(BATCH));
        verify(repository).lockRetryable(any(), eq(BATCH));
    }
}
