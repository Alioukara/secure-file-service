package io.github.alioukara.sfs.repository;

import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the real target database, which H2 cannot stand in for: Liquibase
 * makes the schema portable, not the queries. SKIP LOCKED in particular does not
 * exist before MySQL 8, and the recovery sweep depends on it.
 *
 * <p>Off by default so the suite stays green without Docker. Enable with
 * {@code mvn test -Dsfs.mysql=true} once the sfs-mysql container is up.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("dev")
@EnabledIfSystemProperty(named = "sfs.mysql", matches = "true")
class StoredFileRepositoryMySqlTest {

    @Autowired
    private StoredFileRepository repository;

    @Autowired
    private EntityManager entityManager;

    private StoredFile persisted(long sizeBytes) {
        StoredFile file = StoredFile.pending(UUID.randomUUID(), "report.pdf", "application/pdf",
                sizeBytes, "d41d8cd98f00b204e9800998ecf8427e");
        return repository.saveAndFlush(file);
    }

    @Test
    void schema_devraitValiderContreLeChangelog_quandContexteDemarre() {
        assertThat(repository.count()).isNotNegative();
    }

    @Test
    void entite_devraitFaireUnAllerRetourComplet_quandPersistee() {
        StoredFile saved = persisted(1024);
        saved.startScanning();
        repository.saveAndFlush(saved);
        entityManager.clear();

        StoredFile reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.getLeaseToken()).isEqualTo(saved.getLeaseToken());
        assertThat(reloaded.getStatus()).isEqualTo(FileStatus.SCANNING);
        assertThat(reloaded.getScanStartedAt()).isNotNull();
    }

    @Test
    void sumSizeByStatusIn_devraitSommer_quandLignesPresentes() {
        persisted(100);
        persisted(250);

        assertThat(repository.sumSizeByStatusIn(EnumSet.of(FileStatus.PENDING))).isEqualTo(350);
    }

    @Test
    void skipLocked_devraitEtreSupporte_quandBalayageSimule() {
        persisted(100);

        Object result = entityManager
                .createNativeQuery("select id from stored_file where status = 'PENDING' "
                        + "limit 1 for update skip locked")
                .getResultList()
                .stream()
                .findFirst()
                .orElse(null);

        assertThat(result).isNotNull();
    }
}
