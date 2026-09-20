package io.github.alioukara.sfs.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static io.github.alioukara.sfs.domain.FileStatus.CLEAN;
import static io.github.alioukara.sfs.domain.FileStatus.INFECTED;
import static io.github.alioukara.sfs.domain.FileStatus.PENDING;
import static io.github.alioukara.sfs.domain.FileStatus.SCANNING;
import static io.github.alioukara.sfs.domain.FileStatus.SCAN_FAILED;
import static io.github.alioukara.sfs.domain.FileStatus.SCAN_FAILED_EXHAUSTED;
import static io.github.alioukara.sfs.domain.FileStatus.UNSCANNABLE;
import static org.assertj.core.api.Assertions.assertThat;

class FileStatusTest {

    /**
     * Second, independent expression of the transition table. Written by hand
     * on purpose: deriving it from the production code would make the test
     * agree with any bug.
     */
    private static final Map<FileStatus, Set<FileStatus>> ALLOWED = new EnumMap<>(Map.of(
            PENDING, EnumSet.of(SCANNING),
            SCANNING, EnumSet.of(CLEAN, INFECTED, SCAN_FAILED, SCAN_FAILED_EXHAUSTED, UNSCANNABLE, PENDING),
            SCAN_FAILED, EnumSet.of(PENDING),
            SCAN_FAILED_EXHAUSTED, EnumSet.of(PENDING),
            CLEAN, EnumSet.noneOf(FileStatus.class),
            INFECTED, EnumSet.noneOf(FileStatus.class),
            UNSCANNABLE, EnumSet.noneOf(FileStatus.class)));

    @Test
    void canTransitionTo_devraitCouvrirToutesLesPaires_quandOnBalaieLaTable() {
        for (FileStatus from : FileStatus.values()) {
            for (FileStatus to : FileStatus.values()) {
                boolean expected = ALLOWED.get(from).contains(to);
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(FileStatus.class)
    void canTransitionTo_devraitRefuser_quandCibleNulle(FileStatus from) {
        assertThat(from.canTransitionTo(null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(FileStatus.class)
    void canTransitionTo_devraitRefuserLaBoucle_quandCibleIdentique(FileStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = FileStatus.class, names = {"CLEAN", "INFECTED", "UNSCANNABLE"})
    void canTransitionTo_devraitToutRefuser_quandEtatTerminal(FileStatus terminal) {
        for (FileStatus to : FileStatus.values()) {
            assertThat(terminal.canTransitionTo(to)).as("%s -> %s", terminal, to).isFalse();
        }
    }

    @Test
    void isDownloadable_devraitEtreVrai_quandCleanUniquement() {
        for (FileStatus status : FileStatus.values()) {
            assertThat(status.isDownloadable())
                    .as("%s", status)
                    .isEqualTo(status == CLEAN);
        }
    }

    @Test
    void isDownloadable_devraitRefuser_quandNouvelEtatAjoute() {
        long downloadable = EnumSet.allOf(FileStatus.class).stream()
                .filter(FileStatus::isDownloadable)
                .count();

        assertThat(downloadable).isOne();
    }
}
