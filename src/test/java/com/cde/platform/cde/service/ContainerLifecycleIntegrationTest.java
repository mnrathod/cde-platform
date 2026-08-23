package com.cde.platform.cde.service;

import com.cde.platform.cde.domain.ContainerState;
import com.cde.platform.cde.domain.StateTransitionNotPermittedException;
import com.cde.platform.cde.model.ContainerRevision;
import com.cde.platform.cde.model.ContainerStateTransition;
import com.cde.platform.cde.model.InformationContainer;
import com.cde.platform.cde.repository.ContainerRevisionRepository;
import com.cde.platform.cde.repository.ContainerStateTransitionRepository;
import com.cde.platform.cde.repository.InformationContainerRepository;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CDE lifecycle against a real database.
 *
 * <p>The immutability assertions here are the ones that matter most, and they
 * deliberately go around the service to reach the row directly. A test that
 * only proved {@code ContainerLifecycleService} refuses to edit a published
 * revision would prove that one class behaves — not that the record is safe
 * from the repository method somebody adds next year, or from a bulk update in
 * a migration. What is asserted is that the database refuses, whoever asks.
 */
@SpringBootTest
class ContainerLifecycleIntegrationTest {

    @Autowired ContainerLifecycleService            lifecycle;
    @Autowired InformationContainerRepository       containerRepo;
    @Autowired ContainerRevisionRepository          revisionRepo;
    @Autowired ContainerStateTransitionRepository   transitionRepo;
    @Autowired ProjectRepository                    projectRepo;
    @Autowired UserRepository                       userRepo;
    @Autowired JdbcTemplate                         jdbcTemplate;

    private InformationContainer container;
    private User author;

    @BeforeEach
    void createAContainer() {
        long unique = System.nanoTime();
        author = userRepo.save(User.builder()
            .username("cde-author-" + unique)
            .email("author-" + unique + "@example.test")
            .password("{noop}irrelevant")
            .role(User.Role.ENGINEER)
            .build());

        Project project = projectRepo.save(
            Project.builder().name("Bridge " + unique).owner(author).build());

        container = containerRepo.save(InformationContainer.builder()
            .project(project)
            .containerReference("PRJ-ORG-XX-00-DR-A-" + unique)
            .createdBy(author)
            .build());
    }

    private ContainerRevision publishedRevision(String code) {
        ContainerRevision revision = lifecycle.startWorkInProgress(container, code, author);
        revision = lifecycle.share(revision, author, "Ready for coordination");
        return lifecycle.publish(revision, author, "Approved for construction");
    }

