package com.cde.platform.service;

import com.cde.platform.exception.DocumentProcessingException;
import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.service.FormFieldBuilder.FieldKind;
import com.cde.platform.service.FormFieldBuilder.FieldPlacement;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Placing and removing form fields, and committing each change as a version.
 *
 * <p>The real {@link FormFieldBuilder} and real PDFs throughout, because the
 * part worth testing here is the seam: the builder knows about PDFs and
 * nothing about this application, and this class supplies the document
 * lookup, the version commit and the error translation. Stubbing the builder
 * would leave only the plumbing, which is not where the mistakes are.
 *
 * <p>The work-file cleanup assertions matter for the same reason they did in
 * the processing and page services — a leaked work file per failed edit fills
 * a disk slowly enough that nobody connects it to form design — and here the
 * builder really does write one before failing, so they are not vacuous.
 */
@SpringBootTest
@DisplayName("designing a form on a document")
class FormDesignServiceTest {

    private static final String USERNAME = "form-design-user";

    @Autowired FormDesignService formDesign;
    @Autowired DocumentRepository documentRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository userRepo;

    /**
     * A spy, so one case can make the commit fail after the rewrite has
     * already written its output — the one sequence that can leave a work
     * file with nothing owning it.
     */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    DocumentVersionService versionService;

    @TempDir Path storage;

    private Document sheet;

