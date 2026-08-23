package com.cde.platform.controller;

import com.cde.platform.dto.SignatureDtos.SignatureRequest;
import com.cde.platform.dto.SignatureDtos.SignatureResponse;
import com.cde.platform.dto.SignatureDtos.SignatureVerification;
import com.cde.platform.dto.SignatureDtos.SigningResult;
import com.cde.platform.model.DocumentSignature;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.service.DocumentSigningService;
import com.cde.platform.service.DocumentSigningService.SignRequest;
import com.cde.platform.service.DocumentSigningService.SigningOutcome;
import com.cde.platform.service.DocumentSigningService.VerificationOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Digital signatures.
 *
 * <p>Signing a PDF writes the signature into the document and commits a new
 * version, so the signature travels with the file and any conforming reader can
 * check it.
 */
@RestController
@RequestMapping("/api/signatures")
@Tag(name = ApiDocumentation.TAG_SIGNATURES)
@StandardErrorResponses
public class SignatureController {

    private final DocumentSigningService signing;

    public SignatureController(DocumentSigningService signing) {
        this.signing = signing;
    }

    @Operation(
        operationId = "listDocumentSignatures",
        summary = "List the signatures held against a document",
        description = """
            Includes revoked signatures. A revoked signature is part of the document's history and \
            removing it from the listing would hide that the document was once signed and then \
            was not.

            Each entry names the version it attests to: a signature on version 2 says nothing \
            about version 5.

            Requires the `signature:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The signatures on the document.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/document/{documentId}")
    public List<SignatureResponse> getSignatures(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId
    ) {
        return signing.signaturesFor(documentId).stream().map(this::toResponse).toList();
    }

    @Operation(
        operationId = "signDocument",
        summary = "Sign a document",
        description = """
            Writes a PAdES signature into the document itself and commits the result as a new \
            version, so the signature travels with the file rather than living only in this \
            application's records. `embedded` in the reply says which of those happened.

            The signer is the authenticated caller and cannot be supplied. Role, reason and \
            location are optional context recorded in the signature dictionary.

            Requires the `signature:write` permission.""")
    @ApiResponse(responseCode = "200", description = "The document was signed.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The document cannot be signed — it is not a PDF, or its file is missing.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/document/{documentId}/sign")
    public ResponseEntity<SigningResult> signDocument(
        @Parameter(description = "Identifier of the document to sign.", example = "1180")
        @PathVariable Long documentId,
        @Valid @RequestBody(required = false) SignatureRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        SignatureRequest supplied = request != null ? request : new SignatureRequest(null, null, null);
        SigningOutcome outcome = signing.sign(
            documentId, principal.getUsername(),
            new SignRequest(supplied.role(), supplied.reason(), supplied.location()));

        return ResponseEntity.ok(new SigningResult(
            toResponse(outcome.signature()),
            outcome.stampSvg(),
            outcome.version().getVersionNumber(),
            outcome.embedded(),
            outcome.documentStatus()));
    }

    @Operation(
        operationId = "verifySignature",
        summary = "Check whether a signature still holds",
        description = """
            Re-checks the signature against the document as it stands. `embedded` says whether \
            the check read the document itself or only this application's record of the \
            signature — the difference being whether a recipient outside this system could reach \
            the same conclusion.

            A signature that no longer holds is not an error: the answer is `valid: false` with \
            `200`, because the question was answered.

            Requires the `signature:read` permission.""")
    @ApiResponse(responseCode = "200",
        description = "The check ran. Read `valid` for its outcome — a `200` does not mean the "
                    + "signature is good.")
    @ApiResponse(responseCode = "404",
        description = "No signature with that identifier is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/{signatureId}/verify")
    public ResponseEntity<SignatureVerification> verifySignature(
        @Parameter(description = "Public identifier of the signature.", example = "sig_7f3a91c4e2b8")
        @PathVariable String signatureId
    ) {
        return signing.verify(signatureId)
            .map(this::toVerificationResponse)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(
        operationId = "revokeSignature",
        summary = "Revoke a signature",
        description = """
            Marks the signature as no longer standing. The record is kept — a revoked signature is \
            part of the document's history, and erasing it would hide that the document was once \
            signed.

            Only the signer may revoke their own signature. Anyone else — including an \
            administrator — gets `403`: a signature is a personal attestation, so letting \
            someone else withdraw it would mean the record no longer says what the signer said.

            Requires the `signature:write` permission.""")
    @ApiResponse(responseCode = "204", description = "The signature is revoked.")
    @ApiResponse(responseCode = "404",
        description = "No signature with that identifier is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @DeleteMapping("/{signatureId}")
    public ResponseEntity<Void> revokeSignature(
        @Parameter(description = "Public identifier of the signature.", example = "sig_7f3a91c4e2b8")
        @PathVariable String signatureId,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        return signing.revoke(signatureId, principal.getUsername())
            .map(revoked -> revoked
                ? ResponseEntity.noContent().<Void>build()
                : ResponseEntity.status(HttpStatus.FORBIDDEN).<Void>build())
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private SignatureVerification toVerificationResponse(VerificationOutcome outcome) {
        return new SignatureVerification(
            outcome.valid(), outcome.status(), outcome.message(), outcome.embedded());
    }

    private SignatureResponse toResponse(DocumentSignature signature) {
        return new SignatureResponse(
            signature.getId(),
            signature.getSignatureId(),
            signature.getSignerName(),
            orEmpty(signature.getSignerEmail()),
            orEmpty(signature.getRole()),
            orEmpty(signature.getReason()),
            signature.getStatus(),
            signature.getSignedAt(),
            // Which revision this attests to — a signature on v2 says nothing
            // about v5.
            signature.getDocumentVersion() != null
                ? signature.getDocumentVersion().getVersionNumber() : null);
    }

    private String orEmpty(String value) {
        return value != null ? value : "";
    }
}
