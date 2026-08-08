package com.cde.platform.controller;

import com.cde.platform.collaboration.CollaborationBroadcaster;
import com.cde.platform.collaboration.CollaborationEvent;
import com.cde.platform.dto.Dtos.*;
import com.cde.platform.model.*;
import com.cde.platform.repository.*;
import com.cde.platform.service.XfdfService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/annotations")
public class AnnotationController {

    private final AnnotationRepository     annotationRepo;
    private final AnnotationReplyRepository replyRepo;
    private final DocumentRepository        documentRepo;
    private final UserRepository            userRepo;
    private final XfdfService              xfdfService;
    private final CollaborationBroadcaster broadcaster;

    public AnnotationController(
        AnnotationRepository     annotationRepo,
        AnnotationReplyRepository replyRepo,
        DocumentRepository        documentRepo,
        UserRepository            userRepo,
        XfdfService              xfdfService,
        CollaborationBroadcaster broadcaster
    ) {
        this.annotationRepo = annotationRepo;
        this.replyRepo       = replyRepo;
        this.documentRepo    = documentRepo;
        this.userRepo        = userRepo;
        this.xfdfService     = xfdfService;
        this.broadcaster     = broadcaster;
    }

    // ── Annotations CRUD ─────────────────────────────────────────
    @GetMapping("/document/{documentId}")
    public List<AnnotationResponse> getByDocument(@PathVariable Long documentId) {
        return annotationRepo.findByDocument_Id(documentId).stream().map(this::toResponse).toList();
    }

