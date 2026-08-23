package com.cde.platform.cde.api;

import com.cde.platform.cde.api.RevisionDtos.ContainerTransitionResponse;
import com.cde.platform.cde.api.RevisionDtos.RevisionTransitionResult;
import com.cde.platform.cde.api.RevisionDtos.TransitionRequest;
import com.cde.platform.cde.model.ContainerRevision;
import com.cde.platform.cde.repository.ContainerRevisionRepository;
import com.cde.platform.cde.repository.ContainerStateTransitionRepository;
import com.cde.platform.cde.service.ContainerLifecycleService;
import com.cde.platform.exception.ResourceNotFoundException;
import com.cde.platform.model.User;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * State changes on a container revision, and the record of the ones already
 * made.
 *
 * <p>A transition is modelled as something you append to a revision rather than
 * as a field you set, because that is what it is: the state column is not
 * writable, and each move carries its own permission, validation, approval
 * record and audit row.
 */
@RestController
@RequestMapping("/api/cde/revisions/{revisionId}/transitions")
@Tag(name = ApiDocumentation.TAG_CDE)
@StandardErrorResponses
public class ContainerTransitionController {

    private final ContainerLifecycleService lifecycle;
    private final ContainerRevisionRepository revisionRepository;
    private final ContainerStateTransitionRepository transitionRepository;
    private final UserRepository userRepository;

    public ContainerTransitionController(ContainerLifecycleService lifecycle,
                                         ContainerRevisionRepository revisionRepository,
                                         ContainerStateTransitionRepository transitionRepository,
                                         UserRepository userRepository) {
        this.lifecycle = lifecycle;
        this.revisionRepository = revisionRepository;
        this.transitionRepository = transitionRepository;
        this.userRepository = userRepository;
    }

    @Operation(
        operationId = "listRevisionTransitions",
        summary = "Read a revision's transition history",
        description = """
            Every state change the revision has been through, oldest first, with who performed \
            it, when, and why.

            The history is append-only in the database rather than by convention: the \
            application holds no grant to update or delete a row in this table, so no code \
            path can rewrite it however privileged it is.

            Requires the `container:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The transition history, oldest first.")
    @ApiResponse(responseCode = "404",
        description = "No revision with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('container:read')")
    @GetMapping
    public List<ContainerTransitionResponse> listTransitions(
        @Parameter(description = "Identifier of the revision.", example = "2049")
        @PathVariable Long revisionId
    ) {
        requireRevision(revisionId);
        return transitionRepository.findByRevisionIdOrderByPerformedAtAsc(revisionId).stream()
            .map(CdeResponseMapper::toResponse)
            .toList();
    }

    @Operation(
        operationId = "transitionContainerRevision",
        summary = "Move a revision to a new state",
        description = """
            The only way a revision's state changes. Which moves are legal depends on where it \
            is now:

            - **work in progress** may be shared, or archived if the work is abandoned;
            - **shared** may be published, rejected back to work in progress, or archived;
            - **published** may only be archived, which happens as part of being superseded;
            - **archived** is terminal.

            Anything else returns `409` with a message naming both states.

            Each target state requires its own permission — `container:share`, \
            `container:publish`, `container:reject` or `container:archive` — and the check is \
            made against the state actually requested, not against the endpoint. Holding one \
            of them is enough to reach the endpoint; it is not enough to make a move you lack \
            the permission for.

            Publishing records the caller as the authorising party and the reason as the \
            authorisation. From that point the revision's content is frozen: the database \
            refuses any edit or deletion, and the way to change published information is a new \
            revision that supersedes it.""")
    @ApiResponse(responseCode = "200",
        description = "The revision after the change, and the transition recorded for it.")
    @ApiResponse(responseCode = "404",
        description = "No revision with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "409",
        description = "The state machine does not allow that move from the revision's current "
                    + "state.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The request failed validation — a missing target state, or a blank "
                    + "reason.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAnyAuthority('container:share', 'container:publish',"
                + " 'container:reject', 'container:archive')")
    @PostMapping
    public RevisionTransitionResult transition(
        @Parameter(description = "Identifier of the revision.", example = "2049")
        @PathVariable Long revisionId,
        @Valid @RequestBody TransitionRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        ContainerRevision revision = requireRevision(revisionId);
        User actor = currentUser(principal);

        // Dispatched here rather than inside the service, and that placement is
        // load-bearing. The permission for each move is enforced by an
        // annotation on the individual service method, and those annotations
        // are applied by a proxy — a switch inside the service calling its own
        // methods would not pass through it, and every check would silently do
        // nothing. Each branch below is a call from outside the bean.
        ContainerRevision moved = switch (request.toState()) {
            case SHARED -> lifecycle.share(revision, actor, request.reason());
            case WORK_IN_PROGRESS -> lifecycle.reject(revision, actor, request.reason());
            case PUBLISHED -> lifecycle.publish(revision, actor, request.reason());
            case ARCHIVED -> lifecycle.archive(revision, actor, request.reason());
        };

        var recorded = transitionRepository.findFirstByRevisionIdOrderByIdDesc(moved.getId())
            .orElseThrow(() -> new IllegalStateException(
                "A transition succeeded without recording itself, which the service writes in "
                + "the same transaction as the change."));

        return new RevisionTransitionResult(
            CdeResponseMapper.toResponse(moved), CdeResponseMapper.toResponse(recorded));
    }

    private ContainerRevision requireRevision(Long revisionId) {
        return revisionRepository.findById(revisionId)
            .orElseThrow(() -> new ResourceNotFoundException("No such revision."));
    }

    private User currentUser(UserDetails principal) {
        return userRepository.findByUsername(principal.getUsername())
            .orElseThrow(() -> new ResourceNotFoundException("No such user."));
    }
}