    @BeforeEach
    void setUp() throws IOException {
        User owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("form-design@example.com")
                .password("x").role(User.Role.ENGINEER).build()));

        Project project = projectRepo.save(Project.builder()
            .name("Forms").description("d")
            .phase(Project.ProjectPhase.DESIGN).build());

        Path path = storage.resolve("uuid_sheet.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.addPage(new PDPage());
            pdf.save(path.toFile());
        }

        sheet = documentRepo.save(Document.builder()
            .name("Sheet").fileName("sheet.pdf").fileType("application/pdf")
            .filePath(path.toString()).fileSize(Files.size(path))
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(owner).build());
    }

    private FieldPlacement text(String name) {
        return new FieldPlacement(name, FieldKind.TEXT, 1, 70, 700, 200, 20, false, List.of());
    }

    private List<Path> filesOnDisk() throws IOException {
        try (var entries = Files.walk(storage)) {
            return entries.filter(Files::isRegularFile).sorted().toList();
        }
    }

    // ── Adding ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("placing a field commits a version")
    void addingCommitsAVersion() {
        var change = formDesign.addFields(sheet.getId(), List.of(text("approver")), USERNAME);

        assertThat(change.version()).isNotNull();
        assertThat(change.fields()).containsExactly("approver");
    }

    @Test
    @DisplayName("the version summary names the fields placed")
    void summaryNamesTheFields() {
        // It is what the history panel shows, and "changed the form" would
        // not tell anybody which field appeared.
        var change = formDesign.addFields(
            sheet.getId(), List.of(text("approver"), text("dateSigned")), USERNAME);

        assertThat(change.version().getSummary())
            .contains("approver").contains("dateSigned").contains("2");
    }

    @Test
    @DisplayName("placing several fields at once is one version, not one each")
    void severalFieldsAreOneVersion() {
        var change = formDesign.addFields(
            sheet.getId(), List.of(text("a"), text("b"), text("c")), USERNAME);

        assertThat(change.fields()).hasSize(3);
        assertThat(change.version().getVersionNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("refuses a change that places nothing")
    void refusesAnEmptyPlacement() {
        assertThatThrownBy(() -> formDesign.addFields(sheet.getId(), List.of(), USERNAME))
            .isInstanceOf(DocumentProcessingException.class)
            .hasMessageContaining("at least one field");
    }

    @Test
    @DisplayName("refuses a change with no placements at all")
    void refusesNullPlacements() {
        assertThatThrownBy(() -> formDesign.addFields(sheet.getId(), null, USERNAME))
            .hasMessageContaining("at least one field");
    }

    @Test
    @DisplayName("records who made the change")
    void recordsTheActor() {
        var change = formDesign.addFields(sheet.getId(), List.of(text("a")), USERNAME);

        assertThat(change.version().getCreatedBy()).isNotNull();
    }

    @Test
    @DisplayName("records a system change honestly rather than inventing an actor")
    void recordsASystemChange() {
        var change = formDesign.addFields(sheet.getId(), List.of(text("a")), null);

        assertThat(change.version().getCreatedBy()).isNull();
    }

    // ── Removing ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("removing a field commits a version")
    void removingCommitsAVersion() {
        formDesign.addFields(sheet.getId(), List.of(text("approver")), USERNAME);

        var change = formDesign.removeFields(sheet.getId(), List.of("approver"), USERNAME);

        assertThat(change.fields()).containsExactly("approver");
    }

    @Test
    @DisplayName("says so plainly when nothing matched")
    void nothingMatchedIsSaidPlainly() {
        // Still a version, because the document was rewritten; the summary
        // is what stops the history reading as though a field was removed.
        formDesign.addFields(sheet.getId(), List.of(text("approver")), USERNAME);

        var change = formDesign.removeFields(sheet.getId(), List.of("nonexistent"), USERNAME);

        assertThat(change.fields()).isEmpty();
        assertThat(change.version().getSummary()).contains("No matching form fields");
    }

    @Test
    @DisplayName("refuses a removal that names nothing")
    void refusesAnEmptyRemoval() {
        assertThatThrownBy(() -> formDesign.removeFields(sheet.getId(), List.of(), USERNAME))
            .hasMessageContaining("at least one field");
    }

    @Test
    @DisplayName("refuses a removal with no names at all")
    void refusesNullRemoval() {
        assertThatThrownBy(() -> formDesign.removeFields(sheet.getId(), null, USERNAME))
            .hasMessageContaining("at least one field");
    }

    // ── Documents it cannot work on ───────────────────────────────────────

    @Test
    @DisplayName("refuses a document that does not exist")
    void refusesUnknownDocument() {
        assertThatThrownBy(() ->
            formDesign.addFields(9_999_999L, List.of(text("a")), USERNAME))
            .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("refuses a document with no stored file")
    void refusesDocumentWithNoFile() {
        Document detached = documentRepo.save(Document.builder()
            .name("No file").fileName("none.pdf").fileType("application/pdf")
            .filePath("   ").documentType(Document.DocumentType.DRAWING)
            .project(sheet.getProject()).uploadedBy(sheet.getUploadedBy()).build());

        assertThatThrownBy(() ->
            formDesign.addFields(detached.getId(), List.of(text("a")), USERNAME))
            .hasMessageContaining("no stored file");
    }

    // ── What the builder objects to ───────────────────────────────────────

    @Test
    @DisplayName("passes the builder's complaint on, since it names the field")
    void builderComplaintIsPassedOn() {
        // More use to whoever placed the field than a generic rejection.
        FieldPlacement offPage = new FieldPlacement(
            "stray", FieldKind.TEXT, 99, 70, 700, 200, 20, false, List.of());

        assertThatThrownBy(() ->
            formDesign.addFields(sheet.getId(), List.of(offPage), USERNAME))
            .isInstanceOf(DocumentProcessingException.class)
            .hasMessageContaining("99");
    }

    @Test
    @DisplayName("leaves no work file behind when the commit fails after a successful rewrite")
    void cleansUpWhenTheCommitFails() throws Exception {
        // The only path that can actually leak here, and it took two
        // attempts to find. The first version of this test used a placement
        // the builder rejects — but the builder validates before it writes,
        // so no work file ever existed and the assertion passed with the
        // cleanup deleted. What leaks is a rewrite that succeeded followed
        // by a commit that did not: the file is on disk and nothing owns it.
        org.mockito.Mockito.doThrow(new IOException("the version could not be written"))
            .when(versionService).commit(org.mockito.ArgumentMatchers.any(),
                                         org.mockito.ArgumentMatchers.any(),
                                         org.mockito.ArgumentMatchers.any(),
                                         org.mockito.ArgumentMatchers.any(),
                                         org.mockito.ArgumentMatchers.any());
        List<Path> before = filesOnDisk();

        assertThatThrownBy(() ->
            formDesign.addFields(sheet.getId(), List.of(text("approver")), USERNAME))
            .isInstanceOf(DocumentProcessingException.class);

        assertThat(filesOnDisk()).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    @DisplayName("commits nothing when the builder refuses")
    void commitsNothingOnRefusal() {
        FieldPlacement offPage = new FieldPlacement(
            "stray", FieldKind.TEXT, 99, 70, 700, 200, 20, false, List.of());
        Integer versionBefore = documentRepo.findById(sheet.getId())
            .orElseThrow().getCurrentVersion();

        assertThatThrownBy(() ->
            formDesign.addFields(sheet.getId(), List.of(offPage), USERNAME));

        assertThat(documentRepo.findById(sheet.getId()).orElseThrow().getCurrentVersion())
            .isEqualTo(versionBefore);
    }
}
