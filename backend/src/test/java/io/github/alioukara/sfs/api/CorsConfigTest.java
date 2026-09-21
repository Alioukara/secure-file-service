package io.github.alioukara.sfs.api;

import io.github.alioukara.sfs.service.FileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FileController.class)
@TestPropertySource(properties = {
        "sfs.api.allowed-origins=http://localhost:3000",
        "sfs.api.retry-after-seconds=30"
})
class CorsConfigTest {

    private static final String ALLOWED = "http://localhost:3000";
    private static final String UNKNOWN = "http://evil.example";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FileService fileService;

    @Test
    void preflight_devraitAutoriser_quandOrigineDeclaree() throws Exception {
        mockMvc.perform(options("/api/files")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED));
    }

    @Test
    void preflight_devraitRefuser_quandOrigineInconnue() throws Exception {
        mockMvc.perform(options("/api/files")
                        .header(HttpHeaders.ORIGIN, UNKNOWN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflight_devraitRefuser_quandMethodeNonAutorisee() throws Exception {
        mockMvc.perform(options("/api/files")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                .andExpect(status().isForbidden());
    }

    /** Without this, the browser hides both headers from the front. */
    @Test
    void requete_devraitExposerLesEntetes_quandOrigineDeclaree() throws Exception {
        when(fileService.list(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/files").header(HttpHeaders.ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsString(HttpHeaders.CONTENT_DISPOSITION)))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsString(HttpHeaders.RETRY_AFTER)));
    }
}
