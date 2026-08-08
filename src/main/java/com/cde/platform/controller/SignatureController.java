package com.cde.platform.controller;

import com.cde.platform.model.DocumentSignature;
import com.cde.platform.service.DocumentSigningService;
import com.cde.platform.service.DocumentSigningService.SignRequest;
import com.cde.platform.service.DocumentSigningService.SigningOutcome;
import com.cde.platform.service.DocumentSigningService.VerificationOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Digital signatures:
 * <pre>
 *   GET    /api/signatures/document/{id}        — signatures on a document
 *   POST   /api/signatures/document/{id}/sign   — sign it
 *   POST   /api/signatures/{signatureId}/verify — check a signature
 *   DELETE /api/signatures/{signatureId}        — revoke the record
 * </pre>
 *
 * <p>Signing a PDF writes the signature into the document and commits a new
 * version, so the signature travels with the file and any conforming reader
 * can check it.
 */
@RestController
@RequestMapping("/api/signatures")
public class SignatureController {

    private final DocumentSigningService signing;

    public SignatureController(DocumentSigningService signing) {
        this.signing = signing;
    }

    @GetMapping("/document/{documentId}")
    public List<Map<String,Object>> getSignatures(@PathVariable Long documentId) {
        return signing.signaturesFor(documentId).stream().map(this::toResponse).toList();
    }

    @PostMapping("/document/{documentId}/sign")
    public ResponseEntity<Map<String,Object>> signDocument(
        @PathVariable Long documentId,
        @RequestBody(required = false) SignatureRequest request,
        @AuthenticationPrincipal UserDetails principal
    ) {
        SignatureRequest supplied = request != null ? request : new SignatureRequest(null, null, null);
        SigningOutcome outcome = signing.sign(
            documentId, principal.getUsername(), supplied.toSignRequest());

        Map<String,Object> body = new LinkedHashMap<>(toResponse(outcome.signature()));
        body.put("stampSvg",       outcome.stampSvg());
        body.put("version",        outcome.version().getVersionNumber());
        body.put("embedded",       outcome.embedded());
        body.put("documentStatus", outcome.documentStatus());
        return ResponseEntity.ok(body);
    }

    public record SignatureRequest(String role, String reason, String location) {
        SignRequest toSignRequest() {
            return new SignRequest(role, reason, location);
        }
    }

    @PostMapping("/{signatureId}/verify")
    public ResponseEntity<Map<String,Object>> verifySignature(@PathVariable String signatureId) {
        return signing.verify(signatureId)
            .map(this::toVerificationResponse)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{signatureId}")
    public ResponseEntity<Void> revokeSignature(
        @PathVariable String signatureId,
        @AuthenticationPrincipal UserDetails principal
    ) {
        return signing.revoke(signatureId, principal.getUsername())
            .map(revoked -> revoked
                ? ResponseEntity.noContent().<Void>build()
                : ResponseEntity.status(HttpStatus.FORBIDDEN).<Void>build())
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String,Object> toVerificationResponse(VerificationOutcome outcome) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("valid",   outcome.valid());
        body.put("status",  outcome.status());
        body.put("message", outcome.message());
        // Whether the check read the document itself or only our record of
        // it — the difference between a signature a recipient can verify and
        // one only this application knows about.
        body.put("embedded", outcome.embedded());
        return body;
    }

    private Map<String,Object> toResponse(DocumentSignature signature) {
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("id",          signature.getId());
        response.put("signatureId", signature.getSignatureId());
        response.put("signerName",  signature.getSignerName());
        response.put("signerEmail", orEmpty(signature.getSignerEmail()));
        response.put("role",        orEmpty(signature.getRole()));
        response.put("reason",      orEmpty(signature.getReason()));
        response.put("status",      signature.getStatus().name());
        response.put("signedAt",    signature.getSignedAt().toString());
        // Which revision this attests to — a signature on v2 says nothing
        // about v5.
        response.put("version", signature.getDocumentVersion() != null
            ? signature.getDocumentVersion().getVersionNumber() : null);
        return response;
    }

    private String orEmpty(String value) {
        return value != null ? value : "";
    }
}
