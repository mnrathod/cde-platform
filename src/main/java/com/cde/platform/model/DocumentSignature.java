package com.cde.platform.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "document_signatures")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DocumentSignature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signature_id", unique = true, nullable = false)
    private String signatureId;   // UUID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    /**
     * The exact version whose bytes were signed.
     *
     * <p>Verification hashes this version rather than the document's current
     * file. Without it, any later processing run — an OCR pass, a redaction —
     * would change the head's bytes and flip every existing signature to
     * TAMPERED, even though nobody had touched what was actually signed.
     *
     * <p>Null for signatures taken before versioning existed; those fall back
     * to the document head, which is the file they were signed against.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_version_id")
    private DocumentVersion documentVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signer_id")
    private User signer;

    @Column(name = "signer_name")  private String signerName;
    @Column(name = "signer_email") private String signerEmail;

    @Column(name = "role")   private String role;     // Author, Reviewer, Approver
    @Column(name = "reason") private String reason;
    @Column(name = "location") private String location;

    @Column(name = "document_hash", length = 100)  private String documentHash;
    @Column(name = "signature_b64", columnDefinition = "TEXT") private String signatureB64;
    @Column(name = "certificate_b64", columnDefinition = "TEXT") private String certificateB64;
    @Column(name = "algorithm") private String algorithm;

    @Enumerated(EnumType.STRING)
    private SignatureStatus status;

    @Column(name = "signed_at") private LocalDateTime signedAt;

    @PrePersist
    void prePersist() { if (signedAt == null) signedAt = LocalDateTime.now(); }

    public enum SignatureStatus { VALID, INVALID, TAMPERED, EXPIRED }
}
