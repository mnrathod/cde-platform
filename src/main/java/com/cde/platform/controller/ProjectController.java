package com.cde.platform.controller;

import com.cde.platform.dto.ProjectDtos.*;
import com.cde.platform.model.*;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.service.DocumentDeletionService;
import com.cde.platform.repository.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@Tag(name = ApiDocumentation.TAG_PROJECTS)
@StandardErrorResponses
public class ProjectController {

    private final ProjectRepository projectRepo;
    private final UserRepository userRepo;
    private final DocumentRepository documentRepo;
    private final DocumentDeletionService deletionService;

    public ProjectController(ProjectRepository projectRepo, UserRepository userRepo,
                             DocumentRepository documentRepo,
                             DocumentDeletionService deletionService) {
        this.projectRepo = projectRepo;
        this.userRepo = userRepo;
        this.documentRepo = documentRepo;
        this.deletionService = deletionService;
    }

    @Operation(
        operationId = "listProjects",
        summary = "List the projects in the caller's tenant",
        description = """
            Returns every project visible to the caller. Visibility is decided by the tenant \
            on the authenticated principal — there is no tenant parameter, and a project \
            belonging to another tenant is not returned by any combination of arguments.

            Requires the `project:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The projects in the caller's tenant.")
    @GetMapping
    public List<ProjectResponse> list() {
        return projectRepo.findAll().stream().map(this::toResponse).toList();
    }

    @Operation(
        operationId = "getProject",
        summary = "Read one project",
        description = """
            Requires the `project:read` permission.

            A project in another tenant reports `404`, not `403`, so the response does not \
            confirm that the id exists elsewhere.""")
    @ApiResponse(responseCode = "200", description = "The project.")
    @ApiResponse(responseCode = "404",
        description = "No project with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> get(
        @Parameter(description = "Identifier of the project.", example = "42")
        @PathVariable Long id
    ) {
        return projectRepo.findById(id)
            .map(p -> ResponseEntity.ok(toResponse(p)))
            .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
        operationId = "createProject",
        summary = "Create a project",
        description = """
            The new project is created in the caller's tenant and owned by the caller. Neither \
            the tenant nor the owner can be supplied in the body.

            Requires the `project:write` permission.""")
    @ApiResponse(responseCode = "201", description = "The project as created.")
    @ApiResponse(responseCode = "422",
        description = "The project details failed validation — an empty name, or a field over "
                    + "its length limit.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping
    public ResponseEntity<ProjectResponse> create(
        @Valid @RequestBody ProjectRequest req,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        var owner = userRepo.findByUsername(principal.getUsername()).orElseThrow();
        var project = Project.builder()
            .name(req.name())
            .description(req.description())
            .location(req.location())
            .phase(req.phase() != null ? req.phase() : Project.ProjectPhase.CONCEPT)
            .owner(owner)
            .build();
        projectRepo.save(project);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(project));
    }

    @Operation(
        operationId = "replaceProject",
        summary = "Replace a project's details",
        description = """
            Replaces name, description and location outright, so an omitted field is cleared \
            rather than left alone. Phase is the exception: omitting it keeps the current \
            phase, because a project moves through phases in order and clearing one by \
            omission would be a silent regression.

            Ownership and tenant are not settable here.

            Requires the `project:write` permission.""")
    @ApiResponse(responseCode = "200", description = "The project as it now stands.")
    @ApiResponse(responseCode = "404",
        description = "No project with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The project details failed validation — an empty name, or a field over "
                    + "its length limit.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(
        @Parameter(description = "Identifier of the project.", example = "42")
        @PathVariable Long id,
        @Valid @RequestBody ProjectRequest req
    ) {
        return projectRepo.findById(id).map(p -> {
            p.setName(req.name());
            p.setDescription(req.description());
            p.setLocation(req.location());
            if (req.phase() != null) p.setPhase(req.phase());
            projectRepo.save(p);
            return ResponseEntity.ok(toResponse(p));
        }).orElse(ResponseEntity.notFound().build());
    }

    @Operation(
        operationId = "deleteProject",
        summary = "Delete a project and everything in it",
        description = """
            Cascades through the project's documents and their annotations, versions and \
            signatures. This is not reversible.

            Idempotent: deleting a project that is already gone reports `404` rather than \
            failing differently on a second attempt.

            Requires the `project:write` permission.""")
    @ApiResponse(responseCode = "204", description = "The project and its contents are gone.")
    @ApiResponse(responseCode = "404",
        description = "No project with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
        @Parameter(description = "Identifier of the project.", example = "42")
        @PathVariable Long id
    ) {
        // Cascades through the project's documents and their dependents.
        return deletionService.deleteProject(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    private ProjectResponse toResponse(Project p) {
        int docCount = p.getDocuments() != null ? p.getDocuments().size() : 0;
        return new ProjectResponse(
            p.getId(), p.getName(), p.getDescription(), p.getLocation(),
            p.getPhase(),
            p.getOwner() != null ? p.getOwner().getUsername() : null,
            p.getCreatedAt(), p.getUpdatedAt(), docCount
        );
    }
}
