package com.cde.platform.model;

import com.cde.platform.tenancy.TenantAssigningListener;
import com.cde.platform.tenancy.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@EntityListeners(TenantAssigningListener.class)
@Table(name = "documents")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document implements TenantScoped {

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

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "file_type")
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    /**
     * Version number that {@link #filePath} currently points at. Denormalised
     * from document_versions so the viewer can cache-bust its PDF request
     * without a second query; {@code null} for documents uploaded before
     * versioning existed, which are backfilled on their first processing run.
     */
    @Column(name = "current_version")
    private Integer currentVersion;

    @Enumerated(EnumType.STRING)
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    private DocumentStatus status;

    private String revision;

    @Column(name = "drawing_number")
    private String drawingNumber;

    @Column(name = "sheet_number")
    private String sheetNumber;

    // SVG/vector data for 2D drawings stored inline for small files
    @Column(name = "vector_data", columnDefinition = "TEXT")
    private String vectorData;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = DocumentStatus.DRAFT;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum DocumentType {
        DRAWING, SPECIFICATION, REPORT, SCHEDULE, BIM_MODEL, POINT_CLOUD, OTHER
    }

    public enum DocumentStatus {
        DRAFT, IN_REVIEW, APPROVED, SUPERSEDED, VOID
    }
}
