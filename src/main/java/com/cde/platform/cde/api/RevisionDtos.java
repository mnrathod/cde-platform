package com.cde.platform.cde.api;

import com.cde.platform.cde.domain.ContainerState;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * The request and response bodies for container revisions and their state
 * transitions.
 */
public final class RevisionDtos {

    private RevisionDtos() {
    }

    @Schema(name = "RevisionRequest",
            description = """
                A new revision of a container, created in work in progress.

                Supply `supersedesRevisionId` to issue a revision that replaces the container's \
                current published one. That is the only way published information changes: the \
                superseded revision is archived rather than edited, and stays retrievable.""")
    public record RevisionRequest(

        @Schema(description = "The revision identifier, in the project's own scheme — "
                            + "preliminary or contractual. Validated for length and uniqueness "
                            + "within the container, not against a shipped format.",
                example = "P01.01",
                minLength = 1, maxLength = 20, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "A revision code is required.")
        @Size(max = 20, message = "A revision code may be at most 20 characters.")
        String revisionCode,

        @Schema(description = "The published revision this one replaces. Omit for the first "
                            + "revision of a container, or for a further revision that does not "
                            + "supersede anything.",
                example = "2048", nullable = true)
        Long supersedesRevisionId
    ) {
    }

    @Schema(name = "TransitionRequest",
            description = """
                A move to a new state.

                This is the only way a revision's state changes; there is no writable state \
                field. Which moves are legal depends on the current state — work in progress may \
                be shared or archived, shared may be published, rejected back to work in \
                progress, or archived, and published may only be superseded. An illegal move \
                returns `409`.""")
    public record TransitionRequest(

        @Schema(description = "The state to move to.",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "SHARED")
        @NotNull(message = "A target state is required.")
        ContainerState toState,

        @Schema(description = """
                Why. Required on every transition, including the ones where it might feel \
                like ceremony: this is the text that answers "on whose authority, and \
                why" years later, and a transition history of blank reasons is not an \
                audit trail. On a move to `PUBLISHED` it is recorded as the \
                authorisation.""",
                example = "Checked against the coordination model and approved for construction.",
                minLength = 1, maxLength = 1000, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "A reason is required for every state change.")
        @Size(max = 1000, message = "A reason may be at most 1000 characters.")
        String reason
    ) {
    }

    @Schema(name = "SuitabilityAssignmentRequest",
            description = "The suitability code to apply to a revision — what the information "
                        + "may be relied on for.")
    public record SuitabilityAssignmentRequest(

        @Schema(description = "The code to apply, from the project's own list. Send null to "
                            + "clear the current one.",
                example = "7", nullable = true)
        Long suitabilityCodeId
    ) {
    }

    @Schema(name = "RevisionResponse", description = "One revision of a container.")
    public record RevisionResponse(

        @Schema(description = "Identifier of the revision.", example = "2049")
        Long id,

        @Schema(description = "The container this revision belongs to.", example = "1024")
        Long containerId,

        @Schema(description = "The revision identifier in the project's scheme.",
                example = "P01.02")
        String revisionCode,

        @Schema(description = "The revision's current state.", example = "SHARED")
        ContainerState state,

        @Schema(description = "The suitability code applied, if any.",
                example = "S2", nullable = true)
        String suitabilityCode,

        @Schema(description = "The document holding this revision's content, where one is "
                            + "linked.", example = "512", nullable = true)
        Long documentId,

        @Schema(description = "Username of whoever created the revision.", example = "a.surveyor")
        String createdBy,

        @Schema(description = "When the revision was created.",
                format = "date-time", example = "2026-03-04T09:15:00")
        LocalDateTime createdAt,

        @Schema(description = "Username of whoever authorised publication. Null until published.",
                example = "l.reviewer", nullable = true)
        String publishedBy,

        @Schema(description = "When the revision was published. Null until published.",
                format = "date-time", example = "2026-03-11T16:40:00", nullable = true)
        LocalDateTime publishedAt,

        @Schema(description = "The authorisation recorded at publication.",
                example = "Approved for construction.", nullable = true)
        String approvalReason,

        @Schema(description = "The revision this one replaced, by its code.",
                example = "P01.01", nullable = true)
        String supersedesRevisionCode,

        @Schema(description = "The revision that replaced this one, by its code.",
                example = "C01", nullable = true)
        String supersededByRevisionCode,

        @Schema(description = "Whether this revision is the container's current authorised "
                            + "expression — published and not yet superseded. At most one "
                            + "revision of a container can be, and the database enforces that.",
                example = "false")
        boolean currentPublished
    ) {
    }

    @Schema(name = "ContainerTransitionResponse",
            description = "One recorded state change: who moved a revision, from what to what, "
                        + "when, and why. Append-only — the application holds no grant to "
                        + "rewrite or remove one.")
    public record ContainerTransitionResponse(

        @Schema(description = "Identifier of the transition record.", example = "8801")
        Long id,

        @Schema(description = "The state the revision was in.", example = "WORK_IN_PROGRESS")
        ContainerState fromState,

        @Schema(description = "The state it moved to.", example = "SHARED")
        ContainerState toState,

        @Schema(description = "Username of whoever performed the transition.",
                example = "a.surveyor")
        String performedBy,

        @Schema(description = "When it was performed.",
                format = "date-time", example = "2026-03-06T11:02:00")
        LocalDateTime performedAt,

        @Schema(description = "The reason given.",
                example = "Issued for coordination.", nullable = true)
        String reason
    ) {
    }

    @Schema(name = "RevisionTransitionResult",
            description = "The outcome of a state change: the revision as it now stands, and the "
                        + "transition recorded for it. Both, because a client needs the new "
                        + "state to render and the transition to append to a history it is "
                        + "already showing.")
    public record RevisionTransitionResult(

        @Schema(description = "The revision after the change.")
        RevisionResponse revision,

        @Schema(description = "The transition that was recorded.")
        ContainerTransitionResponse transition
    ) {
    }
}
