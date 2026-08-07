package com.cde.platform.service;

import com.cde.platform.model.*;
import com.cde.platform.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deletion is exercised against a real database rather than mocks: the bug
 * being guarded against was a referential-integrity constraint, which mocked
 * repositories cannot reproduce — every ordering would have "passed".
 */
@SpringBootTest
@Transactional
class DocumentDeletionServiceTest {

    @Autowired DocumentDeletionService  deletionService;
    @Autowired ProjectRepository        projectRepo;
    @Autowired DocumentRepository       documentRepo;
    @Autowired AnnotationRepository     annotationRepo;
    @Autowired AnnotationReplyRepository replyRepo;
    @Autowired UserRepository           userRepo;

    private User author;

    @BeforeEach
    void setUp() {
        author = userRepo.findByUsername("admin").orElseGet(() ->
            userRepo.save(User.builder()
                .username("deletion-test-user")
                .email("deletion-test@example.com")
                .password("x")
                .role(User.Role.ENGINEER)
                .build()));
    }

    private Project newProject(String name) {
        return projectRepo.save(Project.builder()
            .name(name).description("d")
            .phase(Project.ProjectPhase.DESIGN)
            .build());
    }

    private Document newDocument(Project project, String name) {
        return documentRepo.save(Document.builder()
            .name(name).fileName(name + ".pdf").fileType("application/pdf").fileSize(1L)
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(author)
            .build());
    }

    private Annotation newAnnotation(Document document) {
        Annotation annotation = new Annotation();
        annotation.setDocument(document);
        annotation.setAuthor(author);
        annotation.setType(Annotation.AnnotationType.MARKUP);
        annotation.setShapeData("{\"tool\":\"rect\"}");
        annotation.setPageNumber(1);
        return annotationRepo.save(annotation);
    }

    private AnnotationReply newReply(Annotation annotation) {
        AnnotationReply reply = new AnnotationReply();
        reply.setAnnotation(annotation);
        reply.setAuthor(author);
        reply.setContent("a reply");
        return replyRepo.save(reply);
    }

    @Test
    @DisplayName("deletes a document that has annotations and replies")
    void deletesDocumentWithDependents() {
        Project project   = newProject("P1");
        Document document = newDocument(project, "D1");
        Annotation annotation = newAnnotation(document);
        newReply(annotation);

        // The whole point: this used to fail on a foreign-key constraint,
        // which Spring Security then reported to the client as 403.
        boolean deleted = deletionService.deleteDocument(document.getId());

        assertThat(deleted).isTrue();
        assertThat(documentRepo.findById(document.getId())).isEmpty();
        assertThat(annotationRepo.findByDocument_Id(document.getId())).isEmpty();
        assertThat(replyRepo.findByAnnotation_IdOrderByCreatedAtAsc(annotation.getId())).isEmpty();
    }

    @Test
    @DisplayName("deletes a document that has no dependents")
    void deletesBareDocument() {
        Document document = newDocument(newProject("P2"), "D2");

        assertThat(deletionService.deleteDocument(document.getId())).isTrue();
        assertThat(documentRepo.findById(document.getId())).isEmpty();
    }

    @Test
    @DisplayName("reports false for a document that does not exist")
    void missingDocument() {
        assertThat(deletionService.deleteDocument(999_999L)).isFalse();
    }

    @Test
    @DisplayName("leaves other documents' annotations untouched")
    void doesNotTouchOtherDocuments() {
        Project project = newProject("P3");
        Document target   = newDocument(project, "target");
        Document bystander = newDocument(project, "bystander");
        newAnnotation(target);
        Annotation keep = newAnnotation(bystander);

        deletionService.deleteDocument(target.getId());

        assertThat(documentRepo.findById(bystander.getId())).isPresent();
        assertThat(annotationRepo.findByDocument_Id(bystander.getId()))
            .extracting(Annotation::getId).containsExactly(keep.getId());
    }

    @Test
    @DisplayName("deletes a project along with its documents and their dependents")
    void deletesProjectCascade() {
        Project project = newProject("P4");
        Document first  = newDocument(project, "first");
        Document second = newDocument(project, "second");
        Annotation annotation = newAnnotation(first);
        newReply(annotation);

        boolean deleted = deletionService.deleteProject(project.getId());

        assertThat(deleted).isTrue();
        assertThat(projectRepo.findById(project.getId())).isEmpty();
        assertThat(documentRepo.findById(first.getId())).isEmpty();
        assertThat(documentRepo.findById(second.getId())).isEmpty();
        assertThat(annotationRepo.findByDocument_Id(first.getId())).isEmpty();
        assertThat(replyRepo.findByAnnotation_IdOrderByCreatedAtAsc(annotation.getId())).isEmpty();
    }

    @Test
    @DisplayName("deletes an empty project")
    void deletesEmptyProject() {
        Project project = newProject("P5");

        assertThat(deletionService.deleteProject(project.getId())).isTrue();
        assertThat(projectRepo.findById(project.getId())).isEmpty();
    }

    @Test
    @DisplayName("reports false for a project that does not exist")
    void missingProject() {
        assertThat(deletionService.deleteProject(999_999L)).isFalse();
    }

    @Test
    @DisplayName("leaves other projects' documents untouched")
    void doesNotTouchOtherProjects() {
        Project target    = newProject("target");
        Project bystander = newProject("bystander");
        newDocument(target, "doomed");
        Document keep = newDocument(bystander, "kept");

        deletionService.deleteProject(target.getId());

        assertThat(projectRepo.findById(bystander.getId())).isPresent();
        assertThat(documentRepo.findByProject_Id(bystander.getId()))
            .extracting(Document::getId).containsExactly(keep.getId());
    }
}
