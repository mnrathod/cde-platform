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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A document that has never been processed has a file on disk but no version
 * rows. Both reading its history and downloading its original have to work
 * anyway — otherwise what a client gets back depends on which endpoint someone
 * happened to call first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DocumentVersionControllerTest {

    private static final String USERNAME = "version-controller-user";

    @Autowired MockMvc            mockMvc;
    @Autowired DocumentRepository documentRepo;
    @Autowired ProjectRepository  projectRepo;
    @Autowired UserRepository     userRepo;

    @TempDir Path storage;

    private Document document;

    @BeforeEach
    void setUp() throws IOException {
        User owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("version-controller@example.com")
                .password("x").role(User.Role.ENGINEER).build()));

        Project project = projectRepo.save(Project.builder()
            .name("Versions").description("d")
            .phase(Project.ProjectPhase.DESIGN).build());

        Path original = storage.resolve("uuid_sheet.pdf");
        Files.writeString(original, "ORIGINAL BYTES");

        document = documentRepo.save(Document.builder()
            .name("Sheet").fileName("sheet.pdf").fileType("application/pdf")
            .filePath(original.toString()).fileSize(Files.size(original))
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(owner).build());
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("an unprocessed document reports its upload as version 1")
    void historyIsNeverEmpty() throws Exception {
        mockMvc.perform(get("/api/documents/{id}/versions", document.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].version").value(1))
            .andExpect(jsonPath("$[0].operation").value("UPLOAD"))
            .andExpect(jsonPath("$[0].current").value(true));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("version 1 downloads without the history being listed first")
    void originalIsDownloadableStraightAway() throws Exception {
        mockMvc.perform(get("/api/documents/{id}/versions/1/file", document.getId()))
            .andExpect(status().isOk())
            .andExpect(content().bytes("ORIGINAL BYTES".getBytes()));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("the download is named after the document and its version")
    void downloadIsNamedForItsVersion() throws Exception {
        mockMvc.perform(get("/api/documents/{id}/versions/1/file", document.getId()))
            .andExpect(status().isOk())
            .andExpect(result -> {
                String disposition = result.getResponse().getHeader("Content-Disposition");
                org.assertj.core.api.Assertions.assertThat(disposition).contains("sheet_v1.pdf");
            });
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a version that does not exist is a 404, not a backfilled blank")
    void unknownVersionIsNotFound() throws Exception {
        mockMvc.perform(get("/api/documents/{id}/versions/9/file", document.getId()))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("an unknown document is a 404")
    void unknownDocumentIsNotFound() throws Exception {
        mockMvc.perform(get("/api/documents/{id}/versions", 999999L))
            .andExpect(status().isNotFound());
    }
}
