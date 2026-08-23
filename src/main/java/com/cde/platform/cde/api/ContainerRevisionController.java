package com.cde.platform.cde.api;

import com.cde.platform.cde.api.RevisionDtos.RevisionRequest;
import com.cde.platform.cde.api.RevisionDtos.RevisionResponse;
import com.cde.platform.cde.api.RevisionDtos.SuitabilityAssignmentRequest;
import com.cde.platform.cde.model.ContainerRevision;
import com.cde.platform.cde.repository.ContainerRevisionRepository;
import com.cde.platform.cde.repository.InformationContainerRepository;
import com.cde.platform.cde.repository.SuitabilityCodeRepository;
import com.cde.platform.cde.service.ContainerLifecycleService;
import com.cde.platform.exception.ResourceConflictException;
import com.cde.platform.exception.ResourceNotFoundException;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * Revisions of an information container: what actually carries state, content
 * and a place in the supersession lineage.
 */
@RestController
@RequestMapping("/api/cde")
@Tag(name = ApiDocumentation.TAG_CDE)
@StandardErrorResponses
public class ContainerRevisionController {

    private final ContainerLifecycleService lifecycle;
    private final ContainerRevisionRepository revisionRepository;
    private final InformationContainerRepository containerRepository;
    private final SuitabilityCodeRepository suitabilityCodeRepository;
    private final UserRepository userRepository;

    public ContainerRevisionController(ContainerLifecycleService lifecycle,
                                       ContainerRevisionRepository revisionRepository,
                                       InformationContainerRepository containerRepository,
                                       SuitabilityCodeRepository suitabilityCodeRepository,
                                       UserRepository userRepository) {
        this.lifecycle = lifecycle;
        this.revisionRepository = revisionRepository;
        this.containerRepository = containerRepository;
        this.suitabilityCodeRepository = suitabilityCodeRepository;
        this.userRepository = userRepository;
    }

