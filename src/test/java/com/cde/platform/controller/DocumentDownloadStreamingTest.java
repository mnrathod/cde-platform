package com.cde.platform.controller;

import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documents are sent to the browser without being held in memory first.
 *
 * <p>§7.7 forbids {@code Files.readAllBytes} on user-supplied content, and
 * four download routes were doing it: the viewer's PDF and image routes, the
 * model route, and the version download. Each allocated the whole document on
 * the heap and held it for as long as the client took to read it. §6.7.4 says
 * the same thing again with the number attached — a model measured in
 * gigabytes must not touch application memory.
 *
 * <p>Asserting that the right bytes arrive would not have caught any of that;
 * the bytes were always right. What distinguishes a streamed response from a
 * buffered one, from outside, is that a streamed one can answer a {@code
 * Range} request — Spring can seek within a {@code FileSystemResource} and
 * cannot seek within a {@code byte[]} body. So that is what these assert. It
 * is not an incidental property either: §7.7 requires range support for large
 * media and resumable downloads, and it is how the viewer scrubs through a
 * long drawing without fetching all of it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("downloading a document")
class DocumentDownloadStreamingTest {

    private static final String USERNAME = "download-streaming-user";

    /** Long enough that a partial response is unambiguously partial. */
    private static final String PDF_BODY = "%PDF-1.7\n" + "x".repeat(4_000);

    @Autowired MockMvc mockMvc;
    @Autowired DocumentRepository documentRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository userRepo;

    @TempDir Path storage;

    private Document pdf;
    private Document image;
    private Document model;

    @BeforeEach
    void setUp() throws IOException {
        User owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("download-streaming@example.com")
                .password("x").role(User.Role.ENGINEER).build()));

        Project project = projectRepo.save(Project.builder()
            .name("Downloads").description("d")
            .phase(Project.ProjectPhase.DESIGN).build());

        pdf = store(project, owner, "sheet.pdf", "application/pdf", PDF_BODY);
        image = store(project, owner, "site.png", "image/png", "PNG" + "y".repeat(2_000));
        model = store(project, owner, "tower.glb", "model/gltf-binary", "glTF" + "z".repeat(3_000));
    }

    private Document store(Project project, User owner,
                           String fileName, String mediaType, String body) throws IOException {
        Path path = storage.resolve("uuid_" + fileName);
        Files.writeString(path, body, StandardCharsets.UTF_8);
        return documentRepo.save(Document.builder()
            .name(fileName).fileName(fileName).fileType(mediaType)
            .filePath(path.toString()).fileSize(Files.size(path))
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(owner).build());
    }

    // ── The viewer's PDF route ────────────────────────────────────────────

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a PDF arrives whole when the whole thing is asked for")
    void pdfArrivesWhole() throws Exception {
        mockMvc.perform(get("/api/viewer/{id}/pdf", pdf.getId()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().longValue("Content-Length", PDF_BODY.length()))
            .andExpect(content().string(PDF_BODY));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a PDF can be asked for a byte at a time, which a buffered body cannot do")
    void pdfAnswersARangeRequest() throws Exception {
        mockMvc.perform(get("/api/viewer/{id}/pdf", pdf.getId())
                .header("Range", "bytes=0-7"))
            .andExpect(status().isPartialContent())
            .andExpect(content().string("%PDF-1.7"));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a range from the middle of a PDF returns only that range")
    void pdfRangeIsTheRangeAskedFor() throws Exception {
        mockMvc.perform(get("/api/viewer/{id}/pdf", pdf.getId())
                .header("Range", "bytes=100-199"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Range", "bytes 100-199/" + PDF_BODY.length()))
            .andExpect(result ->
                assertThat(result.getResponse().getContentAsString()).hasSize(100));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a PDF is still served inline, under its own name, and not cached")
    void pdfKeepsItsHeaders() throws Exception {
        // These were on the old byte[] response and had to survive the move.
        // Losing the cache directive is the one that bites quietly: the
        // bytes behind this URL are replaced on every commit, so a cached
        // copy shows a version that no longer exists.
        mockMvc.perform(get("/api/viewer/{id}/pdf", pdf.getId()))
            .andExpect(header().string("Content-Disposition", "inline; filename=\"sheet.pdf\""))
            .andExpect(header().string("Cache-Control", "no-cache, must-revalidate"))
            .andExpect(header().string("X-Source-Type", "pdf"));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("asking the PDF route for something that is not a PDF is refused")
    void pdfRouteRefusesOtherFormats() throws Exception {
        mockMvc.perform(get("/api/viewer/{id}/pdf", image.getId()))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a document that does not exist is a 404, not an empty download")
    void unknownDocumentIsNotFound() throws Exception {
        mockMvc.perform(get("/api/viewer/{id}/pdf", 9_999_999L))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a document whose file has gone from disk says so rather than sending nothing")
    void missingFileIsReported() throws Exception {
        Files.delete(Path.of(pdf.getFilePath()));

        mockMvc.perform(get("/api/viewer/{id}/pdf", pdf.getId()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("not found on disk")));
    }

    // ── The viewer's image route ──────────────────────────────────────────

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("an image arrives with the media type it was stored under")
    void imageArrivesWhole() throws Exception {
        mockMvc.perform(get("/api/viewer/{id}", image.getId()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("an image answers a range request too")
    void imageAnswersARangeRequest() throws Exception {
        mockMvc.perform(get("/api/viewer/{id}", image.getId())
                .header("Range", "bytes=0-2"))
            .andExpect(status().isPartialContent())
            .andExpect(content().string("PNG"));
    }

    // ── The model route ───────────────────────────────────────────────────

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a model answers a range request, which is what resumable download needs")
    void modelAnswersARangeRequest() throws Exception {
        // The route this matters most for. A federated model is the largest
        // thing the product serves, and it is the one a reader is most
        // likely to lose a connection partway through.
        mockMvc.perform(get("/api/viewer3d/{id}", model.getId())
                .header("Range", "bytes=0-3"))
            .andExpect(status().isPartialContent())
            .andExpect(content().string("glTF"));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a model still declares its format to the viewer")
    void modelKeepsItsFormatHeader() throws Exception {
        mockMvc.perform(get("/api/viewer3d/{id}", model.getId()))
            .andExpect(status().isOk())
            .andExpect(header().string("X-3D-Format", "glb"))
            .andExpect(header().string("Access-Control-Expose-Headers", "X-3D-Format"));
    }

    // ── The version download ──────────────────────────────────────────────

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a stored version answers a range request")
    void versionAnswersARangeRequest() throws Exception {
        mockMvc.perform(get("/api/documents/{id}/versions/1/file", pdf.getId())
                .header("Range", "bytes=0-7"))
            .andExpect(status().isPartialContent())
            .andExpect(content().string("%PDF-1.7"));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a stored version is still an attachment named for its version")
    void versionKeepsItsDisposition() throws Exception {
        mockMvc.perform(get("/api/documents/{id}/versions/1/file", pdf.getId()))
            .andExpect(status().isOk())
            .andExpect(result -> assertThat(
                result.getResponse().getHeader("Content-Disposition"))
                .contains("attachment").contains("sheet_v1.pdf"));
    }
}
