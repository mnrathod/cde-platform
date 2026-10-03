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
import org.springframework.security.test.context.support.WithMockUser;

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
// Every lifecycle operation demands its own permission, so a test calling the
// service directly has to hold them — as this one does, because what it is
// about is the state machine and the database, not the authorisation. That the
// permissions are enforced at all is asserted in
// ContainerPermissionEnforcementTest, and asserted by their absence: without
// this annotation every test here fails on missing credentials, which is the
// check working rather than a fixture problem.
@WithMockUser(username = "cde-lifecycle-test",
              authorities = { "container:read", "container:write", "container:share",
                              "container:publish", "container:reject", "container:archive" })
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

    // ── Suitability codes ─────────────────────────────────────────────────
    //
    // The code says what the information may be relied on for, which is the
    // part of ISO 19650 that carries contractual weight: "approved for
    // construction" on a drawing nobody has checked is the confusion the code
    // list exists to prevent. These assert the three ways a code can be wrong
    // for the revision it is being put on, each of which is a different
    // mistake with a different answer.

    @Nested
    @DisplayName("marking a revision with a suitability code")
    class SuitabilityCodes {

        @Autowired com.cde.platform.cde.repository.SuitabilityCodeRepository codeRepo;

        private com.cde.platform.cde.model.SuitabilityCode code(
                String name, ContainerState validInState, boolean active) {
            return codeRepo.save(com.cde.platform.cde.model.SuitabilityCode.builder()
                .project(container.getProject())
                .code(name + "-" + System.nanoTime())
                .description("A code for " + name)
                .validInState(validInState)
                .active(active)
                .build());
        }

        @Test
        @DisplayName("a code valid in any state goes on a revision in progress")
        void anyStateCodeIsAccepted() {
            ContainerRevision revision =
                lifecycle.startWorkInProgress(container, "P01.01", author);

            ContainerRevision marked =
                lifecycle.assignSuitabilityCode(revision, code("S0", null, true));

            assertThat(marked.getSuitabilityCode()).isNotNull();
        }

        @Test
        @DisplayName("a code restricted to the revision's own state is accepted")
        void matchingStateCodeIsAccepted() {
            ContainerRevision revision =
                lifecycle.startWorkInProgress(container, "P01.01", author);

            ContainerRevision marked = lifecycle.assignSuitabilityCode(
                revision, code("S1", ContainerState.WORK_IN_PROGRESS, true));

            assertThat(marked.getSuitabilityCode()).isNotNull();
        }

        @Test
        @DisplayName("a code restricted to a different state is refused, naming both")
        void codeFromTheWrongStateIsRefused() {
            // The whole point of the restriction. A reviewer reading "suitable
            // for construction" has no way to tell it was applied to unverified
            // work in progress, so the check has to be here rather than in the
            // reader's judgement.
            ContainerRevision revision =
                lifecycle.startWorkInProgress(container, "P01.01", author);

            assertThatThrownBy(() -> lifecycle.assignSuitabilityCode(
                    revision, code("S4", ContainerState.PUBLISHED, true)))
                .isInstanceOf(com.cde.platform.cde.domain
                    .SuitabilityCodeNotValidInStateException.class)
                // Both states named, in prose rather than enum constants: the
                // reader has to be able to see which code was applied to which
                // revision without looking either up.
                .hasMessageContaining("published")
                .hasMessageContaining("work in progress");
        }

        @Test
        @DisplayName("a code the project has retired is refused")
        void retiredCodeIsRefused() {
            // Codes are tenant- and project-populated (§6.7.2), so they get
            // superseded as a project's conventions change. Retiring one has to
            // stop it being applied to anything new without invalidating the
            // revisions already carrying it.
            ContainerRevision revision =
                lifecycle.startWorkInProgress(container, "P01.01", author);

            assertThatThrownBy(() -> lifecycle.assignSuitabilityCode(
                    revision, code("S2", null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer in use");
        }

        @Test
        @DisplayName("clearing the code is allowed while the revision is still mutable")
        void clearingTheCodeIsAllowed() {
            ContainerRevision revision =
                lifecycle.startWorkInProgress(container, "P01.01", author);
            lifecycle.assignSuitabilityCode(revision, code("S0", null, true));

            assertThat(lifecycle.assignSuitabilityCode(revision, null).getSuitabilityCode())
                .isNull();
        }

        @Test
        @DisplayName("a published revision's code cannot be changed")
        void publishedCodeIsFrozen() {
            // The label is part of the frozen contractual record. The database
            // trigger refuses the write too, but reaching it would report a
            // deliberate rule as an internal fault.
            ContainerRevision published = publishedRevision("C01");

            assertThatThrownBy(() -> lifecycle.assignSuitabilityCode(
                    published, code("S4", null, true)))
                .isInstanceOf(StateTransitionNotPermittedException.class);
        }

        @Test
        @DisplayName("an archived revision's code cannot be changed either")
        void archivedCodeIsFrozen() {
            ContainerRevision abandoned =
                lifecycle.startWorkInProgress(container, "P01.01", author);
            ContainerRevision archived =
                lifecycle.archive(abandoned, author, "Scheme abandoned");

            assertThatThrownBy(() -> lifecycle.assignSuitabilityCode(archived, null))
                .isInstanceOf(StateTransitionNotPermittedException.class);
        }

        @Test
        @DisplayName("clearing the code on a published revision is refused like setting one")
        void clearingAPublishedCodeIsAlsoRefused() {
            // Removing the label from the contractual record is the same edit
            // as changing it, and a null argument must not slip past the state
            // check on its way to the setter.
            ContainerRevision published = publishedRevision("C01");

            assertThatThrownBy(() -> lifecycle.assignSuitabilityCode(published, null))
                .isInstanceOf(StateTransitionNotPermittedException.class);
        }
    }

    // ── Publication and archival guards ───────────────────────────────────

    @Nested
    @DisplayName("the guards on publishing and archiving")
    class PublicationGuards {

        @Test
        @DisplayName("publishing without a recorded approver is refused")
        void publicationNeedsAnApprover() {
            // A published revision is the contractual record and a database
            // CHECK refuses one with no approver; failing here names the
            // missing thing instead of surfacing a constraint violation.
            ContainerRevision shared = lifecycle.share(
                lifecycle.startWorkInProgress(container, "P01.01", author),
                author, "Ready");

            assertThatThrownBy(() -> lifecycle.publish(shared, null, "Approved"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approver");
        }

        @Test
        @DisplayName("a rejection reason of nothing but whitespace is refused")
        void whitespaceRejectionReasonIsRefused() {
            // The author has to know what to change, and "   " tells them
            // nothing — it is the same omission as sending no reason at all.
            ContainerRevision shared = lifecycle.share(
                lifecycle.startWorkInProgress(container, "P01.01", author),
                author, "Ready");

            assertThatThrownBy(() -> lifecycle.reject(shared, author, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        }

        @Test
        @DisplayName("an unpublished revision cannot be superseded — there is nothing to replace")
        void onlyAPublishedRevisionIsSuperseded() {
            // Supersession retires the contractual record. Work in progress is
            // not one, and superseding it would archive work that was never
            // issued while creating a replacement for nothing.
            ContainerRevision inProgress =
                lifecycle.startWorkInProgress(container, "P01.01", author);

            assertThatThrownBy(() -> lifecycle.supersede(inProgress, "P02.01", author))
                .isInstanceOf(StateTransitionNotPermittedException.class);
        }

        @Test
        @DisplayName("a shared revision cannot be superseded either")
        void sharedRevisionCannotBeSuperseded() {
            ContainerRevision shared = lifecycle.share(
                lifecycle.startWorkInProgress(container, "P01.01", author),
                author, "Ready");

            assertThatThrownBy(() -> lifecycle.supersede(shared, "P02.01", author))
                .isInstanceOf(StateTransitionNotPermittedException.class);
        }

        @Test
        @DisplayName("abandoned work in progress can be archived without being published")
        void abandonedWorkCanBeArchived() {
            // Not every revision becomes a record. Work that will not proceed
            // still has to leave the active set, and the only terminal state is
            // archived.
            //
            // This did not work. A CHECK constraint demanded an approver for
            // every ARCHIVED row as well as every PUBLISHED one, so archiving
            // something that was never authorised raised a constraint violation
            // — a 500, for an operation the service documents as its purpose.
            // V9 narrows the constraint to publication, which is what its own
            // comment describes.
            ContainerRevision abandoned =
                lifecycle.startWorkInProgress(container, "P01.01", author);

            assertThat(lifecycle.archive(abandoned, author, "Scheme abandoned").getState())
                .isEqualTo(ContainerState.ARCHIVED);
        }

        @Test
        @DisplayName("an archived revision that was published keeps its approver")
        void archivedPublishedRevisionKeepsItsApprover() {
            // The guarantee V9 had to preserve while relaxing the CHECK that
            // demanded an approver on every archived row. It is preserved by the
            // trigger rather than by the constraint: any UPDATE of a row whose
            // state is PUBLISHED or ARCHIVED is refused if it touches
            // published_by, published_at or approval_reason. So the path the old
            // constraint was imagined to close — publish, archive, then null the
            // approver — is still closed, and this is the test that says so
            // rather than leaving it asserted in a migration comment.
            ContainerRevision published = publishedRevision("C01");
            lifecycle.supersede(published, "C02", author);

            ContainerRevision archived = revisionRepo.findById(published.getId()).orElseThrow();
            assertThat(archived.getState()).isEqualTo(ContainerState.ARCHIVED);
            assertThat(archived.getPublishedBy()).isNotNull();

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE container_revisions SET published_by = NULL WHERE id = ?",
                    archived.getId()))
                .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("a revision archived without publication records no approver")
        void archivedUnpublishedRevisionHasNoApprover() {
            // The other half: the field stays empty rather than being filled in
            // to satisfy a constraint. Recording a named person as having
            // authorised information nobody authorised would be a false entry in
            // the column a contractual dispute reads first.
            ContainerRevision abandoned =
                lifecycle.startWorkInProgress(container, "P01.01", author);

            ContainerRevision archived =
                lifecycle.archive(abandoned, author, "Scheme abandoned");

            assertThat(archived.getPublishedBy()).isNull();
            assertThat(archived.getPublishedAt()).isNull();
        }

        @Test
        @DisplayName("a shared revision that will not proceed can be archived")
        void sharedRevisionCanBeArchived() {
            ContainerRevision shared = lifecycle.share(
                lifecycle.startWorkInProgress(container, "P01.01", author),
                author, "Ready");

            assertThat(lifecycle.archive(shared, author, "Superseded by a change of scope")
                .getState()).isEqualTo(ContainerState.ARCHIVED);
        }
    }
}
