package io.github.alioukara.sfs.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Fails startup on an unsupported value of {@code sfs.antivirus.implementation}.
 * Neither condition would match, leaving no scanner at all, and Spring would
 * then report a missing AntivirusScanner instead of the typo that caused it.
 */
@Configuration
public class AntivirusImplementationGuard {

    private static final Set<String> SUPPORTED = Set.of("clamav", "http");

    public AntivirusImplementationGuard(
            @Value("${sfs.antivirus.implementation}") String implementation) {
        if (!SUPPORTED.contains(implementation)) {
            throw new IllegalStateException(
                    "sfs.antivirus.implementation must be one of " + SUPPORTED
                            + ", was: " + implementation);
        }
    }
}
