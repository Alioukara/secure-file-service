package io.github.alioukara.sfs.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageImplTest {

    private static final byte[] CONTENT = "hello".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path baseDirectory;

    private LocalFileStorageImpl storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorageImpl(baseDirectory);
    }

    private static InputStream content() {
        return new ByteArrayInputStream(CONTENT);
    }

    private Path zoneRoot(StorageZone zone) {
        return baseDirectory.resolve(zone == StorageZone.QUARANTINE ? "quarantine" : "servable");
    }

    private List<Path> filesIn(StorageZone zone) throws IOException {
        try (Stream<Path> walk = Files.walk(zoneRoot(zone))) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void store_devraitEcrireEnQuarantaine_quandCleNeuve() throws IOException {
        UUID key = UUID.randomUUID();

        long written = storage.store(key, content(), CONTENT.length);

        assertThat(written).isEqualTo(CONTENT.length);
        assertThat(storage.exists(StorageZone.QUARANTINE, key)).isTrue();
        assertThat(filesIn(StorageZone.SERVABLE)).isEmpty();
    }

    @Test
    void store_devraitEclaterLaCle_quandElleEstEcrite() throws IOException {
        UUID key = UUID.randomUUID();
        String name = key.toString();

        storage.store(key, content(), CONTENT.length);

        Path expected = zoneRoot(StorageZone.QUARANTINE)
                .resolve(name.substring(0, 2))
                .resolve(name.substring(2, 4))
                .resolve(name);
        assertThat(filesIn(StorageZone.QUARANTINE)).containsExactly(expected);
    }

    @Test
    void store_devraitLever_quandCleDejaUtilisee() {
        UUID key = UUID.randomUUID();
        storage.store(key, content(), CONTENT.length);

        assertThatThrownBy(() -> storage.store(key, content(), CONTENT.length))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("already stored");
    }

    @Test
    void store_devraitLeverEtNeRienLaisser_quandTailleAnnonceeFausse() throws IOException {
        UUID key = UUID.randomUUID();

        assertThatThrownBy(() -> storage.store(key, content(), CONTENT.length + 10))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Truncated");

        assertThat(storage.exists(StorageZone.QUARANTINE, key)).isFalse();
        assertThat(filesIn(StorageZone.QUARANTINE)).isEmpty();
    }

    @Test
    void store_devraitAccepter_quandTailleInconnue() {
        UUID key = UUID.randomUUID();

        long written = storage.store(key, content(), -1);

        assertThat(written).isEqualTo(CONTENT.length);
    }

    @Test
    void retrieve_devraitRendreLeContenu_quandPresent() throws IOException {
        UUID key = UUID.randomUUID();
        storage.store(key, content(), CONTENT.length);

        try (InputStream in = storage.retrieve(StorageZone.QUARANTINE, key)) {
            assertThat(in.readAllBytes()).isEqualTo(CONTENT);
        }
    }

    @Test
    void retrieve_devraitLever_quandAbsent() {
        assertThatThrownBy(() -> storage.retrieve(StorageZone.SERVABLE, UUID.randomUUID()))
                .isInstanceOf(FileNotStoredException.class);
    }

    @Test
    void promote_devraitDeplacerVraiment_quandPresentEnQuarantaine() throws IOException {
        UUID key = UUID.randomUUID();
        storage.store(key, content(), CONTENT.length);

        storage.promote(key);

        assertThat(storage.exists(StorageZone.QUARANTINE, key)).isFalse();
        assertThat(storage.exists(StorageZone.SERVABLE, key)).isTrue();
        assertThat(filesIn(StorageZone.QUARANTINE)).isEmpty();

        try (InputStream in = storage.retrieve(StorageZone.SERVABLE, key)) {
            assertThat(in.readAllBytes()).isEqualTo(CONTENT);
        }
    }

    @Test
    void promote_devraitLever_quandAbsentDeQuarantaine() {
        assertThatThrownBy(() -> storage.promote(UUID.randomUUID()))
                .isInstanceOf(FileNotStoredException.class);
    }

    @Test
    void exists_devraitDistinguerLesDeuxZones_quandFichierPromu() {
        UUID key = UUID.randomUUID();
        storage.store(key, content(), CONTENT.length);

        assertThat(storage.exists(StorageZone.QUARANTINE, key)).isTrue();
        assertThat(storage.exists(StorageZone.SERVABLE, key)).isFalse();

        storage.promote(key);

        assertThat(storage.exists(StorageZone.QUARANTINE, key)).isFalse();
        assertThat(storage.exists(StorageZone.SERVABLE, key)).isTrue();
    }

    @Test
    void exists_devraitEtreFaux_quandCleInconnue() {
        UUID key = UUID.randomUUID();

        assertThat(storage.exists(StorageZone.QUARANTINE, key)).isFalse();
        assertThat(storage.exists(StorageZone.SERVABLE, key)).isFalse();
    }

    @Test
    void zoneServable_devraitResterVide_quandAucunPromote() throws IOException {
        for (int i = 0; i < 5; i++) {
            UUID key = UUID.randomUUID();
            storage.store(key, content(), CONTENT.length);
            try (InputStream ignored = storage.retrieve(StorageZone.QUARANTINE, key)) {
                assertThat(storage.exists(StorageZone.QUARANTINE, key)).isTrue();
            }
        }

        assertThat(filesIn(StorageZone.SERVABLE)).isEmpty();
    }

    @Test
    void quarantaine_devraitEtreInaccessibleAuxAutres_quandCreee() throws IOException {
        Path quarantine = zoneRoot(StorageZone.QUARANTINE);

        if (!Files.getFileStore(quarantine).supportsFileAttributeView(PosixFileAttributeView.class)) {
            return;
        }

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(quarantine)))
                .isEqualTo("rwx------");
    }

    @Test
    void constructeur_devraitCreerLesDeuxZones_quandRepertoireVide() {
        assertThat(zoneRoot(StorageZone.QUARANTINE)).isDirectory();
        assertThat(zoneRoot(StorageZone.SERVABLE)).isDirectory();
    }
}
