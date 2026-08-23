package com.cde.platform.cde.model;

import com.cde.platform.cde.domain.ContainerState;
import com.cde.platform.model.Project;
import com.cde.platform.tenancy.TenantAssigningListener;
import com.cde.platform.tenancy.TenantScoped;
import jakarta.persistence.*;
import lombok.*;

/**
 * A tenant- or project-defined suitability code — "S2 suitable for
 * information", "A1 approved for construction", or whatever the organisation
 * uses.
 *
 * <p>The product ships the mechanism and none of the values. Organisations
 * customise these lists, and the code tables published in the standard are
 * copyrighted material that may not be reproduced in a commercial product.
 * Every list is populated by the customer or imported from their own.
 */
@Entity
@EntityListeners(TenantAssigningListener.class)
@Table(name = "suitability_codes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuitabilityCode implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** Null means the code applies tenant-wide rather than to one project. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    /**
     * The container state this code may be applied in, so a drawing cannot be
     * marked approved for construction while it is still work in progress.
     * Null means it is valid in any state.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "valid_in_state", length = 20)
    private ContainerState validInState;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
