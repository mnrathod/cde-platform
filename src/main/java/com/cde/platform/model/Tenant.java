package com.cde.platform.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * An isolated customer organisation.
 *
 * <p>Deliberately <em>not</em> {@code TenantScoped}: this is the table that
 * defines the scope, so it carries no {@code tenant_id} and no Row-Level
 * Security policy. It follows that any application connection can read the
 * whole registry, which is why no endpoint exposes it — the slugs and names are
 * a customer list.
 */
@Entity
@Table(name = "tenants")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Stable, URL-safe identifier. Used for home-realm discovery and for
     * namespacing cache keys, queue headers and object-storage prefixes, so it
     * is constrained to lowercase alphanumerics and hyphens by a database check
     * constraint as well as here.
     */
    @Column(nullable = false, unique = true, length = 63)
    private String slug;

    @Column(nullable = false, length = 200)
    private String name;

    /**
     * The residency boundary. A tenant is bound to a region at creation and all
     * of its data — primary store, replicas, backups, cache, queues, indexes,
     * logs and exports — stays inside it.
     */
    @Column(nullable = false, length = 50)
    @Builder.Default
    private String region = "default";

    /**
     * Sets the ceiling a tenant administrator cannot exceed: password expiry
     * interval, whether external services may be called at all, and which
     * cryptographic provider is permitted.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_tier", nullable = false, length = 20)
    @Builder.Default
    private DeploymentTier deploymentTier = DeploymentTier.COMMERCIAL;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public enum DeploymentTier {
        /** External services permitted; password expiry configurable 30–365 days. */
        COMMERCIAL,
        /** IRAP-scoped: ASD-approved algorithms, expiry ceiling 90 days. */
        GOVERNMENT,
        /** Air-gapped: no outbound calls, policy locked by contract. */
        DEFENCE
    }
}
