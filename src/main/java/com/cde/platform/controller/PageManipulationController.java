package com.cde.platform.controller;

import com.cde.platform.dto.Dtos.PageArrangementResponse;
import com.cde.platform.dto.Dtos.PageExtractionResponse;
import com.cde.platform.service.PageArrangement;
import com.cde.platform.service.PageArrangement.PageRef;
import com.cde.platform.service.PageManipulationService;
import com.cde.platform.service.PageManipulationService.ArrangementResult;
import com.cde.platform.service.PageManipulationService.ExtractionResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Page manipulation:
 * <pre>
 *   GET  /api/documents/{id}/pages          — page count, sizes and rotations
 *   POST /api/documents/{id}/pages/arrange  — reorder, delete, duplicate, rotate
 *   POST /api/documents/{id}/pages/insert   — bring pages in from another document
 *   POST /api/documents/{id}/pages/extract  — copy pages into a new document
 * </pre>
 *
 * <p>Reordering, deleting, duplicating and rotating share one endpoint
 * because they are one operation: the client sends the page layout it wants
 * and the server works out what changed. A batch of edits in the page
 * organiser then commits as a single version described by its net effect,
 * rather than one version per drag.
 */
@RestController
@RequestMapping("/api/documents/{documentId}/pages")
public class PageManipulationController {

    private final PageManipulationService pageService;

    public PageManipulationController(PageManipulationService pageService) {
        this.pageService = pageService;
    }

    @GetMapping
    public ResponseEntity<JsonNode> describePages(@PathVariable Long documentId) {
        return ResponseEntity.ok(pageService.describePages(documentId));
    }

    // ── Rearrange in place ───────────────────────────────────────
    @PostMapping("/arrange")
    public ResponseEntity<PageArrangementResponse> arrangePages(
        @PathVariable Long documentId,
        @RequestBody ArrangeRequest request,
        @AuthenticationPrincipal UserDetails principal
    ) {
        ArrangementResult result = pageService.arrange(
            documentId, request.toArrangement(), usernameOf(principal));
        return ResponseEntity.ok(PageArrangementResponse.from(documentId, result));
    }

    /**
     * @param pages the layout wanted, in order. Each entry names a source page
     *              and an optional rotation relative to its current one.
     */
    public record ArrangeRequest(List<PageEntry> pages) {
        PageArrangement toArrangement() {
            if (pages == null) return new PageArrangement(List.of());
            return new PageArrangement(pages.stream().map(PageEntry::toRef).toList());
        }
    }

    public record PageEntry(Integer page, Integer rotate) {
        PageRef toRef() {
            return new PageRef(null, page != null ? page : 0, rotate != null ? rotate : 0);
        }
    }

    // ── Insert from another document ─────────────────────────────
    @PostMapping("/insert")
    public ResponseEntity<PageArrangementResponse> insertPages(
        @PathVariable Long documentId,
        @RequestBody InsertRequest request,
        @AuthenticationPrincipal UserDetails principal
    ) {
        ArrangementResult result = pageService.insertPages(
            documentId,
            request.sourceDocumentId(),
            request.pages(),
            request.positionOrEnd(),
            usernameOf(principal));
        return ResponseEntity.ok(PageArrangementResponse.from(documentId, result));
    }

    public record InsertRequest(Long sourceDocumentId, List<Integer> pages, Integer position) {
        /** Absent position means append, which the service clamps for. */
        int positionOrEnd() {
            return position != null ? position : Integer.MAX_VALUE;
        }
    }

    // ── Extract into a new document ──────────────────────────────
    @PostMapping("/extract")
    public ResponseEntity<PageExtractionResponse> extractPages(
        @PathVariable Long documentId,
        @RequestBody ExtractRequest request,
        @AuthenticationPrincipal UserDetails principal
    ) {
        ExtractionResult result = pageService.extractPages(
            documentId, request.pages(), request.name(), usernameOf(principal));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(PageExtractionResponse.from(result));
    }

    public record ExtractRequest(List<Integer> pages, String name) {}

    private String usernameOf(UserDetails principal) {
        return principal != null ? principal.getUsername() : null;
    }
}
