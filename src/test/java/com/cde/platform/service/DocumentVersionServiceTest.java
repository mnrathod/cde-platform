package com.cde.platform.service;

import com.cde.platform.model.Document;
import com.cde.platform.model.DocumentVersion;
import com.cde.platform.model.DocumentVersion.DocumentOperation;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.DocumentVersionRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercised against a real database and a real filesystem rather than mocks.
 *
 * <p>The behaviour under test is that a processing run leaves its output where
 * the <em>next</em> run will read it — which is a statement about files on
 * disk and a mutated {@code Document.filePath}, not about which repository
 * methods were called. Mocks would pass regardless.
 */
@SpringBootTest
@Transactional
class DocumentVersionServiceTest {

    @Autowired DocumentVersionService    versionService;
    @Autowired DocumentVersionRepository versionRepo;
    @Autowired DocumentRepository        documentRepo;
    @Autowired ProjectRepository         projectRepo;
    @Autowired UserRepository            userRepo;

    @TempDir Path storage;

    private User     actor;
    private Document document;

    @BeforeEach
    void setUp() throws IOException {
        actor = userRepo.findByUsername("version-test-user").orElseGet(() ->
            userRepo.save(User.builder()
                .username("version-test-user")
                .email("version-test@example.com")
                .password("x")
                .role(User.Role.ENGINEER)
                .build()));

        Project project = projectRepo.save(Project.builder()
            .name("Versioning").description("d")
            .phase(Project.ProjectPhase.DESIGN)
            .build());

        Path original = storage.resolve("uuid_plan.pdf");
        Files.writeString(original, "ORIGINAL");

        document = documentRepo.save(Document.builder()
            .name("Plan").fileName("plan.pdf").fileType("application/pdf")
            .filePath(original.toString())
            .fileSize(Files.size(original))
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(actor)
            .build());
    }

    /** Stands in for a converter run: writes the given content to a work path. */
    private Path converterWrites(String content) throws IOException {
        Path work = versionService.allocateWorkPath(document, "test");
        Files.writeString(work, content);
        return work;
    }

    private String contentOf(Document doc) throws IOException {
        return Files.readString(Paths.get(doc.getFilePath()));
    }

    // ── Backfilling version 1 ────────────────────────────────────

