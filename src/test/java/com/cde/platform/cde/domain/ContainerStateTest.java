package com.cde.platform.cde.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static com.cde.platform.cde.domain.ContainerState.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transition rules, tested without a database or a Spring context, because
 * they are rules rather than plumbing.
 */
class ContainerStateTest {

    @Nested
    @DisplayName("the permitted moves")
    class PermittedMoves {

        @Test
        void workInProgressMayBeSharedOrAbandoned() {
            assertThat(WORK_IN_PROGRESS.permittedTransitions())
                .containsExactlyInAnyOrder(SHARED, ARCHIVED);
        }

        @Test
        @DisplayName("work in progress cannot be published without being shared first")
        void workInProgressCannotSkipReview() {
            // Skipping the coordination step is how unreviewed information
            // becomes the contractual record.
            assertThat(WORK_IN_PROGRESS.canTransitionTo(PUBLISHED)).isFalse();
        }

        @Test
        void sharedMayBePublishedRejectedOrAbandoned() {
            assertThat(SHARED.permittedTransitions())
                .containsExactlyInAnyOrder(PUBLISHED, WORK_IN_PROGRESS, ARCHIVED);
        }

        @Test
        @DisplayName("publication is one-way: the only move out is to archive")
        void publishedMayOnlyBeArchived() {
            assertThat(PUBLISHED.permittedTransitions()).containsExactly(ARCHIVED);
            assertThat(PUBLISHED.canTransitionTo(WORK_IN_PROGRESS)).isFalse();
            assertThat(PUBLISHED.canTransitionTo(SHARED)).isFalse();
        }

        @Test
        @DisplayName("archived is terminal — it is the historical record")
        void archivedHasNoWayOut() {
            assertThat(ARCHIVED.permittedTransitions()).isEmpty();
            assertThat(ARCHIVED.isTerminal()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(ContainerState.class)
        @DisplayName("no state transitions to itself")
        void noSelfTransitions(ContainerState state) {
            assertThat(state.canTransitionTo(state)).isFalse();
        }
    }

    @Nested
    @DisplayName("mutability")
    class Mutability {

        @Test
        void contentMayBeEditedBeforePublication() {
            assertThat(WORK_IN_PROGRESS.isMutable()).isTrue();
            assertThat(SHARED.isMutable()).isTrue();
        }

        @Test
        @DisplayName("nothing is editable from publication onward")
        void publishedAndArchivedAreFrozen() {
            assertThat(PUBLISHED.isMutable()).isFalse();
            assertThat(ARCHIVED.isMutable()).isFalse();
        }
    }

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        @Test
        @DisplayName("only publication demands a recorded approver")
        void publicationRequiresApproval() {
            assertThat(PUBLISHED.requiresRecordedApproval()).isTrue();
            assertThat(SHARED.requiresRecordedApproval()).isFalse();
            assertThat(WORK_IN_PROGRESS.requiresRecordedApproval()).isFalse();
            assertThat(ARCHIVED.requiresRecordedApproval()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(ContainerState.class)
        @DisplayName("every state names the permission required to reach it")
        void everyStateHasAPermission(ContainerState state) {
            // There is no default-allow: a transition with no permission
            // defined would be one any authenticated caller could perform.
            assertThat(state.requiredPermission()).isNotBlank().startsWith("container:");
        }

        @Test
        void eachTransitionHasItsOwnPermission() {
            Set<String> permissions = Set.of(
                WORK_IN_PROGRESS.requiredPermission(),
                SHARED.requiredPermission(),
                PUBLISHED.requiredPermission(),
                ARCHIVED.requiredPermission());

            // Distinct, so publishing cannot be granted by giving somebody the
            // ability to share.
            assertThat(permissions).hasSize(4);
        }
    }

    @Nested
    @DisplayName("the refusal explains itself")
    class Messages {

        @Test
        void publishedToWorkInProgressSaysToSupersedeInstead() {
            var thrown = new StateTransitionNotPermittedException(PUBLISHED, WORK_IN_PROGRESS);

            assertThat(thrown.getMessage())
                .contains("supersede")
                .doesNotContain("Exception")
                .doesNotContain("com.cde");
        }

        @Test
        void archivedSaysItIsTheHistoricalRecord() {
            var thrown = new StateTransitionNotPermittedException(ARCHIVED, SHARED);
            assertThat(thrown.getMessage()).contains("historical record");
        }

        @ParameterizedTest
        @EnumSource(ContainerState.class)
        @DisplayName("no message leaks a class name or a stack frame")
        void messagesAreSafeToShowAUser(ContainerState from) {
            for (ContainerState to : ContainerState.values()) {
                String message = new StateTransitionNotPermittedException(from, to).getMessage();
                assertThat(message).doesNotContain("com.cde").doesNotContain("java.");
            }
        }
    }
}
