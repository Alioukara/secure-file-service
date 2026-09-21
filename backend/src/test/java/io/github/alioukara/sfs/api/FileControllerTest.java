package io.github.alioukara.sfs.api;

import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;
import io.github.alioukara.sfs.service.DownloadableFile;
import io.github.alioukara.sfs.service.FileNotDownloadableException;
import io.github.alioukara.sfs.service.StoredFileNotFoundException;
import io.github.alioukara.sfs.service.FileService;
import io.github.alioukara.sfs.service.QuotaAccount;
import io.github.alioukara.sfs.service.QuotaExceededException;
import io.github.alioukara.sfs.service.ScannerCapacityExceededException;
import io.github.alioukara.sfs.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    void download_devraitRendreLeContenu_quandClean() throws Exception {
        when(fileService.download(any(UUID.class))).thenReturn(new DownloadableFile(
                "rapport final.pdf", "application/pdf", 5L,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))));

        mvc.perform(get("/api/files/{id}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(content().string("hello"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 5L))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("attachment")))
                // Spring MIME-encodes the name as soon as it holds a space, even
                // in pure ASCII: the readable form is the RFC 5987 one.
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("filename*=UTF-8''rapport%20final.pdf")));
    }

    @Test
    void download_devraitEncoderLeNom_quandCaracteresNonAscii() throws Exception {
        when(fileService.download(any(UUID.class))).thenReturn(new DownloadableFile(
                "rapport été.pdf", "application/pdf", 5L,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))));

        mvc.perform(get("/api/files/{id}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("filename*=UTF-8''")));
    }

    /**
     * Second, independent expression of the refusal table: six rows written by
     * hand, one per non-downloadable status. Deriving them from the handler
     * would make the test agree with any bug, and a status added without a row
     * here fails the count assertion below.
     */
    static Stream<Arguments> refus() {
        return Stream.of(
                Arguments.of(FileStatus.PENDING, 409, true, "SCAN_IN_PROGRESS"),
                Arguments.of(FileStatus.SCANNING, 409, true, "SCAN_IN_PROGRESS"),
                Arguments.of(FileStatus.SCAN_FAILED, 409, true, "SCAN_IN_PROGRESS"),
                Arguments.of(FileStatus.SCAN_FAILED_EXHAUSTED, 409, false, "SCAN_GAVE_UP"),
                Arguments.of(FileStatus.UNSCANNABLE, 409, false, "FILE_UNSCANNABLE"),
                Arguments.of(FileStatus.INFECTED, 403, false, "FILE_INFECTED"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("refus")
    void download_devraitRefuser_quandEtatNonTelechargeable(
            FileStatus status, int code, boolean retryAfter, String reason) throws Exception {

        when(fileService.download(any(UUID.class))).thenThrow(new FileNotDownloadableException(status));

        ResultActions response = mvc.perform(get("/api/files/{id}", UUID.randomUUID()))
                .andExpect(status().is(code))
                .andExpect(jsonPath("$.reason").value(reason));

        if (retryAfter) {
            response.andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"));
        } else {
            response.andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
        }
    }

    @Test
    void refus_devraitCouvrirTousLesEtatsSaufClean_quandTableRelue() {
        assertThat(refus().map(a -> (FileStatus) a.get()[0]))
                .containsExactlyInAnyOrderElementsOf(
                        EnumSet.complementOf(EnumSet.of(FileStatus.CLEAN)));
    }

    @Test
    void download_devraitRendre404_quandIdentifiantInconnu() throws Exception {
        UUID id = UUID.randomUUID();
        when(fileService.download(id)).thenThrow(new StoredFileNotFoundException(id));

        mvc.perform(get("/api/files/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("FILE_NOT_FOUND"));
    }

    @Test
    void download_devraitNeJamaisExposerDeStackTrace_quandErreurInterne() throws Exception {
        when(fileService.download(any(UUID.class))).thenThrow(new StorageException("disk failure"));

        mvc.perform(get("/api/files/{id}", UUID.randomUUID()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("at io.github"))))
                .andExpect(jsonPath("$.reason").value("STORAGE_FAILURE"));
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