    @Operation(
        operationId = "listContainerRevisions",
        summary = "List a container's revisions",
        description = """
            Every revision the container has ever had, oldest first, including archived ones. \
            Superseded revisions are never removed: a published revision is the contractual \
            record and stays retrievable for the operational life of the asset.

            Requires the `container:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The container's revisions, oldest first.")
    @ApiResponse(responseCode = "404",
        description = "No container with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('container:read')")
    @GetMapping("/containers/{containerId}/revisions")
    public List<RevisionResponse> listRevisions(
        @Parameter(description = "Identifier of the container.", example = "1024")
        @PathVariable Long containerId
    ) {
        requireContainer(containerId);
        return revisionRepository.findByContainerIdOrderByCreatedAtAsc(containerId).stream()
            .map(CdeResponseMapper::toResponse)
            .toList();
    }

    @Operation(
        operationId = "createContainerRevision",
        summary = "Issue a new revision of a container",
        description = """
            Creates a revision in work in progress — visible to the originating task team, and \
            not yet issued for coordination or approved for use.

            Supplying `supersedesRevisionId` supersedes the container's published revision: \
            the new revision inherits its content, and the superseded one is archived in the \
            same transaction, with the lineage recorded in both directions. This is the only \
            way published information changes. Superseding requires both `container:write` \
            and `container:archive`, and is refused if that revision is not published or has \
            already been superseded.

            Requires the `container:write` permission.""")
    @ApiResponse(responseCode = "201", description = "The revision as created.")
    @ApiResponse(responseCode = "404",
        description = "No such container, or no such revision of it.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "409",
        description = "The container already has a revision with that code, or the revision "
                    + "being superseded is not published or is already superseded.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The revision details failed validation — a blank code, or one over its "
                    + "length limit.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('container:write')")
    @PostMapping("/containers/{containerId}/revisions")
    public ResponseEntity<RevisionResponse> createRevision(
        @Parameter(description = "Identifier of the container.", example = "1024")
        @PathVariable Long containerId,
        @Valid @RequestBody RevisionRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        var container = requireContainer(containerId);
        var author = currentUser(principal);

        revisionRepository.findByContainerIdAndRevisionCode(containerId, request.revisionCode())
            .ifPresent(existing -> {
                throw new ResourceConflictException(
                    "This container already has revision " + request.revisionCode()
                    + ". Revision codes identify a revision within its container and are not "
                    + "reused.");
            });

        ContainerRevision created = request.supersedesRevisionId() == null
            ? lifecycle.startWorkInProgress(container, request.revisionCode(), author)
            : lifecycle.supersede(
                requireRevisionOf(containerId, request.supersedesRevisionId()),
                request.revisionCode(), author);

        return ResponseEntity
            .created(URI.create("/api/cde/revisions/" + created.getId()))
            .body(CdeResponseMapper.toResponse(created));
    }

    @Operation(
        operationId = "getContainerRevision",
        summary = "Read one revision",
        description = """
            Requires the `container:read` permission.

            A revision in another tenant reports `404`, not `403`.""")
    @ApiResponse(responseCode = "200", description = "The revision.")
    @ApiResponse(responseCode = "404",
        description = "No revision with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('container:read')")
    @GetMapping("/revisions/{revisionId}")
    public RevisionResponse getRevision(
        @Parameter(description = "Identifier of the revision.", example = "2049")
        @PathVariable Long revisionId
    ) {
        return CdeResponseMapper.toResponse(requireRevision(revisionId));
    }

    @Operation(
        operationId = "setRevisionSuitabilityCode",
        summary = "Set a revision's suitability code",
        description = """
            Marks what the information may be relied on for, using a code the project defined \
            for itself. Send a null `suitabilityCodeId` to clear it.

            Idempotent: setting the same code twice leaves the revision as it was.

            Only while the revision is still work in progress or shared. After publication the \
            label is part of the frozen record, and the request is refused with `409` — the \
            way to change it is a new revision that supersedes this one.

            Requires the `container:write` permission.""")
    @ApiResponse(responseCode = "200", description = "The revision as it now stands.")
    @ApiResponse(responseCode = "404",
        description = "No such revision, or no such suitability code.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "409",
        description = "The revision is published or archived, or the code does not apply to "
                    + "information in the revision's current state.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The request body could not be validated.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('container:write')")
    @PutMapping("/revisions/{revisionId}/suitability-code")
    public RevisionResponse setSuitabilityCode(
        @Parameter(description = "Identifier of the revision.", example = "2049")
        @PathVariable Long revisionId,
        @Valid @RequestBody SuitabilityAssignmentRequest request
    ) {
        var revision = requireRevision(revisionId);
        var code = request.suitabilityCodeId() == null ? null
            : suitabilityCodeRepository.findById(request.suitabilityCodeId())
                .orElseThrow(() -> new ResourceNotFoundException("No such suitability code."));

        return CdeResponseMapper.toResponse(lifecycle.assignSuitabilityCode(revision, code));
    }

    private com.cde.platform.cde.model.InformationContainer requireContainer(Long containerId) {
        return containerRepository.findById(containerId)
            .orElseThrow(() -> new ResourceNotFoundException("No such container."));
    }

    private ContainerRevision requireRevision(Long revisionId) {
        return revisionRepository.findById(revisionId)
            .orElseThrow(() -> new ResourceNotFoundException("No such revision."));
    }

    /**
     * A revision of some other container is reported as not found rather than
     * as a bad reference. Within the scope the caller addressed, it genuinely
     * is not there — and answering otherwise would confirm the id exists
     * somewhere they cannot see.
     */
    private ContainerRevision requireRevisionOf(Long containerId, Long revisionId) {
        ContainerRevision revision = requireRevision(revisionId);
        if (revision.getContainer() == null
            || !containerId.equals(revision.getContainer().getId())) {
            throw new ResourceNotFoundException("No such revision of this container.");
        }
        return revision;
    }

    private com.cde.platform.model.User currentUser(UserDetails principal) {
        return userRepository.findByUsername(principal.getUsername())
            .orElseThrow(() -> new ResourceNotFoundException("No such user."));
    }
}
