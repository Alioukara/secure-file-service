package io.github.alioukara.sfs.config;

import io.github.alioukara.sfs.antivirus.AntivirusScanner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Semaphore;

@Configuration
@EnableAsync
public class ScanConcurrencyConfig {

    /**
     * Sized by the active scanner, never by a value of our own: ClamAV's
     * MaxThreads and a remote API quota are not the same number.
     */
    @Bean
    public Semaphore scanPermits(AntivirusScanner scanner) {
        return new Semaphore(scanner.maxConcurrentScans());
    }
}
