package io.github.alioukara.sfs.antivirus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanResultTest {

    @Test
    void clean_devraitNePorterNiSignatureNiRaison_quandConstruit() {
        ScanResult result = ScanResult.clean();

        assertThat(result.verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(result.signature()).isNull();
        assertThat(result.reason()).isNull();
    }

    @Test
    void infected_devraitPorterLaSignature_quandConstruit() {
        ScanResult result = ScanResult.infected("Eicar-Test-Signature");

        assertThat(result.verdict()).isEqualTo(Verdict.INFECTED);
        assertThat(result.signature()).isEqualTo("Eicar-Test-Signature");
    }

    @Test
    void inconclusive_devraitPorterLaRaison_quandConstruit() {
        ScanResult result = ScanResult.inconclusive("encrypted archive");

        assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo("encrypted archive");
    }

    @Test
    void constructeur_devraitLever_quandInfectedSansSignature() {
        assertThatThrownBy(() -> new ScanResult(Verdict.INFECTED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");

        assertThatThrownBy(() -> new ScanResult(Verdict.INFECTED, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructeur_devraitLever_quandInconclusiveSansRaison() {
        assertThatThrownBy(() -> new ScanResult(Verdict.INCONCLUSIVE, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void constructeur_devraitLever_quandCleanPorteUneSignature() {
        assertThatThrownBy(() -> new ScanResult(Verdict.CLEAN, "something", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructeur_devraitLever_quandVerdictNul() {
        assertThatThrownBy(() -> new ScanResult(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
