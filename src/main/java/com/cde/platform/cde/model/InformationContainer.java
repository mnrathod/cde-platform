package com.cde.platform.cde.model;

import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.tenancy.TenantAssigningListener;
import com.cde.platform.tenancy.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The identity of a piece of project information, persisting across every
 * revision of its content.
 *
 * <p>A container is not a file. It is the thing a drawing number refers to —
 * "the ground floor structural plan" — of which revision P01.01 and revision
 * C01 are successive expressions. Modelling it as an attribute of a file record
 * instead is what makes supersession lineage impossible to reconstruct later.
 */
@Entity
@EntityListeners(TenantAssigningListener.class)
@Table(name = "information_containers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InformationContainer implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** The assembled, delimited identifier a person reads and searches on. */
    @Column(name = "container_reference", nullable = false, length = 255)
    private String containerReference;

    /**
     * The individual naming fields, keyed by the field name the project's
     * convention uses.
     *
     * <p>Held as JSON rather than as columns because ISO 19650-2's field
     * pattern is configurable per project — the delimiter, the field set and
     * the permitted values all differ between organisations, and the standard's
     * own code tables are copyrighted and cannot be shipped. Fixed columns
     * would bake one organisation's convention into the schema.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "naming_fields", nullable = false)
    @Builder.Default
    private Map<String, String> namingFields = new LinkedHashMap<>();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
