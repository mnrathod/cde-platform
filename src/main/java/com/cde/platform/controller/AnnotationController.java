package com.cde.platform.controller;

import com.cde.platform.collaboration.CollaborationBroadcaster;
import com.cde.platform.collaboration.CollaborationEvent;
import com.cde.platform.dto.AnnotationDtos.*;
import com.cde.platform.exception.DocumentProcessingException;
import com.cde.platform.exception.ResourceNotFoundException;
import com.cde.platform.model.*;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = ApiDocumentation.TAG_ANNOTATIONS)
@StandardErrorResponses
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
    @Operation(
        operationId = "listAnnotationsByDocument",
        summary = "List the markup on a document",
        description = """
            Returns every piece of markup on the document, open and resolved alike, in creation \
            order. Filtering to open items is left to the client because a review panel usually \
            wants both, with the resolved ones collapsed.

            Requires the `annotation:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The markup on the document.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/document/{documentId}")
    public List<AnnotationResponse> getByDocument(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId
    ) {
        return annotationRepo.findByDocument_Id(documentId).stream().map(this::toResponse).toList();
    }

    @Operation(
        operationId = "createAnnotation",
        summary = "Place markup on a document",
        description = """
            The author is the authenticated caller and cannot be supplied. New markup starts \
            `OPEN`.

            Everyone viewing the document is notified over the collaboration channel, so a second \
            reviewer sees the markup without reloading.

            Requires the `annotation:write` permission.""")
    @ApiResponse(responseCode = "201", description = "The markup as created.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The markup failed validation — no shape data, or a page number below 1.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping
    public ResponseEntity<AnnotationResponse> create(
        @Valid @RequestBody AnnotationRequest req,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        var doc = documentRepo.findById(req.documentId())
            .orElseThrow(() -> new ResourceNotFoundException("No such document."));
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

    @Operation(
        operationId = "updateAnnotation",
        summary = "Change markup's geometry or note",
        description = """
            Only the shape and the comment are changeable. Type, page, author and document are \
            fixed at creation: markup that changed which document or page it referred to would \
            invalidate every reply already written against it.

            Requires the `annotation:write` permission.""")
    @ApiResponse(responseCode = "200", description = "The markup as it now stands.")
    @ApiResponse(responseCode = "404",
        description = "No markup with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The markup failed validation — no shape data, or a comment over the limit.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PutMapping("/{id}")
    public ResponseEntity<AnnotationResponse> update(
        @Parameter(description = "Identifier of the markup.", example = "9042")
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

    @Operation(
        operationId = "resolveAnnotation",
        summary = "Mark markup as dealt with",
        description = """
            Idempotent — resolving markup that is already resolved succeeds and changes nothing.

            The markup and its replies stay in place. Resolving records that the point was \
            addressed; it does not remove the record that it was raised.

            Requires the `annotation:write` permission.""")
    @ApiResponse(responseCode = "200", description = "The markup, now resolved.")
    @ApiResponse(responseCode = "404",
        description = "No markup with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PatchMapping("/{id}/resolve")
    public ResponseEntity<AnnotationResponse> resolve(
        @Parameter(description = "Identifier of the markup.", example = "9042")
        @PathVariable Long id
    ) {
        return annotationRepo.findById(id).map(ann -> {
            ann.setStatus(Annotation.AnnotationStatus.RESOLVED);
            AnnotationResponse saved = toResponse(annotationRepo.save(ann));
            broadcaster.annotationResolved(documentIdOf(ann), authorNameOf(ann), saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @Operation(
        operationId = "deleteAnnotation",
        summary = "Remove markup and its replies",
        description = """
            Not reversible, and it takes the reply thread with it. Resolving is usually what is \
            wanted instead: it records that the point was addressed rather than erasing that it \
            was raised.

            Requires the `annotation:write` permission.""")
    @ApiResponse(responseCode = "204", description = "The markup and its replies are gone.")
    @ApiResponse(responseCode = "404",
        description = "No markup with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
        @Parameter(description = "Identifier of the markup.", example = "9042")
        @PathVariable Long id,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal) {
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
    @Operation(
        operationId = "listAnnotationReplies",
        summary = "Read a markup thread",
        description = """
            Replies in the order they were written, oldest first, which is how a conversation \
            reads.

            Requires the `annotation:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The thread, oldest reply first.")
    @ApiResponse(responseCode = "404",
        description = "No markup with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/{annotationId}/replies")
    public List<ReplyResponse> getReplies(
        @Parameter(description = "Identifier of the markup.", example = "9042")
        @PathVariable Long annotationId
    ) {
        return replyRepo.findByAnnotation_IdOrderByCreatedAtAsc(annotationId)
            .stream().map(this::toReplyResponse).toList();
    }

    @Operation(
        operationId = "addAnnotationReply",
        summary = "Reply in a markup thread",
        description = """
            The author is the authenticated caller and cannot be supplied.

            Everyone viewing the document is notified over the collaboration channel.

            Requires the `annotation:write` permission.""")
    @ApiResponse(responseCode = "201", description = "The reply as written.")
    @ApiResponse(responseCode = "404",
        description = "No markup with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The reply is empty or longer than the limit.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/{annotationId}/replies")
    public ResponseEntity<ReplyResponse> addReply(
        @Parameter(description = "Identifier of the markup to reply to.", example = "9042")
        @PathVariable Long annotationId,
        @Valid @RequestBody ReplyRequest body,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        var ann = annotationRepo.findById(annotationId)
            .orElseThrow(() -> new ResourceNotFoundException("No such annotation."));

        var author = userRepo.findByUsername(principal.getUsername()).orElseThrow();
        var reply = AnnotationReply.builder()
            .annotation(ann).author(author)
            .content(body.content())
            .createdAt(LocalDateTime.now())
            .build();
        ReplyResponse saved = toReplyResponse(replyRepo.save(reply));
        broadcaster.replyAdded(documentIdOf(ann), author.getUsername(),
            new CollaborationEvent.ReplyPayload(
                saved.id(), annotationId, saved.authorName(), saved.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @Operation(
        operationId = "deleteAnnotationReply",
        summary = "Remove one reply from a thread",
        description = """
            Removes the reply only; the markup it belongs to and the rest of the thread are \
            untouched.

            Requires the `annotation:write` permission.""")
    @ApiResponse(responseCode = "204", description = "The reply is gone.")
    @ApiResponse(responseCode = "404",
        description = "No reply with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @DeleteMapping("/replies/{replyId}")
    public ResponseEntity<Void> deleteReply(
        @Parameter(description = "Identifier of the reply.", example = "3311")
        @PathVariable Long replyId
    ) {
        if (!replyRepo.existsById(replyId)) return ResponseEntity.notFound().build();
        replyRepo.deleteById(replyId);
        return ResponseEntity.noContent().build();
    }

    // ── XFDF Export ───────────────────────────────────────────────
    @Operation(
        operationId = "exportAnnotationsAsXfdf",
        summary = "Export a document's markup as XFDF",
        description = """
            XFDF is the interchange format Acrobat and most PDF tools read, so markup made here \
            can be opened elsewhere and vice versa.

            The reply is a file download, not JSON.

            Requires the `annotation:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The markup as an XFDF document.",
        content = @Content(mediaType = "application/vnd.adobe.xfdf",
                           schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/document/{documentId}/xfdf")
    public ResponseEntity<byte[]> exportXfdf(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId
    ) {
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

    @Operation(
        operationId = "importAnnotationsFromXfdf",
        summary = "Import markup from an XFDF file",
        description = """
            Adds the file's markup to the document, attributed to the authenticated caller. \
            Existing markup is left alone — importing adds, it does not replace.

            A file containing no markup is not an error: nothing is imported and the reply says \
            so, because "the file was empty" and "the file was rejected" call for different \
            things from the person who chose it.

            The file is parsed with external entities and DTDs disabled.

            Requires the `annotation:write` permission.""")
    @ApiResponse(responseCode = "200",
        description = "The file was read. `imported` may be zero if it held no markup.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The file is not readable XFDF.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/document/{documentId}/xfdf")
    public ResponseEntity<XfdfImportResponse> importXfdf(
        @Parameter(description = "Identifier of the document to add the markup to.", example = "1180")
        @PathVariable Long documentId,
        @Parameter(description = "The XFDF file.")
        @RequestParam("file") MultipartFile file,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        var doc = documentRepo.findById(documentId)
            .orElseThrow(() -> new ResourceNotFoundException("No such document."));
        var author = userRepo.findByUsername(principal.getUsername()).orElseThrow();

        try {
            var imported = xfdfService.fromXfdf(file.getBytes());
            if (imported.isEmpty()) {
                return ResponseEntity.ok(new XfdfImportResponse(
                    0, List.of(), "That file contained no markup, so nothing was imported."));
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

            return ResponseEntity.ok(new XfdfImportResponse(saved.size(), saved,
                "Imported %d %s.".formatted(saved.size(),
                    saved.size() == 1 ? "annotation" : "annotations")));

        } catch (Exception e) {
            // The parser's own message names classes and offsets, so it is
            // logged rather than returned.
            throw new DocumentProcessingException(
                "That file could not be read as XFDF. Check it was exported from a PDF tool "
                + "and is not damaged.", e);
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
