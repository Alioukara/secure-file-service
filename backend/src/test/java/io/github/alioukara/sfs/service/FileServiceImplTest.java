package io.github.alioukara.sfs.service;

import io.github.alioukara.sfs.antivirus.AntivirusScanner;
import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;
import io.github.alioukara.sfs.repository.StoredFileRepository;
import io.github.alioukara.sfs.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    private static final byte[] CONTENT = "hello".getBytes(StandardCharsets.UTF_8);

    /** SHA-256 of "hello", to prove the digest comes from the transferred stream. */
    private static final String HELLO_SHA256 =
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    private static final long AUTOMATIC_QUOTA = 2_000;
    private static final long ON_ACTION_QUOTA = 5_000;

    @Mock
    private StoredFileRepository repository;

    @Mock
    private FileStorage storage;

    @Mock
    private AntivirusScanner scanner;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private TransactionTemplate transactions;

    private FileServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FileServiceImpl(repository, storage, scanner, events, transactions,
                DataSize.ofBytes(AUTOMATIC_QUOTA), DataSize.ofBytes(ON_ACTION_QUOTA));
    }

    private void givenScannerAcceptsAnySize() {
        when(scanner.maxScannableSize()).thenReturn(1_000_000L);
    }

    private void givenBothQuotasEmpty() {
        when(repository.sumSizeByStatusIn(eq(QuotaAccount.AUTOMATIC.statuses()))).thenReturn(0L);
        when(repository.sumSizeByStatusIn(eq(QuotaAccount.ON_ACTION.statuses()))).thenReturn(0L);
    }

    private void givenStorageDrainsTheStream() {
        when(storage.store(any(), any(), anyLong())).thenAnswer(call -> {
            InputStream in = call.getArgument(1);
            return (long) in.readAllBytes().length;
        });
    }

    @SuppressWarnings("unchecked")
    private void givenTransactionRunsInline() {
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
    }

    private void givenSaveReturnsItsArgument() {
        when(repository.save(any(StoredFile.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void givenEverythingPasses() {
        givenScannerAcceptsAnySize();
        givenBothQuotasEmpty();
        givenStorageDrainsTheStream();
        givenTransactionRunsInline();
        givenSaveReturnsItsArgument();
    }

    private StoredFile upload() {
        return service.upload("report.pdf", "application/pdf", CONTENT.length,
                new ByteArrayInputStream(CONTENT));
    }

    @Test
    void upload_devraitPersisterEnPending_quandToutPasse() {
        givenEverythingPasses();

        StoredFile stored = upload();

        assertThat(stored.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(stored.getOriginalFilename()).isEqualTo("report.pdf");
        verify(repository).save(any(StoredFile.class));
    }

    @Test
    void upload_devraitCalculerLeChecksumPendantLeTransfert_quandContenuLu() {
        givenEverythingPasses();

        StoredFile stored = upload();

        assertThat(stored.getChecksum()).isEqualTo(HELLO_SHA256);
    }

    @Test
    void upload_devraitStockerSousLaCleDeLEntite_quandEcrit() {
        givenEverythingPasses();
        ArgumentCaptor<UUID> key = ArgumentCaptor.forClass(UUID.class);

        StoredFile stored = upload();

        verify(storage).store(key.capture(), any(), eq((long) CONTENT.length));
        assertThat(stored.getId()).isEqualTo(key.getValue());
    }

    @Test
    void upload_devraitPublierLEvenement_quandLigneEcrite() {
        givenEverythingPasses();

        StoredFile stored = upload();

        verify(events).publishEvent(new FileUploadedEvent(stored.getId()));
    }

    @Test
    void upload_devraitLever_quandTailleDepasseLeScanner() {
        when(scanner.maxScannableSize()).thenReturn(2L);

        assertThatThrownBy(this::upload)
                .isInstanceOf(ScannerCapacityExceededException.class);

        verify(storage, never()).store(any(), any(), anyLong());
    }

    @Test
    void upload_devraitLever_quandCompteAutomatiquePlein() {
        givenScannerAcceptsAnySize();
        when(repository.sumSizeByStatusIn(eq(QuotaAccount.AUTOMATIC.statuses()))).thenReturn(AUTOMATIC_QUOTA);

        assertThatThrownBy(this::upload)
                .isInstanceOf(QuotaExceededException.class)
                .extracting(e -> ((QuotaExceededException) e).getAccount())
                .isEqualTo(QuotaAccount.AUTOMATIC);

        verify(storage, never()).store(any(), any(), anyLong());
    }

    @Test
    void upload_devraitLever_quandCompteSurActionPlein() {
        givenScannerAcceptsAnySize();
        when(repository.sumSizeByStatusIn(eq(QuotaAccount.AUTOMATIC.statuses()))).thenReturn(0L);
        when(repository.sumSizeByStatusIn(eq(QuotaAccount.ON_ACTION.statuses()))).thenReturn(ON_ACTION_QUOTA);

        assertThatThrownBy(this::upload)
                .isInstanceOf(QuotaExceededException.class)
                .extracting(e -> ((QuotaExceededException) e).getAccount())
                .isEqualTo(QuotaAccount.ON_ACTION);
    }

    @Test
    void upload_devraitLever_quandLeFichierEntrantFeraitDepasser() {
        givenScannerAcceptsAnySize();
        when(repository.sumSizeByStatusIn(eq(QuotaAccount.AUTOMATIC.statuses())))
                .thenReturn(AUTOMATIC_QUOTA - CONTENT.length + 1);

        assertThatThrownBy(this::upload).isInstanceOf(QuotaExceededException.class);

        verify(storage, never()).store(any(), any(), anyLong());
    }

    @Test
    void upload_devraitAccepter_quandLeFichierEntrantTientPileDansLeQuota() {
        givenScannerAcceptsAnySize();
        when(repository.sumSizeByStatusIn(eq(QuotaAccount.AUTOMATIC.statuses())))
                .thenReturn(AUTOMATIC_QUOTA - CONTENT.length);
        when(repository.sumSizeByStatusIn(eq(QuotaAccount.ON_ACTION.statuses()))).thenReturn(0L);
        givenStorageDrainsTheStream();
        givenTransactionRunsInline();
        givenSaveReturnsItsArgument();

        assertThat(upload().getStatus()).isEqualTo(FileStatus.PENDING);
    }

    @Test
    void upload_devraitSupprimerLeFichier_quandEcritureEnBaseEchoue() {
        givenScannerAcceptsAnySize();
        givenBothQuotasEmpty();
        givenStorageDrainsTheStream();
        // doThrow, not when(...).thenThrow: re-stubbing would call the mock and
        // trigger the answer already registered, with a null argument.
        doThrow(new IllegalStateException("database down")).when(transactions).execute(any());

        assertThatThrownBy(this::upload).isInstanceOf(IllegalStateException.class);

        verify(storage).discardFromQuarantine(any(UUID.class));
    }

    @Test
    void upload_devraitConserverLaCauseInitiale_quandLaSuppressionEchoueAussi() {
        givenScannerAcceptsAnySize();
        givenBothQuotasEmpty();
        givenStorageDrainsTheStream();
        doThrow(new IllegalStateException("database down")).when(transactions).execute(any());
        doThrow(new IllegalStateException("disk unreachable"))
                .when(storage).discardFromQuarantine(any(UUID.class));

        assertThatThrownBy(this::upload)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database down")
                .satisfies(e -> assertThat(e.getSuppressed())
                        .singleElement()
                        .extracting(Throwable::getMessage)
                        .isEqualTo("disk unreachable"));
    }

    @Test
    void upload_devraitNeRienSupprimer_quandEcritureEnBaseReussit() {
        givenEverythingPasses();

        upload();

        verify(storage, never()).discardFromQuarantine(any());
    }
}
