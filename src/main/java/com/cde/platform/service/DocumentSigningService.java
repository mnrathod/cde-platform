package com.cde.platform.service;

import com.cde.platform.exception.DocumentProcessingException;
import com.cde.platform.model.Document;
import com.cde.platform.model.DocumentSignature;
import com.cde.platform.model.DocumentVersion;
import com.cde.platform.model.DocumentVersion.DocumentOperation;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.DocumentSignatureRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.service.DigitalSignatureService.SelfSignedCert;
import com.cde.platform.service.DigitalSignatureService.SignatureRecord;
import com.cde.platform.service.DigitalSignatureService.VerificationResult;
import com.cde.platform.service.PdfSignatureEmbedder.SigningDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs documents and checks the signatures afterwards.
 *
 * <p>Signing writes the signature into the PDF itself and commits the result
 * as a new version. Recording a hash in the database alone — what this used
 * to do — produces a signature only this application can see: the document
 * opens elsewhere showing nothing, and a recipient outside the system has
 * nothing to verify.
 *
 * <p>The database record is still written, because it is what lets the
 * application answer "who signed this, when, and in what role" without
 * parsing every version, and because documents that cannot carry an embedded
 * signature — anything that is not a PDF — still need to be signable.
 */
@Service
public class DocumentSigningService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSigningService.class);

    private static final String ORGANISATION = "CDE Platform";

    private final DigitalSignatureService     signatures;
    private final PdfSignatureEmbedder        embedder;
    private final DocumentVersionService      versionService;
    private final DocumentRepository          documentRepo;
    private final DocumentSignatureRepository signatureRepo;
    private final UserRepository              userRepo;

    /**
     * Per-user signing certificates.
     *
     * <p>Generated on demand and held in memory, so they do not survive a
     * restart. Adequate for demonstrating signing; a deployment that needs
     * signatures to be attributable to a real identity has to supply keys
     * from a keystore or an HSM instead.
     */
    private final Map<String, SelfSignedCert> certificates = new ConcurrentHashMap<>();

    public DocumentSigningService(DigitalSignatureService signatures,
                                  PdfSignatureEmbedder embedder,
                                  DocumentVersionService versionService,
                                  DocumentRepository documentRepo,
                                  DocumentSignatureRepository signatureRepo,
                                  UserRepository userRepo) {
        this.signatures     = signatures;
        this.embedder       = embedder;
        this.versionService = versionService;
        this.documentRepo   = documentRepo;
        this.signatureRepo  = signatureRepo;
        this.userRepo       = userRepo;
    }

    /** What the signer is asserting. */
    public record SignRequest(String role, String reason, String location) {

        public SignRequest {
            if (role     == null || role.isBlank())     role     = "Reviewer";
            if (reason   == null || reason.isBlank())   reason   = "Document review and approval";
            if (location == null || location.isBlank()) location = ORGANISATION;
        }

        boolean isApproval() {
            return "Approver".equalsIgnoreCase(role);
        }
    }

    /**
     * @param embedded true when the signature is inside the document and any
     *                 conforming reader can check it
     */
    public record SigningOutcome(
        DocumentSignature signature,
        DocumentVersion   version,
        boolean           embedded,
        String            stampSvg,
        String            documentStatus
    ) {}

    /** @param embedded whether the check read a signature inside the document */
    public record VerificationOutcome(boolean valid, String status, String message, boolean embedded) {}

    // ── Signing ──────────────────────────────────────────────────

    @Transactional
    public SigningOutcome sign(Long documentId, String username, SignRequest request) {
        Document document = requireDocumentWithFile(documentId);
        User signer = userRepo.findByUsername(username)
            .orElseThrow(() -> new DocumentProcessingException("Unknown signer."));

        SelfSignedCert cert = certificateFor(signer);
        DocumentVersion source = versionService.currentVersion(document, signer);

        DocumentVersion signedVersion = source;
        boolean embedded = false;

        if (isPdf(document)) {
            signedVersion = embedInto(document, source, cert, signer, request);
            embedded = true;
        } else {
            // Nothing else can carry a signature dictionary, so the record
            // stands alone — and says so, rather than implying portability
            // it does not have.
            log.info("Document {} is not a PDF; recording a detached signature only", documentId);
        }

        SignatureRecord record = createRecord(signedVersion, signer, cert, request);
        DocumentSignature saved = persist(document, signedVersion, signer, record, embedded);

        if (request.isApproval()) {
            document.setStatus(Document.DocumentStatus.APPROVED);
            documentRepo.save(document);
        }

        return new SigningOutcome(saved, signedVersion, embedded,
            signatures.generateSignatureStampSvg(record), document.getStatus().name());
    }

    /**
     * Writes the signature into the document and commits the result.
     *
     * <p>The committed version is the one the signature covers: a PDF
     * signature protects the file it lives in, so the bytes being attested to
     * are the bytes that now include the signature container.
     */
    private DocumentVersion embedInto(Document document, DocumentVersion source,
                                      SelfSignedCert cert, User signer, SignRequest request) {
        Path output = null;
        try {
            output = versionService.allocateWorkPath(document, "signed");
            embedder.embed(Paths.get(source.getFilePath()), output, cert,
                new SigningDetails(signer.getUsername(), request.reason(), request.location()));

            DocumentVersion committed = versionService.commit(document, output,
                DocumentOperation.SIGN,
                "Signed by %s as %s".formatted(signer.getUsername(), request.role()),
                signer);
            output = null;   // ownership passed to the version chain
            return committed;

        } catch (IOException e) {
            log.error("Could not embed a signature into document {}", document.getId(), e);
            throw new DocumentProcessingException("The document could not be signed.", e);
        } finally {
            discard(output);
        }
    }

    // ── Verification ─────────────────────────────────────────────

    /**
     * Checks a signature against the bytes it covers.
     *
     * <p>Prefers the signature inside the document, because that is the one a
     * recipient outside this system would check. Signatures taken before
     * embedding existed have nothing in the file and fall back to the stored
     * record.
     */
    @Transactional
    public Optional<VerificationOutcome> verify(String signatureId) {
        Optional<DocumentSignature> found = signatureRepo.findBySignatureId(signatureId);
        if (found.isEmpty()) return Optional.empty();

        DocumentSignature signature = found.get();
        Path path = signedBytesPath(signature);

        VerificationOutcome outcome;
        try {
            outcome = embedder.verifyEmbedded(path)
                .map(check -> new VerificationOutcome(
                    check.valid(),
                    check.valid() ? "VALID" : "TAMPERED",
                    check.message(),
                    true))
                .orElseGet(() -> detachedVerification(signature, path));
        } catch (IOException e) {
            log.warn("Could not read {} to verify signature {}: {}",
                     path, signatureId, e.getMessage());
            outcome = new VerificationOutcome(false, "INVALID",
                "The signed document could not be read.", false);
        }

        if (!"VALID".equals(outcome.status())) {
            signature.setStatus(DocumentSignature.SignatureStatus.valueOf(outcome.status()));
            signatureRepo.save(signature);
        }
        return Optional.of(outcome);
    }

    /** The pre-embedding check: a hash recorded in the database. */
    private VerificationOutcome detachedVerification(DocumentSignature signature, Path path) {
        try {
            VerificationResult result = signatures.verifySignature(
                Files.readAllBytes(path), toRecord(signature));
            return new VerificationOutcome(
                result.valid(), result.status(), result.message(), false);
        } catch (IOException e) {
            return new VerificationOutcome(false, "INVALID",
                "The signed document could not be read.", false);
        }
    }

    public List<DocumentSignature> signaturesFor(Long documentId) {
        return signatureRepo.findByDocument_IdOrderBySignedAtDesc(documentId);
    }

    /** @return false when no such signature exists, empty when not the signer's */
    @Transactional
    public Optional<Boolean> revoke(String signatureId, String username) {
        Optional<DocumentSignature> found = signatureRepo.findBySignatureId(signatureId);
        if (found.isEmpty()) return Optional.empty();

        DocumentSignature signature = found.get();
        if (!signature.getSignerName().equals(username)) return Optional.of(false);

        // Only the database record goes. A signature already written into a
        // version stays in that version's bytes — removing it would mean
        // rewriting a file someone has attested to.
        signatureRepo.delete(signature);
        return Optional.of(true);
    }

    // ── Internals ────────────────────────────────────────────────

    private SelfSignedCert certificateFor(User signer) {
        return certificates.computeIfAbsent(signer.getUsername(), username -> {
            try {
                return signatures.generateSelfSignedCert(username, ORGANISATION);
            } catch (Exception e) {
                throw new DocumentProcessingException("A signing certificate could not be created.", e);
            }
        });
    }

    private SignatureRecord createRecord(DocumentVersion version, User signer,
                                         SelfSignedCert cert, SignRequest request) {
        try {
            return signatures.createSignatureRecord(
                Files.readAllBytes(Paths.get(version.getFilePath())),
                signer.getUsername(),
                signer.getEmail() != null ? signer.getEmail()
                                          : signer.getUsername() + "@cde.platform",
                request.role(), request.reason(), request.location(), cert);
        } catch (Exception e) {
            throw new DocumentProcessingException("The signature could not be recorded.", e);
        }
    }

    private DocumentSignature persist(Document document, DocumentVersion version, User signer,
                                      SignatureRecord record, boolean embedded) {
        return signatureRepo.save(DocumentSignature.builder()
            .signatureId(record.id())
            .document(document).documentVersion(version).signer(signer)
            .signerName(record.signerName()).signerEmail(record.signerEmail())
            .role(record.role()).reason(record.reason()).location(record.location())
            .documentHash(record.documentHash())
            .signatureB64(record.signatureB64())
            .certificateB64(record.certificateB64())
            .algorithm(embedded ? record.algorithm() + " (embedded)" : record.algorithm())
            .status(DocumentSignature.SignatureStatus.VALID)
            .signedAt(LocalDateTime.now())
            .build());
    }

    private SignatureRecord toRecord(DocumentSignature signature) {
        return new SignatureRecord(
            signature.getSignatureId(), signature.getSignerName(), signature.getSignerEmail(),
            signature.getRole(), signature.getReason(), signature.getLocation(),
            signature.getDocumentHash(), signature.getSignatureB64(),
            signature.getCertificateB64(), signature.getSignedAt(),
            signature.getAlgorithm(), signature.getStatus().name());
    }

    /**
     * The bytes a signature covers — always its own version, never the
     * document head, which moves every time the document is processed.
     */
    private Path signedBytesPath(DocumentSignature signature) {
        DocumentVersion version = signature.getDocumentVersion();
        return Paths.get(version != null ? version.getFilePath()
                                         : signature.getDocument().getFilePath());
    }

    private boolean isPdf(Document document) {
        String type = document.getFileType() != null ? document.getFileType().toLowerCase() : "";
        String name = document.getFileName() != null ? document.getFileName().toLowerCase() : "";
        return type.contains("pdf") || name.endsWith(".pdf");
    }

    private Document requireDocumentWithFile(Long documentId) {
        Document document = documentRepo.findById(documentId)
            .orElseThrow(() -> new DocumentProcessingException("Document not found."));
        if (document.getFilePath() == null || document.getFilePath().isBlank()) {
            throw new DocumentProcessingException("This document has no stored file.");
        }
        return document;
    }

    private void discard(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not clean up {}: {}", path, e.getMessage());
        }
    }
}
