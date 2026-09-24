package com.cde.platform.controller;

import com.cde.platform.exception.ConverterOfflineException;
import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.service.ConverterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opening an Office document in the viewer.
 *
 * <p>This route converted the document and returned the whole PDF as a
 * {@code byte[]} — tens of megabytes of heap, per reader, for a long
 * specification (§7.7). It now converts to a temporary file and streams
 * that, which introduces the failure the last case here exists for: a
 * temporary file per request is only an improvement while every one of them
 * is removed afterwards. A leak would trade a memory problem for a disk one
 * and be far harder to notice, because it accumulates instead of spiking.
 *
 * <p>The converter itself is stubbed. It is a separate process reached over
 * HTTP (§5.13.10), and what is under test here is what this controller does
 * with the result — including when there is no converter to reach.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("opening an Office document")
class OfficeConversionStreamingTest {

    private static final String USERNAME = "office-conversion-user";
    private static final String CONVERTED_PDF = "%PDF-1.7\nconverted contents";

    @Autowired MockMvc mockMvc;
    @Autowired DocumentRepository documentRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository userRepo;

    @MockitoBean ConverterService converter;

    @TempDir Path storage;

    private Document specification;

    @BeforeEach
    void setUp() throws IOException {
        User owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("office-conversion@example.com")
                .password("x").role(User.Role.ENGINEER).build()));

        Project project = projectRepo.save(Project.builder()
            .name("Office").description("d")
            .phase(Project.ProjectPhase.DESIGN).build());

        Path source = storage.resolve("uuid_spec.docx");
        Files.writeString(source, "a word document");

        specification = documentRepo.save(Document.builder()
            .name("Specification").fileName("spec.docx")
            .fileType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            .filePath(source.toString()).fileSize(Files.size(source))
            .documentType(Document.DocumentType.SPECIFICATION)
            .project(project).uploadedBy(owner).build());
    }

    /** Makes the stubbed converter write a PDF wherever it is asked to. */
    private void converterWritesAPdf() {
        doAnswer(invocation -> {
            Path destination = invocation.getArgument(2);
            Files.writeString(destination, CONVERTED_PDF, StandardCharsets.UTF_8);
            return (long) CONVERTED_PDF.length();
        }).when(converter).convertToPdfFile(any(), anyString(), any(), any(Duration.class));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("the converted PDF is what the reader receives")
    void convertedPdfIsServed() throws Exception {
        converterWritesAPdf();

        mockMvc.perform(get("/api/viewer/{id}", specification.getId()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(content().string(CONVERTED_PDF));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("it is served inline, named after the document, and not cached")
    void convertedPdfKeepsTheViewerHeaders() throws Exception {
        // The same headers a stored PDF carries. A converted document and a
        // stored one are the same thing to the viewer, and one of them
        // behaving differently is a bug nobody can find.
        converterWritesAPdf();

        mockMvc.perform(get("/api/viewer/{id}", specification.getId()))
            .andExpect(header().string("Content-Disposition",
                                       "inline; filename=\"Specification.pdf\""))
            .andExpect(header().string("Cache-Control", "no-cache, must-revalidate"))
            .andExpect(header().string("X-Source-Type", "pdf"))
            .andExpect(header().longValue("Content-Length", CONVERTED_PDF.length()));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("nothing is left behind on disk once the response has been sent")
    void temporaryFileDoesNotSurviveTheResponse() throws Exception {
        // The cost of streaming through a temporary file, and the reason the
        // stream is opened DELETE_ON_CLOSE rather than deleted by hand: the
        // response is written after the controller returns, so a delete in
        // the method would race the send, and a try-with-resources would
        // close the stream before anything was read from it.
        converterWritesAPdf();
        List<Path> before = temporaryConversions();

        mockMvc.perform(get("/api/viewer/{id}", specification.getId()))
            .andExpect(status().isOk());

        assertThat(temporaryConversions())
            .as("a converted PDF left on disk accumulates one file per view")
            .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("nothing is left behind when the conversion fails either")
    void temporaryFileDoesNotSurviveAFailure() throws Exception {
        doThrow(new RuntimeException("LibreOffice is not installed"))
            .when(converter).convertToPdfFile(any(), anyString(), any(), any(Duration.class));
        List<Path> before = temporaryConversions();

        mockMvc.perform(get("/api/viewer/{id}", specification.getId()))
            .andExpect(status().isOk());

        assertThat(temporaryConversions()).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a converter that cannot be reached is reported as such, not as a broken file")
    void converterOfflineIsNamed() throws Exception {
        // The distinction that decides who the reader goes to: their
        // document is fine, the deployment is not.
        doThrow(new ConverterOfflineException("http://converter:5001"))
            .when(converter).convertToPdfFile(any(), anyString(), any(), any(Duration.class));

        mockMvc.perform(get("/api/viewer/{id}", specification.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("office_error"))
            .andExpect(jsonPath("$.error").value("converter_offline"))
            .andExpect(jsonPath("$.loInstalled").value(false));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a converter that is running but cannot convert says something different")
    void conversionFailureIsNotReportedAsOffline() throws Exception {
        doThrow(new RuntimeException("Unsupported document revision"))
            .when(converter).convertToPdfFile(any(), anyString(), any(), any(Duration.class));

        mockMvc.perform(get("/api/viewer/{id}", specification.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("office_error"))
            .andExpect(jsonPath("$.error").value("Unsupported document revision"));
    }

    /** Conversions parked in the system temporary directory. */
    private List<Path> temporaryConversions() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var entries = Files.list(tmp)) {
            return entries
                .filter(path -> path.getFileName().toString().startsWith("viewer-converted-"))
                .toList();
        }
    }
}
