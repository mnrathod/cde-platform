package com.cde.platform.cde.model;

import com.cde.platform.cde.domain.ContainerState;
import com.cde.platform.model.Document;
import com.cde.platform.model.User;
import com.cde.platform.tenancy.TenantAssigningListener;
import com.cde.platform.tenancy.TenantScoped;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One expression of a container's content, with a state and a place in the
 * supersession lineage.
 *
 * <p>Once published, a revision is frozen. There are no setters that change its
 * content, and a database trigger refuses any UPDATE or DELETE that would —
 * because an application-level rule holds only for as long as every future call
 * site remembers to consult it, and the cost of one that forgets is a silently
 * rewritten contractual record.
 */
@Entity
@EntityListeners(TenantAssigningListener.class)
@Table(name = "container_revisions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContainerRevision implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_id", nullable = false, updatable = false)
    private InformationContainer container;

    /**
     * Preliminary or contractual, per the project's configured scheme —
     * P01.01, C01. Free text here and validated by the application against the
     * project's convention, because the scheme differs between organisations.
     */
    @Column(name = "revision_code", nullable = false, length = 20, updatable = false)
    private String revisionCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ContainerState state = ContainerState.WORK_IN_PROGRESS;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suitability_code_id")
    private SuitabilityCode suitabilityCode;

    /**
     * The bytes, where a document record exists. Linking rather than duplicating
     * keeps the viewer, markup and existing versioning working unchanged.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private Document document;

    @Column(name = "file_path", length = 1000)
    private String filePath;

    /** What this revision replaced. Null for the first revision of a container. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_revision_id", updatable = false)
    private ContainerRevision supersedes;

    /**
     * What replaced this revision. Recorded in both directions so the lineage is
     * reconstructible from either end without a recursive scan, and writable
     * exactly once — the trigger refuses to rewrite it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "superseded_by_revision_id")
    private ContainerRevision supersededBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Who authorised publication. A contractual question rather than an audit
     * convenience, which is why a CHECK constraint refuses a published row
     * without it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_by")
    private User publishedBy;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "approval_reason", length = 1000)
    private String approvalReason;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /** Whether this revision is the container's current authorised expression. */
    public boolean isCurrentPublished() {
        return state == ContainerState.PUBLISHED && supersededBy == null;
    }
}
