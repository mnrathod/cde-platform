package com.cde.platform.model;

import com.cde.platform.tenancy.TenantAssigningListener;
import com.cde.platform.tenancy.TenantScoped;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * An immutable snapshot of a document's bytes at one point in its history.
 *
 * <p>Document processing (redact, OCR, flatten, form-fill) used to stream its
 * result straight back to the browser as a download, which meant the operations
 * could not be chained: OCR'ing a scan and then redacting it produced two
 * unrelated files rather than one document carrying both changes. Each
 * operation now commits its output as a new version and advances
 * {@link Document#getFilePath()} to point at it, so the next operation reads
 * the previous one's result and the changes accumulate.
 *
 * <p>Versions are never mutated or deleted by processing. Restoring an earlier
 * version copies it forward as a new version rather than truncating history,
 * which keeps signatures anchored to the bytes they actually signed.
 */
@Entity
@EntityListeners(TenantAssigningListener.class)
@Table(
    name = "document_versions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_document_version",
        columnNames = {"document_id", "version_number"}
    ),
    indexes = @Index(name = "idx_document_version_document", columnList = "document_id")
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentVersion implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The tenant this row belongs to. Populated by {@link TenantAssigningListener}
     * on insert and never changed afterwards — a row does not move between
     * tenants, and the RLS policy would reject the update if it tried.
     */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Document document;

    /** 1-based and gap-free. Version 1 is always the uploaded original. */
    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "file_name")
    private String fileName;

    /** Absolute path to this version's bytes. Never reused between versions. */
    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_size")
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false)
    private DocumentOperation operation;

    /** Human-readable note, e.g. "Redacted 3 regions across 2 pages". */
    @Column(name = "summary", length = 500)
    private String summary;

    /**
     * SHA-256 of this version's bytes, hex encoded. Signature verification
     * compares against this rather than re-reading the document head, so a
     * signature stays valid when a later version is committed.
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /** The processing step that produced a version. */
    public enum DocumentOperation {
        UPLOAD,
        REDACT,
        OCR,
        FLATTEN,
        /** Values were entered into existing fields. */
        FORM_FILL,
        /**
         * Fields were added to or removed from the form itself. Distinct from
         * FORM_FILL because the audit trail should not report adding and
         * removing fields as though someone had filled them in.
         */
        FORM_DESIGN,
        /**
         * Any change to the page tree — reorder, delete, rotate, duplicate,
         * insert. One value rather than five because they arrive together
         * from the page organiser as a single rearrangement; which of them
         * happened is described in the version summary.
         */
        PAGES,
        /** A digital signature was embedded into the document. */
        SIGN,
        RESTORE
    }
}
