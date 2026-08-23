package com.cde.platform.cde.model;

import com.cde.platform.cde.domain.ContainerState;
import com.cde.platform.model.User;
import com.cde.platform.tenancy.TenantAssigningListener;
import com.cde.platform.tenancy.TenantScoped;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One recorded state change: who moved a revision, from what to what, when, and
 * why.
 *
 * <p>Append-only, and enforced as such by the database rather than by
 * convention — the application role holds INSERT and SELECT on this table and
 * nothing else, so no code path can rewrite or remove a transition however
 * privileged it is within the application.
 */
@Entity
@EntityListeners(TenantAssigningListener.class)
@Table(name = "container_state_transitions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContainerStateTransition implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revision_id", nullable = false, updatable = false)
    private ContainerRevision revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", nullable = false, length = 20, updatable = false)
    private ContainerState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 20, updatable = false)
    private ContainerState toState;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by", nullable = false, updatable = false)
    private User performedBy;

    @Column(name = "performed_at", nullable = false, updatable = false)
    private LocalDateTime performedAt;

    @Column(length = 1000, updatable = false)
    private String reason;

    @PrePersist
    void onCreate() {
        if (performedAt == null) {
            performedAt = LocalDateTime.now();
        }
    }
}