    @PostMapping
    public ResponseEntity<AnnotationResponse> create(
        @Valid @RequestBody AnnotationRequest req,
        @AuthenticationPrincipal UserDetails principal
    ) {
        var doc = documentRepo.findById(req.documentId()).orElse(null);
        if (doc == null) return ResponseEntity.badRequest().build();
        var author = userRepo.findByUsername(principal.getUsername()).orElseThrow();
        var ann = Annotation.builder()
            .document(doc).author(author).type(req.type())
            .shapeData(req.shapeData()).comment(req.comment())
            .pageNumber(req.pageNumber())
            .status(Annotation.AnnotationStatus.OPEN)
            .createdAt(LocalDateTime.now())
            .build();
        AnnotationResponse saved = toResponse(annotationRepo.save(ann));
        broadcaster.annotationCreated(doc.getId(), author.getUsername(), saved);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AnnotationResponse> update(
        @PathVariable Long id,
        @RequestBody AnnotationRequest req
    ) {
        return annotationRepo.findById(id).map(ann -> {
            ann.setShapeData(req.shapeData());
            ann.setComment(req.comment());
            AnnotationResponse saved = toResponse(annotationRepo.save(ann));
            broadcaster.annotationUpdated(documentIdOf(ann), authorNameOf(ann), saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/resolve")
    public ResponseEntity<AnnotationResponse> resolve(@PathVariable Long id) {
        return annotationRepo.findById(id).map(ann -> {
            ann.setStatus(Annotation.AnnotationStatus.RESOLVED);
            AnnotationResponse saved = toResponse(annotationRepo.save(ann));
            broadcaster.annotationResolved(documentIdOf(ann), authorNameOf(ann), saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails principal) {
        var existing = annotationRepo.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();

        // Read the document before deleting: afterwards there is nothing left
        // to say which document's viewers should hear about it.
        Long documentId = documentIdOf(existing);
        annotationRepo.deleteById(id);
        broadcaster.annotationDeleted(documentId, usernameOf(principal), id);
        return ResponseEntity.noContent().build();
    }

    // ── Annotation Reply Threads ──────────────────────────────────
    @GetMapping("/{annotationId}/replies")
    public List<ReplyResponse> getReplies(@PathVariable Long annotationId) {
        return replyRepo.findByAnnotation_IdOrderByCreatedAtAsc(annotationId)
            .stream().map(this::toReplyResponse).toList();
    }

    @PostMapping("/{annotationId}/replies")
    public ResponseEntity<ReplyResponse> addReply(
        @PathVariable Long annotationId,
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal UserDetails principal
    ) {
        var ann = annotationRepo.findById(annotationId).orElse(null);
        if (ann == null) return ResponseEntity.notFound().build();

        var author = userRepo.findByUsername(principal.getUsername()).orElseThrow();
        var reply = AnnotationReply.builder()
            .annotation(ann).author(author)
            .content(body.getOrDefault("content", ""))
            .createdAt(LocalDateTime.now())
            .build();
        ReplyResponse saved = toReplyResponse(replyRepo.save(reply));
        broadcaster.replyAdded(documentIdOf(ann), author.getUsername(),
            new CollaborationEvent.ReplyPayload(
                saved.id(), annotationId, saved.authorName(), saved.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/replies/{replyId}")
    public ResponseEntity<Void> deleteReply(@PathVariable Long replyId) {
        if (!replyRepo.existsById(replyId)) return ResponseEntity.notFound().build();
        replyRepo.deleteById(replyId);
        return ResponseEntity.noContent().build();
    }

    // ── XFDF Export ───────────────────────────────────────────────
    @GetMapping("/document/{documentId}/xfdf")
    public ResponseEntity<byte[]> exportXfdf(@PathVariable Long documentId) {
        var docOpt = documentRepo.findById(documentId);
        if (docOpt.isEmpty()) return ResponseEntity.notFound().build();
        var doc  = docOpt.get();
        var anns = annotationRepo.findByDocument_Id(documentId);
        String xfdf = xfdfService.toXfdf(anns, doc.getFileName() != null ? doc.getFileName() : doc.getName());
        String fn   = (doc.getName() != null ? doc.getName() : "document") + ".xfdf";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fn + "\"")
            .header(HttpHeaders.CONTENT_TYPE, "application/vnd.adobe.xfdf")
            .body(xfdf.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/document/{documentId}/xfdf")
    public ResponseEntity<?> importXfdf(
        @PathVariable Long documentId,
        @RequestParam("file") MultipartFile file,
        @AuthenticationPrincipal UserDetails principal
    ) {
        var docOpt = documentRepo.findById(documentId);
        if (docOpt.isEmpty()) return ResponseEntity.notFound().build();
        var doc    = docOpt.get();
        var author = userRepo.findByUsername(principal.getUsername()).orElseThrow();

        try {
            var imported = xfdfService.fromXfdf(file.getBytes());
            if (imported.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "imported", 0,
                    "message",  "No annotations found in XFDF file"
                ));
            }

            var saved = imported.stream().map(imp -> {
                var ann = Annotation.builder()
                    .document(doc).author(author)
                    .type(imp.type())
                    .shapeData(imp.shapeData())
                    .comment(imp.comment() != null ? imp.comment() : "")
                    .pageNumber(imp.pageNumber())
                    .status(Annotation.AnnotationStatus.OPEN)
                    .createdAt(java.time.LocalDateTime.now())
                    .build();
                return toResponse(annotationRepo.save(ann));
            }).toList();

            return ResponseEntity.ok(Map.of(
                "imported",    saved.size(),
                "annotations", saved,
                "message",     "Successfully imported " + saved.size() + " annotation(s)"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Failed to parse XFDF: " + e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────
    /** Which document's viewers should hear about a change to this annotation. */
    private Long documentIdOf(Annotation annotation) {
        return annotation.getDocument() != null ? annotation.getDocument().getId() : null;
    }

    private String authorNameOf(Annotation annotation) {
        return annotation.getAuthor() != null ? annotation.getAuthor().getUsername() : null;
    }

    private String usernameOf(UserDetails principal) {
        return principal != null ? principal.getUsername() : null;
    }

    private AnnotationResponse toResponse(Annotation a) {
        return new AnnotationResponse(
            a.getId(),
            a.getDocument() != null ? a.getDocument().getId() : null,
            a.getAuthor() != null ? a.getAuthor().getUsername() : null,
            a.getType(), a.getShapeData(), a.getComment(), a.getStatus(),
            a.getPageNumber(), a.getCreatedAt()
        );
    }

    private ReplyResponse toReplyResponse(AnnotationReply r) {
        return new ReplyResponse(
            r.getId(),
            r.getAnnotation().getId(),
            r.getAuthor() != null ? r.getAuthor().getUsername() : "Unknown",
            r.getContent(),
            r.getCreatedAt()
        );
    }
}
