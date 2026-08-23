package com.cde.platform.model;

import com.cde.platform.tenancy.TenantAssigningListener;
import com.cde.platform.tenancy.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@EntityListeners(TenantAssigningListener.class)
@Table(name = "annotations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Annotation implements TenantScoped {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @Enumerated(EnumType.STRING)
    private AnnotationType type;

    // JSON shape data: {x, y, width, height, points[], radius, text, color, ...}
    @Column(name = "shape_data", columnDefinition = "TEXT", nullable = false)
    private String shapeData;

    private String comment;

    @Enumerated(EnumType.STRING)
    private AnnotationStatus status;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = AnnotationStatus.OPEN;
    }

    public enum AnnotationType {
        COMMENT, MARKUP, DIMENSION, CLOUD, ARROW, STAMP, HIGHLIGHT,
        UNDERLINE, STRIKEOUT, SQUIGGLY
    }

    public enum AnnotationStatus {
        OPEN, RESOLVED, CLOSED
    }
}
