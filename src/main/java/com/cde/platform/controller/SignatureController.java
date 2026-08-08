package com.cde.platform.controller;

import com.cde.platform.model.*;
import com.cde.platform.repository.*;
import com.cde.platform.service.DigitalSignatureService;
import com.cde.platform.service.DigitalSignatureService.*;
import com.cde.platform.service.DocumentVersionService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/signatures")
public class SignatureController {

    private final DigitalSignatureService sigService;
    private final DocumentRepository       documentRepo;
    private final DocumentSignatureRepository signatureRepo;
    private final UserRepository           userRepo;
    private final DocumentVersionService   versionService;

    // In-memory cert store — in production use HSM or keystore file
    private final Map<String, SelfSignedCert> userCerts = new java.util.concurrent.ConcurrentHashMap<>();

    public SignatureController(
        DigitalSignatureService sigService,
        DocumentRepository documentRepo,
        DocumentSignatureRepository signatureRepo,
        UserRepository userRepo,
        DocumentVersionService versionService
    ) {
        this.sigService     = sigService;
        this.documentRepo   = documentRepo;
        this.signatureRepo  = signatureRepo;
        this.userRepo       = userRepo;
        this.versionService = versionService;
    }

    // ── GET /api/signatures/document/{id} ────────────────────────
    @GetMapping("/document/{documentId}")
    public List<Map<String,Object>> getSignatures(@PathVariable Long documentId) {
        return signatureRepo.findByDocument_IdOrderBySignedAtDesc(documentId)
            .stream().map(this::toResponse).toList();
    }

