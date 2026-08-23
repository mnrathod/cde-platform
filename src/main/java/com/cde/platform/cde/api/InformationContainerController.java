package com.cde.platform.cde.api;

import com.cde.platform.cde.api.ContainerDtos.ContainerRequest;
import com.cde.platform.cde.api.ContainerDtos.ContainerResponse;
import com.cde.platform.cde.model.InformationContainer;
import com.cde.platform.cde.repository.InformationContainerRepository;
import com.cde.platform.exception.ResourceConflictException;
import com.cde.platform.exception.ResourceNotFoundException;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.repository.ProjectRepository;
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
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Information containers: the identities that persist across every revision of
 * a piece of project information.
 *
 * <p>Each method carries the permission its documentation claims. The lifecycle
 * operations are enforced a second time in the service, which is where the
 * authority actually has to hold — this layer states the endpoint's
 * requirement, and refuses early rather than doing work first.
 */
@RestController
@RequestMapping("/api/cde")
@Tag(name = ApiDocumentation.TAG_CDE)
@StandardErrorResponses
public class InformationContainerController {

    private final InformationContainerRepository containerRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public InformationContainerController(InformationContainerRepository containerRepository,
                                          ProjectRepository projectRepository,
                                          UserRepository userRepository) {
        this.containerRepository = containerRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    @Operation(
        operationId = "listInformationContainers",
        summary = "List a project's information containers",
        description = """
            Returns every container in the project, ordered by the database's natural order.

            Requires the `container:read` permission.

            A project belonging to another tenant reports `404`, so the response does not \
            confirm that the id exists elsewhere.""")
    @ApiResponse(responseCode = "200", description = "The project's containers.")
    @ApiResponse(responseCode = "404",
        description = "No project with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('container:read')")
    @GetMapping("/projects/{projectId}/containers")
    public List<ContainerResponse> listContainers(
        @Parameter(description = "Identifier of the project.", example = "42")
        @PathVariable Long projectId
    ) {
        requireProject(projectId);
        return containerRepository.findByProjectId(projectId).stream()
            .map(CdeResponseMapper::toResponse)
            .toList();
    }

    @Operation(
        operationId = "createInformationContainer",
        summary = "Create an information container",
        description = """
            Creates the container's identity. It has no content and no state until a revision \
            is issued for it — see `createContainerRevision`.

            The container is created in the caller's tenant and attributed to the caller; \
            neither can be supplied in the body.

            Requires the `container:write` permission.""")
    @ApiResponse(responseCode = "201", description = "The container as created.")
    @ApiResponse(responseCode = "404",
        description = "No project with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "409",
        description = "The project already has a container with that reference.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The container details failed validation — a blank reference, or one over "
                    + "its length limit.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('container:write')")
    @PostMapping("/projects/{projectId}/containers")
    public ResponseEntity<ContainerResponse> createContainer(
        @Parameter(description = "Identifier of the project.", example = "42")
        @PathVariable Long projectId,
        @Valid @RequestBody ContainerRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        var project = requireProject(projectId);

        if (containerRepository.existsByProjectIdAndContainerReference(
                projectId, request.containerReference())) {
            throw new ResourceConflictException(
                "This project already has a container referenced " + request.containerReference()
                + ". A container's reference identifies it, so issue a revision of the existing "
                + "one rather than creating a second.");
        }

        var container = containerRepository.save(InformationContainer.builder()
            .project(project)
            .containerReference(request.containerReference())
            .namingFields(request.namingFields() != null
                ? new LinkedHashMap<>(request.namingFields())
                : new LinkedHashMap<>())
            .createdBy(currentUser(principal))
            .build());

        return ResponseEntity
            .created(URI.create("/api/cde/containers/" + container.getId()))
            .body(CdeResponseMapper.toResponse(container));
    }

    @Operation(
        operationId = "getInformationContainer",
        summary = "Read one information container",
        description = """
            Requires the `container:read` permission.

            A container in another tenant reports `404`, not `403`.""")
    @ApiResponse(responseCode = "200", description = "The container.")
    @ApiResponse(responseCode = "404",
        description = "No container with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('container:read')")
    @GetMapping("/containers/{containerId}")
    public ContainerResponse getContainer(
        @Parameter(description = "Identifier of the container.", example = "1024")
        @PathVariable Long containerId
    ) {
        return CdeResponseMapper.toResponse(
            containerRepository.findById(containerId)
                .orElseThrow(() -> new ResourceNotFoundException("No such container.")));
    }

    private com.cde.platform.model.Project requireProject(Long projectId) {
        // Row-Level Security scopes the lookup, so a project in another tenant
        // is simply not found — the 404 is the isolation working, not a
        // separate check that could be forgotten.
        return projectRepository.findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("No such project."));
    }

    private com.cde.platform.model.User currentUser(UserDetails principal) {
        return userRepository.findByUsername(principal.getUsername())
            .orElseThrow(() -> new ResourceNotFoundException("No such user."));
    }
}
