package io.github.alioukara.sfs.api;

import io.github.alioukara.sfs.domain.StoredFile;
import io.github.alioukara.sfs.service.FileService;
import io.github.alioukara.sfs.service.QuotaAccount;
import io.github.alioukara.sfs.service.QuotaExceededException;
import io.github.alioukara.sfs.service.ScannerCapacityExceededException;
import io.github.alioukara.sfs.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FileController.class)
@TestPropertySource(properties = "sfs.api.retry-after-seconds=30")
class FileControllerTest {

    @TestConfiguration
    static class Handler {
        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler(30);
        }

        /**
         * The container raises MaxUploadSizeExceededException during multipart
         * resolution, which MockMvc never performs. This route reproduces the
         * throw so the advice mapping is covered; that the resolver actually
         * routes there still needs a running application to confirm.
         */
        @Bean
        ContainerLimitProbe containerLimitProbe() {
            return new ContainerLimitProbe();
        }
    }

    @RestController
    static class ContainerLimitProbe {

        @PostMapping("/test/container-limit")
        void raise() {
            throw new MaxUploadSizeExceededException(100);
        }

        @PostMapping("/test/unreadable-part")
        void unreadable() throws IOException {
            throw new IOException("client disconnected");
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockBean
    private FileService fileService;

    private static MockMultipartFile part(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }

    private static MockMultipartFile validPart() {
        return part("report.pdf", "application/pdf", "hello".getBytes(StandardCharsets.UTF_8));
    }

    private static StoredFile pendingFile() {
        return StoredFile.pending(UUID.randomUUID(), "report.pdf", "application/pdf", 5L, "abc");
    }

    @Test
    void upload_devraitRendre202_quandFichierAccepte() throws Exception {
        StoredFile stored = pendingFile();
        when(fileService.upload(anyString(), anyString(), anyLong(), any())).thenReturn(stored);

        mvc.perform(multipart("/api/files").file(validPart()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fileId").value(stored.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void upload_devraitAppliquerLeTypeParDefaut_quandContentTypeAbsent() throws Exception {
        when(fileService.upload(anyString(), anyString(), anyLong(), any())).thenReturn(pendingFile());

        mvc.perform(multipart("/api/files")
                        .file(part("export.csv", null, "a,b".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isAccepted());

        verify(fileService).upload(eq("export.csv"), eq("application/octet-stream"), anyLong(), any());
    }

    @Test
    void upload_devraitRendre400_quandNomDeFichierAbsent() throws Exception {
        mvc.perform(multipart("/api/files")
                        .file(part(null, "application/pdf", "hello".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_UPLOAD"));
    }

    @Test
    void upload_devraitRendre400_quandFichierVide() throws Exception {
        mvc.perform(multipart("/api/files").file(part("empty.txt", "text/plain", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_UPLOAD"));
    }

    @Test
    void upload_devraitRendre413_quandCapaciteDuScannerDepassee() throws Exception {
        when(fileService.upload(anyString(), anyString(), anyLong(), any()))
                .thenThrow(new ScannerCapacityExceededException(200, 100));

        mvc.perform(multipart("/api/files").file(validPart()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.reason").value("SCANNER_CAPACITY_EXCEEDED"));
    }

    @Test
    void upload_devraitRendre503AvecRetryAfter_quandCompteAutomatiquePlein() throws Exception {
        when(fileService.upload(anyString(), anyString(), anyLong(), any()))
                .thenThrow(new QuotaExceededException(QuotaAccount.AUTOMATIC, 2_000, 2_000));

        mvc.perform(multipart("/api/files").file(validPart()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.reason").value("LOAD_QUOTA_EXCEEDED"));
    }

    @Test
    void upload_devraitRendre503SansRetryAfter_quandCompteSurActionPlein() throws Exception {
        when(fileService.upload(anyString(), anyString(), anyLong(), any()))
                .thenThrow(new QuotaExceededException(QuotaAccount.ON_ACTION, 5_000, 5_000));

        mvc.perform(multipart("/api/files").file(validPart()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.reason").value("RETENTION_QUOTA_EXCEEDED"));
    }

    @Test
    void handler_devraitRendre413_quandConteneurRefuseLUpload() throws Exception {
        mvc.perform(post("/test/container-limit"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.reason").value("UPLOAD_LIMIT_EXCEEDED"));
    }

    @Test
    void handler_devraitRendre500_quandLectureDeLaPartieEchoue() throws Exception {
        mvc.perform(post("/test/unreadable-part"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.reason").value("UPLOAD_READ_FAILED"));
    }

    @Test
    void upload_devraitRendre500_quandStockageEchoue() throws Exception {
        when(fileService.upload(anyString(), anyString(), anyLong(), any()))
                .thenThrow(new StorageException("disk full"));

        mvc.perform(multipart("/api/files").file(validPart()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.reason").value("STORAGE_FAILURE"));
    }
}