    @Test
    @DisplayName("records the uploaded file as version 1 when there is no history")
    void backfillsInitialVersion() {
        DocumentVersion first = versionService.currentVersion(document, actor);

        assertThat(first.getVersionNumber()).isEqualTo(1);
        assertThat(first.getOperation()).isEqualTo(DocumentOperation.UPLOAD);
        assertThat(first.getFilePath()).isEqualTo(document.getFilePath());
        assertThat(document.getCurrentVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("returns the existing head instead of creating a second version 1")
    void backfillIsIdempotent() {
        DocumentVersion first  = versionService.currentVersion(document, actor);
        DocumentVersion second = versionService.currentVersion(document, actor);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(versionRepo.findByDocument_IdOrderByVersionNumberDesc(document.getId()))
            .hasSize(1);
    }

    // ── Committing ───────────────────────────────────────────────

    @Test
    @DisplayName("commit adds version 2 and points the document at it")
    void commitAdvancesTheHead() throws IOException {
        DocumentVersion committed = versionService.commit(
            document, converterWrites("REDACTED"),
            DocumentOperation.REDACT, "Redacted 1 region", actor);

        assertThat(committed.getVersionNumber()).isEqualTo(2);
        assertThat(document.getCurrentVersion()).isEqualTo(2);
        assertThat(document.getFilePath()).isEqualTo(committed.getFilePath());
        assertThat(contentOf(document)).isEqualTo("REDACTED");
        assertThat(document.getFileSize()).isEqualTo("REDACTED".length());
    }

    @Test
    @DisplayName("a second operation reads the first operation's output")
    void operationsCompose() throws IOException {
        // OCR the scan...
        versionService.commit(document, converterWrites("SCAN+TEXT"),
            DocumentOperation.OCR, "Recognised 2 pages", actor);

        // ...then redact, which must start from the OCR'd file, not the original.
        String inputToSecondRun = contentOf(document);
        versionService.commit(document, converterWrites(inputToSecondRun + "+REDACTED"),
            DocumentOperation.REDACT, "Redacted 1 region", actor);

        assertThat(inputToSecondRun).isEqualTo("SCAN+TEXT");
        assertThat(contentOf(document)).isEqualTo("SCAN+TEXT+REDACTED");
        assertThat(document.getCurrentVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("earlier versions keep their own bytes when a new one is committed")
    void earlierVersionsAreImmutable() throws IOException {
        versionService.commit(document, converterWrites("V2"),
            DocumentOperation.OCR, "ocr", actor);
        versionService.commit(document, converterWrites("V3"),
            DocumentOperation.REDACT, "redact", actor);

        assertThat(readVersion(1)).isEqualTo("ORIGINAL");
        assertThat(readVersion(2)).isEqualTo("V2");
        assertThat(readVersion(3)).isEqualTo("V3");
    }

    @Test
    @DisplayName("repeating an operation does not overwrite the previous result")
    void repeatedOperationsGetDistinctFiles() throws IOException {
        DocumentVersion second = versionService.commit(document, converterWrites("FIRST RUN"),
            DocumentOperation.REDACT, "redact", actor);
        DocumentVersion third = versionService.commit(document, converterWrites("SECOND RUN"),
            DocumentOperation.REDACT, "redact again", actor);

        assertThat(second.getFilePath()).isNotEqualTo(third.getFilePath());
        assertThat(Files.readString(Paths.get(second.getFilePath()))).isEqualTo("FIRST RUN");
        assertThat(Files.readString(Paths.get(third.getFilePath()))).isEqualTo("SECOND RUN");
    }

    @Test
    @DisplayName("commit moves the work file rather than leaving a copy behind")
    void commitConsumesTheWorkFile() throws IOException {
        Path work = converterWrites("DONE");
        versionService.commit(document, work, DocumentOperation.FLATTEN, "flatten", actor);

        assertThat(work).doesNotExist();
    }

    @Test
    @DisplayName("records a content hash matching the committed bytes")
    void recordsContentHash() throws IOException {
        DocumentVersion committed = versionService.commit(document, converterWrites("HASH ME"),
            DocumentOperation.OCR, "ocr", actor);

        assertThat(committed.getContentHash()).isEqualTo(sha256("HASH ME"));
        assertThat(committed.getContentHash())
            .isNotEqualTo(versionService.findVersion(document.getId(), 1).orElseThrow()
                .getContentHash());
    }

    @Test
    @DisplayName("records who ran the operation and what it did")
    void recordsProvenance() throws IOException {
        DocumentVersion committed = versionService.commit(document, converterWrites("X"),
            DocumentOperation.FORM_FILL, "Filled 3 field(s)", actor);

        assertThat(committed.getCreatedBy().getUsername()).isEqualTo("version-test-user");
        assertThat(committed.getSummary()).isEqualTo("Filled 3 field(s)");
        assertThat(committed.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("a failed run leaves the document on its previous version")
    void missingOutputLeavesDocumentUnchanged() {
        String before = document.getFilePath();
        Path missing  = storage.resolve("never-written.pdf");

        assertThatThrownBy(() -> versionService.commit(
                document, missing, DocumentOperation.OCR, "ocr", actor))
            .isInstanceOf(IOException.class);

        assertThat(document.getFilePath()).isEqualTo(before);
    }

    // ── Restoring ────────────────────────────────────────────────

    @Test
    @DisplayName("restore copies an earlier version forward as a new version")
    void restoreCopiesForward() throws IOException {
        versionService.commit(document, converterWrites("V2"),
            DocumentOperation.REDACT, "redact", actor);

        DocumentVersion restored =
            versionService.restore(document, 1, actor).orElseThrow();

        assertThat(restored.getVersionNumber()).isEqualTo(3);
        assertThat(restored.getOperation()).isEqualTo(DocumentOperation.RESTORE);
        assertThat(restored.getSummary()).isEqualTo("Restored from version 1");
        assertThat(contentOf(document)).isEqualTo("ORIGINAL");
    }

    @Test
    @DisplayName("restore keeps the versions it rolled back past")
    void restoreDoesNotTruncateHistory() throws IOException {
        versionService.commit(document, converterWrites("V2"),
            DocumentOperation.REDACT, "redact", actor);
        versionService.restore(document, 1, actor);

        List<DocumentVersion> history = versionService.listVersions(document.getId());

        assertThat(history).extracting(DocumentVersion::getVersionNumber)
            .containsExactly(3, 2, 1);
        assertThat(readVersion(2)).isEqualTo("V2");
    }

    @Test
    @DisplayName("restoring a version that does not exist reports nothing to restore")
    void restoreUnknownVersion() throws IOException {
        versionService.currentVersion(document, actor);

        assertThat(versionService.restore(document, 99, actor)).isEmpty();
    }

    // ── Queries ──────────────────────────────────────────────────

    @Test
    @DisplayName("history is newest first")
    void listsNewestFirst() throws IOException {
        versionService.commit(document, converterWrites("V2"), DocumentOperation.OCR, "a", actor);
        versionService.commit(document, converterWrites("V3"), DocumentOperation.REDACT, "b", actor);

        assertThat(versionService.listVersions(document.getId()))
            .extracting(DocumentVersion::getVersionNumber)
            .containsExactly(3, 2, 1);
    }

    @Test
    @DisplayName("head tracks the most recent version")
    void headTracksLatest() throws IOException {
        versionService.commit(document, converterWrites("V2"), DocumentOperation.OCR, "a", actor);

        assertThat(versionService.findHead(document.getId()).orElseThrow().getVersionNumber())
            .isEqualTo(2);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String readVersion(int versionNumber) throws IOException {
        DocumentVersion version = versionService
            .findVersion(document.getId(), versionNumber).orElseThrow();
        return Files.readString(Paths.get(version.getFilePath()));
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
