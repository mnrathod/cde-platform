package com.cde.platform.controller;

import com.cde.platform.model.Document;
import com.cde.platform.model.DocumentVersion.DocumentOperation;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.service.DocumentVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A signature must keep attesting to the bytes it was taken over.
 *
 * <p>Signing used to record a hash of "the document" and verify against
 * whatever {@code Document.filePath} pointed at later. Now that redaction, OCR,
 * flattening and form-filling replace those bytes in place, that arrangement
 * would report every earlier signature as TAMPERED the first time anyone ran
 * one of them — reporting evidence of forgery where there was only ordinary
 * editing. These tests pin the fix: signatures bind to a version.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SignatureVersioningTest {

    private static final String USERNAME = "signature-version-user";

    @Autowired MockMvc               mockMvc;
    @Autowired DocumentVersionService versionService;
    @Autowired DocumentRepository    documentRepo;
    @Autowired ProjectRepository     projectRepo;
    @Autowired UserRepository        userRepo;
    @Autowired ObjectMapper          mapper;

    @TempDir Path storage;

    private Document document;
    private User     signer;

    @BeforeEach
    void setUp() throws IOException {
        signer = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME)
                .email("signature-version@example.com")
                .password("x")
                .role(User.Role.ENGINEER)
                .build()));

        Project project = projectRepo.save(Project.builder()
            .name("Signatures").description("d")
            .phase(Project.ProjectPhase.DESIGN)
            .build());

        // A real PDF, not a text file named .pdf: signing now writes into
        // the document, so it has to be one.
        Path original = storage.resolve("uuid_contract.pdf");
        writePdf(original, "CONTRACT AS SIGNED");

        document = documentRepo.save(Document.builder()
            .name("Contract").fileName("contract.pdf").fileType("application/pdf")
            .filePath(original.toString())
            .fileSize(Files.size(original))
            .documentType(Document.DocumentType.SPECIFICATION)
            .project(project).uploadedBy(signer)
            .build());
    }

    private void writePdf(Path path, String text) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(70, 700);
                content.showText(text);
                content.endText();
            }
            pdf.save(path.toFile());
        }
    }

    private String sign() throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of(
            "role", "Approver", "reason", "Approved for construction"));

        String response = mockMvc.perform(post("/api/signatures/document/{id}/sign", document.getId())
                .contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        return mapper.readTree(response).path("signature").path("signatureId").asText();
    }

    /**
     * Stands in for a processing run that rewrites the document. It has to
     * produce a real PDF: a later signature parses whatever the head points
     * at, so a text file here would fail for the wrong reason.
     */
    private void processDocument(String newContent) throws IOException {
        Path work = versionService.allocateWorkPath(document, "test");
        writePdf(work, newContent);
        versionService.commit(document, work, DocumentOperation.OCR,
            "Recognised 3 page(s)", signer);
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("signing records which version was signed")
    void signingRecordsTheVersion() throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of(
            "role", "Reviewer", "reason", "Reviewed"));

        mockMvc.perform(post("/api/signatures/document/{id}/sign", document.getId())
                .contentType("application/json").content(body))
            .andExpect(status().isOk())
            // Signing writes into the document, so it commits a version of
            // its own — and that is the version the signature covers.
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.embedded").value(true))
            // Under `signature` rather than at the top level: the reply is the
            // signature plus what signing did, not a merge of the two.
            .andExpect(jsonPath("$.signature.status").value("VALID"))
            .andExpect(jsonPath("$.signature.version").value(2));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a signature stays valid after the document is processed again")
    void signatureSurvivesLaterProcessing() throws Exception {
        String signatureId = sign();

        processDocument("CONTRACT AS SIGNED + OCR TEXT LAYER");

        // The head has moved to v3, but the signature covers v2 and v2 still
        // holds exactly the bytes that were signed.
        assertThat(document.getCurrentVersion()).isEqualTo(3);
        mockMvc.perform(post("/api/signatures/{id}/verify", signatureId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.status").value("VALID"));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("tampering with the signed version's own bytes is still detected")
    void tamperingWithTheSignedVersionIsDetected() throws Exception {
        String signatureId = sign();

        // Rewrite the signed version in place — the case the signature exists
        // to catch, and the one that must not be masked by the fix above.
        Path signedVersion = Path.of(
            versionService.findVersion(document.getId(), 2).orElseThrow().getFilePath());
        byte[] bytes = Files.readAllBytes(signedVersion);
        bytes[200] = (byte) (bytes[200] ^ 0xFF);
        Files.write(signedVersion, bytes);

        mockMvc.perform(post("/api/signatures/{id}/verify", signatureId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.status").value("TAMPERED"));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("a later signature attests to the later version")
    void signingAfterProcessingBindsToTheNewVersion() throws Exception {
        sign();
        processDocument("CONTRACT AS SIGNED + OCR TEXT LAYER");

        String body = mapper.writeValueAsString(java.util.Map.of(
            "role", "Approver", "reason", "Approved after OCR"));

        mockMvc.perform(post("/api/signatures/document/{id}/sign", document.getId())
                .contentType("application/json").content(body))
            .andExpect(status().isOk())
            // v2 was the first signature, v3 the OCR run, so this one is v4.
            .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ENGINEER")
    @DisplayName("the signature is written into the document, not only recorded beside it")
    void signatureIsEmbeddedInTheFile() throws Exception {
        sign();

        // What a recipient outside this system would find.
        Path signed = Path.of(
            versionService.findVersion(document.getId(), 2).orElseThrow().getFilePath());
        try (PDDocument pdf = Loader.loadPDF(signed.toFile())) {
            assertThat(pdf.getSignatureDictionaries()).hasSize(1);
        }
    }
}
