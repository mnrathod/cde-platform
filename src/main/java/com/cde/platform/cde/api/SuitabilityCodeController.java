package com.cde.platform.cde.api;

import com.cde.platform.cde.api.SuitabilityCodeDtos.SuitabilityCodeRequest;
import com.cde.platform.cde.api.SuitabilityCodeDtos.SuitabilityCodeResponse;
import com.cde.platform.cde.model.SuitabilityCode;
import com.cde.platform.cde.repository.SuitabilityCodeRepository;
import com.cde.platform.exception.ResourceConflictException;
import com.cde.platform.exception.ResourceNotFoundException;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.repository.ProjectRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A project's suitability codes — what information carrying each one may be
 * relied on for.
 *
 * <p>The product ships the mechanism and none of the values. Organisations
 * customise these lists, and the code tables printed in the standard are
 * copyrighted material that may not be reproduced in a commercial product, so
 * every list is populated by the customer.
 */
@RestController
@RequestMapping("/api/cde/projects/{projectId}/suitability-codes")
@Tag(name = ApiDocumentation.TAG_CDE)
@StandardErrorResponses
public class SuitabilityCodeController {

    private final SuitabilityCodeRepository suitabilityCodeRepository;
    private final ProjectRepository projectRepository;

    public SuitabilityCodeController(SuitabilityCodeRepository suitabilityCodeRepository,
                                     ProjectRepository projectRepository) {
        this.suitabilityCodeRepository = suitabilityCodeRepository;
        this.projectRepository = projectRepository;
    }

    @Operation(
        operationId = "listSuitabilityCodes",
        summary = "List a project's suitability codes",
        description = """
            The codes currently offered on the project, in the order they should be presented. \
            Retired codes are not returned: they stay in the database so that revisions already \
            carrying one keep their meaning, but they are no longer offered for new use.

            A project that has defined none returns an empty list. Nothing is seeded — a new \
            project starts with no codes and the organisation populates its own.

            Requires the `container:read` permission.""")
    @ApiResponse(responseCode = "200",
        description = "The project's active codes, in display order.")
    @ApiResponse(responseCode = "404",
        description = "No project with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('container:read')")
    @GetMapping
    public List<SuitabilityCodeResponse> listCodes(
        @Parameter(description = "Identifier of the project.", example = "42")
        @PathVariable Long projectId
    ) {
        requireProject(projectId);
        return suitabilityCodeRepository
            .findByProjectIdAndActiveTrueOrderByDisplayOrderAsc(projectId).stream()
            .map(CdeResponseMapper::toResponse)
            .toList();
    }

    @Operation(
        operationId = "createSuitabilityCode",
        summary = "Define a suitability code on a project",
        description = """
            Adds a code to the project's own list.

            Setting `validInState` restricts the code to revisions in that state, which is what \
            stops information being labelled approved for construction while it is still \
            unverified work in progress. Leaving it unset allows the code in any state.

            Requires the `container:write` permission.""")
    @ApiResponse(responseCode = "201", description = "The code as created.")
    @ApiResponse(responseCode = "404",
        description = "No project with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "409",
        description = "The project already defines that code.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The code failed validation — a blank code or description, or one over "
                    + "its length limit.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PreAuthorize("hasAuthority('container:write')")
    @PostMapping
    public ResponseEntity<SuitabilityCodeResponse> createCode(
        @Parameter(description = "Identifier of the project.", example = "42")
        @PathVariable Long projectId,
        @Valid @RequestBody SuitabilityCodeRequest request
    ) {
        var project = requireProject(projectId);

        if (suitabilityCodeRepository.existsByProjectIdAndCode(projectId, request.code())) {
            throw new ResourceConflictException(
                "This project already defines the code " + request.code() + ".");
        }

        var saved = suitabilityCodeRepository.save(SuitabilityCode.builder()
            .project(project)
            .code(request.code())
            .description(request.description())
            .displayOrder(request.displayOrder() != null ? request.displayOrder() : 0)
            .validInState(request.validInState())
            .active(true)
            .build());

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(CdeResponseMapper.toResponse(saved));
    }

    private com.cde.platform.model.Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("No such project."));
    }
}
