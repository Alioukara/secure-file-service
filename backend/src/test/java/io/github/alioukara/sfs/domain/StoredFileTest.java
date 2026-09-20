package io.github.alioukara.sfs.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredFileTest {

    private static StoredFile pending() {
        return StoredFile.pending("report.pdf", "application/pdf", 1024L, "d41d8cd98f00b204e9800998ecf8427e");
    }

    private static StoredFile scanning() {
        StoredFile file = pending();
        file.startScanning();
        return file;
    }

    private static StoredFile scanFailed() {
        StoredFile file = scanning();
        file.markScanFailed("connection refused");
        return file;
    }

    private static StoredFile exhausted() {
        StoredFile file = pending();
        for (int i = 0; i < StoredFile.MAX_SCAN_ATTEMPTS; i++) {
            file.startScanning();
            file.markScanFailed("connection refused");
            // Au dernier tour, markScanFailed part directement en
            // SCAN_FAILED_EXHAUSTED : il n'y a plus rien a remettre en file.
            if (file.getStatus() == FileStatus.SCAN_FAILED) {
                file.requeueAfterBackoff();
            }
        }
        return file;
    }

    private static StoredFile clean() {
        StoredFile file = scanning();
        file.markClean();
        return file;
    }

    private static StoredFile infected() {
        StoredFile file = scanning();
        file.markInfected();
        return file;
    }

    private static StoredFile unscannable() {
        StoredFile file = scanning();
        file.markUnscannable("encrypted archive");
        return file;
    }

    @Test
    void pending_devraitPartirDePending_quandCree() {
        StoredFile file = pending();

        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(file.getScanAttempts()).isZero();
        assertThat(file.getLeaseExpiries()).isZero();
        assertThat(file.getId()).isNotNull();
        assertThat(file.getScanStartedAt()).isNull();
        assertThat(file.getLeaseToken()).isNull();
    }

    @Test
    void pending_devraitConserverLesMetadonnees_quandCree() {
        StoredFile file = pending();

        assertThat(file.getOriginalFilename()).isEqualTo("report.pdf");
        assertThat(file.getContentType()).isEqualTo("application/pdf");
        assertThat(file.getSizeBytes()).isEqualTo(1024L);
        assertThat(file.getChecksum()).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
    }

    @Test
    void startScanning_devraitPoserUnBail_quandPending() {
        StoredFile file = pending();

        file.startScanning();

        assertThat(file.getStatus()).isEqualTo(FileStatus.SCANNING);
        assertThat(file.getScanStartedAt()).isNotNull();
        assertThat(file.getLeaseToken()).isNotNull();
    }

    @Test
    void startScanning_devraitRenouvelerLeJeton_quandNouveauScan() {
        StoredFile file = scanning();
        UUID first = file.getLeaseToken();
        file.markScanFailed("timeout");
        file.requeueAfterBackoff();

        file.startScanning();

        assertThat(file.getLeaseToken()).isNotNull().isNotEqualTo(first);
    }

    @Test
    void startScanning_devraitLever_quandDejaScanning() {
        StoredFile file = scanning();

        assertThatThrownBy(file::startScanning)
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void startScanning_devraitLever_quandTerminal() {
        assertThatThrownBy(() -> clean().startScanning()).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> infected().startScanning()).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> unscannable().startScanning()).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void markClean_devraitLibererLeBail_quandScanning() {
        StoredFile file = scanning();

        file.markClean();

        assertThat(file.getStatus()).isEqualTo(FileStatus.CLEAN);
        assertThat(file.isDownloadable()).isTrue();
        assertThat(file.getLeaseToken()).isNull();
        assertThat(file.getScanStartedAt()).isNull();
    }

    @Test
    void markClean_devraitLever_quandPending() {
        assertThatThrownBy(() -> pending().markClean())
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void markInfected_devraitRendreNonTelechargeable_quandScanning() {
        StoredFile file = scanning();

        file.markInfected();

        assertThat(file.getStatus()).isEqualTo(FileStatus.INFECTED);
        assertThat(file.isDownloadable()).isFalse();
    }

    @Test
    void markInfected_devraitLever_quandPending() {
        assertThatThrownBy(() -> pending().markInfected())
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void markScanFailed_devraitResterRelancable_quandBudgetNonEpuise() {
        StoredFile file = scanning();

        file.markScanFailed("connection refused");

        assertThat(file.getStatus()).isEqualTo(FileStatus.SCAN_FAILED);
        assertThat(file.getScanAttempts()).isOne();
        assertThat(file.getFailureReason()).isEqualTo("connection refused");
    }

    @Test
    void markScanFailed_devraitEpuiser_quandTroisiemeTentative() {
        StoredFile file = exhausted();

        assertThat(file.getStatus()).isEqualTo(FileStatus.SCAN_FAILED_EXHAUSTED);
        assertThat(file.getScanAttempts()).isEqualTo(StoredFile.MAX_SCAN_ATTEMPTS);
    }

    @Test
    void markScanFailed_devraitNePasConsommerDeTentative_quandEtatInvalide() {
        StoredFile file = pending();

        assertThatThrownBy(() -> file.markScanFailed("timeout"))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(file.getScanAttempts()).isZero();
        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
    }

    @Test
    void markUnscannable_devraitEtreDefinitifDesLaPremiereFois_quandScanning() {
        StoredFile file = scanning();

        file.markUnscannable("Heuristics.Limits.Exceeded.MaxFileSize");

        assertThat(file.getStatus()).isEqualTo(FileStatus.UNSCANNABLE);
        assertThat(file.getScanAttempts()).isZero();
        assertThat(file.getFailureReason()).isEqualTo("Heuristics.Limits.Exceeded.MaxFileSize");
    }

    @Test
    void markUnscannable_devraitLever_quandPending() {
        assertThatThrownBy(() -> pending().markUnscannable("unreadable"))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void requeueAfterBackoff_devraitRemettreEnFile_quandScanFailed() {
        StoredFile file = scanFailed();

        file.requeueAfterBackoff();

        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(file.getScanAttempts()).isOne();
    }

    @Test
    void requeueAfterBackoff_devraitLever_quandTerminal() {
        assertThatThrownBy(() -> clean().requeueAfterBackoff())
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void expireLease_devraitRemettreEnFile_quandBudgetNonEpuise() {
        StoredFile file = scanning();

        file.expireLease();

        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(file.getLeaseExpiries()).isOne();
        assertThat(file.getScanAttempts()).isZero();
        assertThat(file.getLeaseToken()).isNull();
    }

    @Test
    void expireLease_devraitEpuiser_quandDeuxiemeExpiration() {
        StoredFile file = scanning();
        file.expireLease();
        file.startScanning();

        file.expireLease();

        assertThat(file.getStatus()).isEqualTo(FileStatus.SCAN_FAILED_EXHAUSTED);
        assertThat(file.getLeaseExpiries()).isEqualTo(StoredFile.MAX_LEASE_EXPIRIES);
    }

    @Test
    void expireLease_devraitNePasConsommerDExpiration_quandEtatInvalide() {
        StoredFile file = pending();

        assertThatThrownBy(file::expireLease)
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(file.getLeaseExpiries()).isZero();
    }

    @Test
    void requestRescan_devraitRemettreLesCompteursAZero_quandEpuise() {
        StoredFile file = exhausted();

        file.requestRescan();

        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(file.getScanAttempts()).isZero();
        assertThat(file.getLeaseExpiries()).isZero();
        assertThat(file.getFailureReason()).isNull();
    }

    @Test
    void requestRescan_devraitPermettreUnNouveauCycleComplet_quandEpuise() {
        StoredFile file = exhausted();
        file.requestRescan();

        assertThatCode(() -> {
            file.startScanning();
            file.markClean();
        }).doesNotThrowAnyException();

        assertThat(file.isDownloadable()).isTrue();
    }

    @Test
    void requestRescan_devraitLever_quandUnscannable() {
        assertThatThrownBy(() -> unscannable().requestRescan())
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void requestRescan_devraitLever_quandScanFailedRelancable() {
        assertThatThrownBy(() -> scanFailed().requestRescan())
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void isDownloadable_devraitEtreVrai_quandCleanUniquement() {
        assertThat(pending().isDownloadable()).isFalse();
        assertThat(scanning().isDownloadable()).isFalse();
        assertThat(scanFailed().isDownloadable()).isFalse();
        assertThat(exhausted().isDownloadable()).isFalse();
        assertThat(infected().isDownloadable()).isFalse();
        assertThat(unscannable().isDownloadable()).isFalse();
        assertThat(clean().isDownloadable()).isTrue();
    }
}