    // ── POST /api/signatures/document/{id}/sign ──────────────────
    @PostMapping("/document/{documentId}/sign")
    public ResponseEntity<?> signDocument(
        @PathVariable Long documentId,
        @RequestBody Map<String,String> body,
        @AuthenticationPrincipal UserDetails principal
    ) {
        var docOpt = documentRepo.findById(documentId);
        if (docOpt.isEmpty()) return ResponseEntity.notFound().build();
        var doc = docOpt.get();

        if (doc.getFilePath() == null)
            return ResponseEntity.badRequest().body(Map.of("error","Document has no file"));

        try {
            var user = userRepo.findByUsername(principal.getUsername()).orElseThrow();

            // Pin the signature to the version being signed. Signing "the
            // document" would leave it verifying against whatever the head
            // becomes later, so a subsequent OCR or redaction would invalidate
            // signatures nobody had tampered with.
            var signedVersion = versionService.currentVersion(doc, user);
            byte[] fileBytes  = Files.readAllBytes(Paths.get(signedVersion.getFilePath()));

            // Get or generate cert for this user
            SelfSignedCert cert = userCerts.computeIfAbsent(
                principal.getUsername(),
                k -> {
                    try {
                        return sigService.generateSelfSignedCert(
                            user.getUsername(),
                            "CDE Platform"
                        );
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
            );

            String role     = body.getOrDefault("role", "Reviewer");
            String reason   = body.getOrDefault("reason", "Document review and approval");
            String location = body.getOrDefault("location", "CDE Platform");

            SignatureRecord record = sigService.createSignatureRecord(
                fileBytes, user.getUsername(),
                user.getEmail() != null ? user.getEmail() : user.getUsername() + "@cde.platform",
                role, reason, location, cert
            );

            // Persist signature
            var sig = DocumentSignature.builder()
                .signatureId(record.id())
                .document(doc).documentVersion(signedVersion).signer(user)
                .signerName(record.signerName())
                .signerEmail(record.signerEmail())
                .role(record.role()).reason(record.reason())
                .location(record.location())
                .documentHash(record.documentHash())
                .signatureB64(record.signatureB64())
                .certificateB64(record.certificateB64())
                .algorithm(record.algorithm())
                .status(DocumentSignature.SignatureStatus.VALID)
                .signedAt(LocalDateTime.now())
                .build();
            signatureRepo.save(sig);

            // Update document status to APPROVED if role is Approver
            if ("Approver".equalsIgnoreCase(role)) {
                doc.setStatus(Document.DocumentStatus.APPROVED);
                documentRepo.save(doc);
            }

            // Generate visual stamp SVG
            String stampSvg = sigService.generateSignatureStampSvg(record);

            return ResponseEntity.ok(Map.of(
                "signatureId",  record.id(),
                "signerName",   record.signerName(),
                "role",         record.role(),
                "signedAt",     record.signedAt().toString(),
                "status",       "VALID",
                "stampSvg",     stampSvg,
                "version",      signedVersion.getVersionNumber(),
                "documentStatus", doc.getStatus().name()
            ));

        } catch (Exception e) {
            return ResponseEntity.status(500)
                .body(Map.of("error", "Signing failed: " + e.getMessage()));
        }
    }

    // ── POST /api/signatures/{signatureId}/verify ────────────────
    @PostMapping("/{signatureId}/verify")
    public ResponseEntity<?> verifySignature(@PathVariable String signatureId) {
        var sigOpt = signatureRepo.findBySignatureId(signatureId);
        if (sigOpt.isEmpty()) return ResponseEntity.notFound().build();
        var sig = sigOpt.get();

        try {
            byte[] fileBytes = Files.readAllBytes(signedBytesPath(sig));

            // Build a SignatureRecord for verification
            SignatureRecord record = new SignatureRecord(
                sig.getSignatureId(), sig.getSignerName(), sig.getSignerEmail(),
                sig.getRole(), sig.getReason(), sig.getLocation(),
                sig.getDocumentHash(), sig.getSignatureB64(), sig.getCertificateB64(),
                sig.getSignedAt(), sig.getAlgorithm(), sig.getStatus().name()
            );

            VerificationResult result = sigService.verifySignature(fileBytes, record);

            // Update status if tampered
            if (!"VALID".equals(result.status())) {
                sig.setStatus(DocumentSignature.SignatureStatus.valueOf(result.status()));
                signatureRepo.save(sig);
            }

            return ResponseEntity.ok(Map.of(
                "valid",   result.valid(),
                "status",  result.status(),
                "message", result.message()
            ));

        } catch (Exception e) {
            return ResponseEntity.status(500)
                .body(Map.of("error", "Verification failed: " + e.getMessage()));
        }
    }

    // ── DELETE /api/signatures/{signatureId} ─────────────────────
    @DeleteMapping("/{signatureId}")
    public ResponseEntity<Void> revokeSignature(
        @PathVariable String signatureId,
        @AuthenticationPrincipal UserDetails principal
    ) {
        var sigOpt = signatureRepo.findBySignatureId(signatureId);
        if (sigOpt.isEmpty()) return ResponseEntity.notFound().build();
        var sig = sigOpt.get();

        // Only the signer or admin can revoke
        if (!sig.getSignerName().equals(principal.getUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        signatureRepo.delete(sig);
        return ResponseEntity.noContent().build();
    }

    /**
     * The bytes this signature actually covers.
     *
     * <p>Always the signed version, never the document head — the head moves
     * every time the document is redacted, OCR'd or filled, and verifying
     * against it would report every earlier signature as TAMPERED. Signatures
     * predating versioning have no version and fall back to the head, which
     * is the file they were taken from.
     */
    private Path signedBytesPath(DocumentSignature signature) {
        var version = signature.getDocumentVersion();
        return Paths.get(version != null
            ? version.getFilePath()
            : signature.getDocument().getFilePath());
    }

    private Map<String,Object> toResponse(DocumentSignature s) {
        var response = new LinkedHashMap<String,Object>();
        response.put("id",          s.getId());
        response.put("signatureId", s.getSignatureId());
        response.put("signerName",  s.getSignerName());
        response.put("signerEmail", s.getSignerEmail() != null ? s.getSignerEmail() : "");
        response.put("role",        s.getRole() != null ? s.getRole() : "");
        response.put("reason",      s.getReason() != null ? s.getReason() : "");
        response.put("status",      s.getStatus().name());
        response.put("signedAt",    s.getSignedAt().toString());
        // Which revision this attests to — a signature on v2 says nothing
        // about v5, and the UI needs to show that distinction.
        response.put("version",
            s.getDocumentVersion() != null ? s.getDocumentVersion().getVersionNumber() : null);
        return response;
    }
}
