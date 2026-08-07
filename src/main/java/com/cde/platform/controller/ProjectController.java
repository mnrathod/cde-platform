package com.cde.platform.controller;

import com.cde.platform.dto.Dtos.*;
import com.cde.platform.model.*;
import com.cde.platform.service.DocumentDeletionService;
import com.cde.platform.repository.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
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

    @GetMapping
    public List<ProjectResponse> list() {
        return projectRepo.findAll().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> get(@PathVariable Long id) {
        return projectRepo.findById(id)
            .map(p -> ResponseEntity.ok(toResponse(p)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(
        @Valid @RequestBody ProjectRequest req,
        @AuthenticationPrincipal UserDetails principal
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

    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
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