    @Nested
    @DisplayName("the lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("a revision moves work in progress -> shared -> published")
        void theHappyPath() {
            ContainerRevision revision = lifecycle.startWorkInProgress(container, "P01.01", author);
            assertThat(revision.getState()).isEqualTo(ContainerState.WORK_IN_PROGRESS);

            revision = lifecycle.share(revision, author, "Ready for coordination");
            assertThat(revision.getState()).isEqualTo(ContainerState.SHARED);

            revision = lifecycle.publish(revision, author, "Approved for construction");
            assertThat(revision.getState()).isEqualTo(ContainerState.PUBLISHED);
            assertThat(revision.getPublishedBy()).isNotNull();
            assertThat(revision.getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("work in progress cannot be published without being shared")
        void cannotSkipCoordination() {
            ContainerRevision revision = lifecycle.startWorkInProgress(container, "P01.01", author);

            assertThatThrownBy(() -> lifecycle.publish(revision, author, "Straight to site"))
                .isInstanceOf(StateTransitionNotPermittedException.class);
        }

        @Test
        @DisplayName("a rejection must say why")
        void rejectionRequiresAReason() {
            ContainerRevision revision =
                lifecycle.share(lifecycle.startWorkInProgress(container, "P01.01", author),
                                author, "For review");

            assertThatThrownBy(() -> lifecycle.reject(revision, author, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        }

        @Test
        @DisplayName("a rejected revision returns to its author, not to limbo")
        void rejectionReturnsToWorkInProgress() {
            ContainerRevision revision =
                lifecycle.share(lifecycle.startWorkInProgress(container, "P01.01", author),
                                author, "For review");

            revision = lifecycle.reject(revision, author, "Grid references do not match the survey");

            assertThat(revision.getState()).isEqualTo(ContainerState.WORK_IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("a published revision is immutable — enforced by the database")
    class Immutability {

        @Test
        @DisplayName("its content cannot be changed, even bypassing the service")
        void contentCannotBeUpdated() {
            ContainerRevision published = publishedRevision("P01.01");

            assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE container_revisions SET file_path = ? WHERE id = ?",
                                    "/tampered", published.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("cannot be modified");
        }

        @Test
        @DisplayName("it cannot be deleted")
        void cannotBeDeleted() {
            ContainerRevision published = publishedRevision("P01.01");

            assertThatThrownBy(() ->
                jdbcTemplate.update("DELETE FROM container_revisions WHERE id = ?", published.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("cannot be deleted");
        }

        @Test
        @DisplayName("it cannot be un-published")
        void cannotReturnToWorkInProgress() {
            ContainerRevision published = publishedRevision("P01.01");

            assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE container_revisions SET state = ? WHERE id = ?",
                                    "WORK_IN_PROGRESS", published.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("cannot change state");
        }

        @Test
        @DisplayName("the approval record cannot be rewritten")
        void approvalCannotBeForged() {
            ContainerRevision published = publishedRevision("P01.01");

            // Who authorised a contractual record is exactly the fact somebody
            // would want to change after the fact.
            assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE container_revisions SET approval_reason = ? WHERE id = ?",
                                    "Someone else approved this", published.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("cannot be modified");
        }

        @Test
        @DisplayName("but an unpublished revision is freely editable — the control is targeted")
        void workInProgressRemainsEditable() {
            // The control for the four tests above: without it, a trigger that
            // rejected every update would pass all of them and break the
            // product.
            ContainerRevision draft = lifecycle.startWorkInProgress(container, "P01.01", author);

            int rowsChanged = jdbcTemplate.update(
                "UPDATE container_revisions SET file_path = ? WHERE id = ?",
                "/drafts/plan.pdf", draft.getId());

            assertThat(rowsChanged).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("supersession")
    class Supersession {

        @Test
        @DisplayName("a new revision replaces the published one, which is archived not deleted")
        void supersedingArchivesRatherThanRemoves() {
            ContainerRevision first = publishedRevision("P01.01");

            ContainerRevision second = lifecycle.supersede(first, "P02.01", author);

            ContainerRevision reloadedFirst = revisionRepo.findById(first.getId()).orElseThrow();

            assertThat(reloadedFirst.getState()).isEqualTo(ContainerState.ARCHIVED);
            assertThat(second.getState()).isEqualTo(ContainerState.WORK_IN_PROGRESS);
            // The whole point: the earlier revision is still there to be read.
            assertThat(revisionRepo.findById(first.getId())).isPresent();
        }

        @Test
        @DisplayName("lineage is walkable from either end")
        void lineageIsRecordedBothWays() {
            ContainerRevision first = publishedRevision("P01.01");
            ContainerRevision second = lifecycle.supersede(first, "P02.01", author);

            ContainerRevision reloadedFirst = revisionRepo.findById(first.getId()).orElseThrow();
            ContainerRevision reloadedSecond = revisionRepo.findById(second.getId()).orElseThrow();

            assertThat(reloadedFirst.getSupersededBy().getId()).isEqualTo(second.getId());
            assertThat(reloadedSecond.getSupersedes().getId()).isEqualTo(first.getId());
        }

        @Test
        @DisplayName("a revision cannot be superseded twice")
        void supersessionHappensOnce() {
            ContainerRevision first = publishedRevision("P01.01");
            lifecycle.supersede(first, "P02.01", author);

            // Superseding archives the old revision, so the second attempt is
            // refused by the state machine before the already-superseded check
            // is reached. Either refusal is correct; what matters is that a
            // second replacement cannot be issued for the same revision, which
            // would fork the lineage.
            assertThatThrownBy(() -> lifecycle.supersede(first, "P03.01", author))
                .isInstanceOf(StateTransitionNotPermittedException.class);

            assertThat(revisionRepo.findByContainerIdOrderByCreatedAtAsc(container.getId()))
                .extracting(ContainerRevision::getRevisionCode)
                .containsExactly("P01.01", "P02.01");
        }

        @Test
        @DisplayName("only one revision of a container is current at a time")
        void onlyOneCurrentPublishedRevision() {
            publishedRevision("P01.01");

            // Two simultaneously-published, unsuperseded revisions would make
            // "which one is the contractual record" unanswerable. The partial
            // unique index refuses.
            assertThatThrownBy(() -> publishedRevision("P02.01"))
                .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("the audit trail")
    class Audit {

        @Test
        @DisplayName("every state change is recorded with its actor and reason")
        void everyTransitionIsRecorded() {
            ContainerRevision revision = lifecycle.startWorkInProgress(container, "P01.01", author);
            revision = lifecycle.share(revision, author, "Ready for coordination");
            revision = lifecycle.publish(revision, author, "Approved for construction");

            List<ContainerStateTransition> history = lifecycle.historyOf(revision);

            assertThat(history).hasSize(3);
            assertThat(history).extracting(ContainerStateTransition::getToState)
                .containsExactly(ContainerState.WORK_IN_PROGRESS,
                                 ContainerState.SHARED,
                                 ContainerState.PUBLISHED);
            assertThat(history).allSatisfy(entry -> {
                assertThat(entry.getPerformedBy()).isNotNull();
                assertThat(entry.getPerformedAt()).isNotNull();
            });
        }

        @Test
        @DisplayName("a recorded transition cannot be altered by the application at all")
        void theTrailIsAppendOnly() {
            ContainerRevision revision =
                lifecycle.share(lifecycle.startWorkInProgress(container, "P01.01", author),
                                author, "For review");
            Long transitionId = lifecycle.historyOf(revision).get(0).getId();

            // Not a service-level rule: UPDATE and DELETE are revoked from the
            // application role, so no code path can rewrite history however
            // privileged it is inside the application.
            assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE container_state_transitions SET reason = ? WHERE id = ?",
                                    "something else entirely", transitionId))
                .isInstanceOf(DataAccessException.class);

            assertThatThrownBy(() ->
                jdbcTemplate.update("DELETE FROM container_state_transitions WHERE id = ?", transitionId))
                .isInstanceOf(DataAccessException.class);
        }
    }
}
