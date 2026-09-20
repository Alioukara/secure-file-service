package io.github.alioukara.sfs.repository;

import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ddl-auto=validate here and nowhere else: this is the only test proving the
 * Liquibase changelog and the entity agree. Without it, a mismatch would only
 * surface when the application starts.
 */
@DataJpaTest
@ActiveProfiles("test")
class StoredFileRepositoryTest {

    @Autowired
    private StoredFileRepository repository;

    private StoredFile persisted(long sizeBytes) {
        StoredFile file = StoredFile.pending(UUID.randomUUID(), "report.pdf", "application/pdf",
                sizeBytes, "d41d8cd98f00b204e9800998ecf8427e");
        return repository.saveAndFlush(file);
    }

    @Test
    void schema_devraitAccepterUneEcritureComplete_quandEntitePersistee() {
        StoredFile saved = persisted(1024);

        assertThat(repository.findById(saved.getId())).isPresent();
    }

    @Test
    void sumSizeByStatusIn_devraitRendreZero_quandAucuneLigne() {
        long sum = repository.sumSizeByStatusIn(EnumSet.of(FileStatus.PENDING));

        assertThat(sum).isZero();
    }

    @Test
    void sumSizeByStatusIn_devraitSommerLesTaillesDuCompte_quandLignesPresentes() {
        persisted(100);
        persisted(250);

        long sum = repository.sumSizeByStatusIn(EnumSet.of(FileStatus.PENDING));

        assertThat(sum).isEqualTo(350);
    }

    @Test
    void sumSizeByStatusIn_devraitIgnorerLesAutresStatuts_quandFichierScanne() {
        persisted(100);
        StoredFile infected = persisted(250);
        infected.startScanning();
        infected.markInfected();
        repository.saveAndFlush(infected);

        assertThat(repository.sumSizeByStatusIn(EnumSet.of(FileStatus.PENDING))).isEqualTo(100);
        assertThat(repository.sumSizeByStatusIn(EnumSet.of(FileStatus.INFECTED))).isEqualTo(250);
    }

    @Test
    void uuid_devraitEtreRelu_quandStockeEnTexte() {
        StoredFile saved = persisted(1024);
        saved.startScanning();
        repository.saveAndFlush(saved);

        StoredFile reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.getLeaseToken()).isEqualTo(saved.getLeaseToken());
    }
}
