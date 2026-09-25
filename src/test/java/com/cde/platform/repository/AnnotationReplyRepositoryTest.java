package com.cde.platform.repository;

import com.cde.platform.model.Annotation;
import com.cde.platform.model.AnnotationReply;
import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a document's whole conversation without an N+1.
 *
 * <p>§7.3 says an N+1 is a build failure and asks for it to be detected in an
 * integration test. There is no query-counting dependency here and §0.3 says
 * to ask before adding one, so this uses Hibernate's own {@link Statistics},
 * which ships with the ORM and needs nothing installed.
 *
 * <p>The assertion is the shape that matters: the same statement count for
 * two replies as for twenty. Asserting "one query" alone would pass a
 * fetch-join that was silently dropped on a fixture small enough not to
 * notice, and asserting the rows come back would pass every version of this
 * code ever written — the original N+1 returned the right replies too.
 *
 * <p>What the count actually guards is the author join. Rendering a reply
 * reads its author's username, so a lazy association there turns one query
 * into one per reply — the same N+1 moved from HTTP down into SQL, where
 * nobody watching the network tab would see it. Verified by dropping it:
 * these tests fail.
 *
 * <p>The annotation join is a different case, and worth stating rather than
 * implying. Removing `fetch` from it passes every test here, because the
 * mapper reads only the annotation's id and a lazy proxy serves that without
 * a query. So the count does not guard it today. It guards the moment anyone
 * reads another field of the annotation, which is the point at which the
 * proxy would start costing a statement per reply.
 */
@SpringBootTest
@DisplayName("reading a document's replies")
class AnnotationReplyRepositoryTest {

    @Autowired AnnotationReplyRepository replyRepo;
    @Autowired AnnotationRepository annotationRepo;
    @Autowired DocumentRepository documentRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository userRepo;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired EntityManager entityManager;

    private Document sheet;
    private User author;

    @BeforeEach
    void setUp() {
        author = userRepo.findByUsername("reply-repository-user").orElseGet(() ->
            userRepo.save(User.builder()
                .username("reply-repository-user").email("reply-repository@example.com")
                .password("x").role(User.Role.ENGINEER).build()));

        Project project = projectRepo.save(Project.builder()
            .name("Replies").description("d")
            .phase(Project.ProjectPhase.DESIGN).build());

        sheet = documentRepo.save(Document.builder()
            .name("Sheet").fileName("sheet.pdf").fileType("application/pdf")
            .filePath("/tmp/sheet.pdf")
            .documentType(Document.DocumentType.DRAWING)
            .project(project).uploadedBy(author).build());
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** Creates `threads` pieces of markup, each carrying `repliesEach` replies. */
    private void conversation(int threads, int repliesEach) {
        for (int thread = 0; thread < threads; thread++) {
            Annotation markup = annotationRepo.save(Annotation.builder()
                .document(sheet).author(author)
                .type(Annotation.AnnotationType.HIGHLIGHT)
                .shapeData("{}").comment("Thread " + thread).pageNumber(1)
                .status(Annotation.AnnotationStatus.OPEN)
                .createdAt(LocalDateTime.now())
                .build());
            for (int reply = 0; reply < repliesEach; reply++) {
                replyRepo.save(AnnotationReply.builder()
                    .annotation(markup).author(author)
                    .content("Reply " + reply)
                    .createdAt(LocalDateTime.now().plusSeconds(reply))
                    .build());
            }
        }
    }

    /**
     * Reads the whole conversation and reports how many statements it took,
     * touching every field the response mapper touches.
     *
     * <p>Cleared first so the fixture's own inserts are not counted, and the
     * persistence context is cleared so nothing is served from it — a first-
     * level cache hit would hide exactly the lazy load being counted.
     */
    private long statementsToReadEverything() {
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = statistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        for (AnnotationReply reply : replyRepo.findForDocument(sheet.getId())) {
            reply.getAnnotation().getId();
            reply.getAuthor().getUsername();
            reply.getContent();
        }

        return statistics.getPrepareStatementCount();
    }

    @Test
    @Transactional
    @DisplayName("costs the same whether there are two replies or twenty")
    void readingIsConstantCost() {
        // The assertion §7.3 asks for. Two threads of one reply, then ten of
        // two: five times the rows and ten times the threads, and the number
        // of statements must not move.
        conversation(2, 1);
        long forTwo = statementsToReadEverything();

        conversation(10, 2);
        long forTwentyTwo = statementsToReadEverything();

        assertThat(forTwentyTwo)
            .as("a fetch join was dropped — reading %d replies took %d statements "
                + "where reading %d took %d", 22, forTwentyTwo, 2, forTwo)
            .isEqualTo(forTwo);
    }

    @Test
    @Transactional
    @DisplayName("takes a single statement")
    void readingTakesOneStatement() {
        conversation(5, 3);

        assertThat(statementsToReadEverything()).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("returns every reply on the document, across all its markup")
    void returnsEveryReply() {
        conversation(3, 4);

        assertThat(replyRepo.findForDocument(sheet.getId())).hasSize(12);
    }

    @Test
    @Transactional
    @DisplayName("returns them oldest first, so a thread reads in order")
    void returnsThemInOrder() {
        conversation(1, 3);

        List<AnnotationReply> replies = replyRepo.findForDocument(sheet.getId());

        assertThat(replies).map(AnnotationReply::getContent)
            .containsExactly("Reply 0", "Reply 1", "Reply 2");
    }

    @Test
    @Transactional
    @DisplayName("does not return another document's replies")
    void doesNotCrossDocuments() {
        conversation(2, 2);
        Document other = documentRepo.save(Document.builder()
            .name("Other").fileName("other.pdf").fileType("application/pdf")
            .filePath("/tmp/other.pdf").documentType(Document.DocumentType.DRAWING)
            .project(sheet.getProject()).uploadedBy(author).build());
        Annotation elsewhere = annotationRepo.save(Annotation.builder()
            .document(other).author(author)
            .type(Annotation.AnnotationType.HIGHLIGHT)
            .shapeData("{}").comment("Elsewhere").pageNumber(1)
            .status(Annotation.AnnotationStatus.OPEN)
            .createdAt(LocalDateTime.now()).build());
        replyRepo.save(AnnotationReply.builder()
            .annotation(elsewhere).author(author).content("Not this document")
            .createdAt(LocalDateTime.now()).build());

        assertThat(replyRepo.findForDocument(sheet.getId()))
            .map(AnnotationReply::getContent)
            .doesNotContain("Not this document")
            .hasSize(4);
    }

    @Test
    @Transactional
    @DisplayName("a document with markup but no replies comes back empty, not missing")
    void noRepliesIsEmpty() {
        conversation(3, 0);

        assertThat(replyRepo.findForDocument(sheet.getId())).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("a document that does not exist comes back empty rather than failing")
    void unknownDocumentIsEmpty() {
        assertThat(replyRepo.findForDocument(9_999_999L)).isEmpty();
    }
}
