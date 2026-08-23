package com.cde.platform.tenancy;

import jakarta.persistence.PrePersist;

/**
 * Stamps the current tenant onto every new row.
 *
 * <p>A JPA entity listener rather than a {@code @PrePersist} on each entity:
 * the specification allows only one callback of each type per class, and all
 * seven entities already use theirs for timestamps. A listener also runs before
 * the entity's own callback, so it cannot be skipped by an entity that forgets
 * to call {@code super}.
 *
 * <p>The assignment is not merely a convenience. The Row-Level Security policy
 * carries a {@code WITH CHECK} clause, so an insert whose {@code tenant_id}
 * does not match the session's context is rejected by the database outright.
 * Without this the write would fail rather than land in the wrong tenant — but
 * it would fail on every single insert, which is not a useful application.
 */
public class TenantAssigningListener {

    @PrePersist
    void assignCurrentTenant(TenantScoped entity) {
        if (entity.getTenantId() != null) {
            // Already set — by a data migration, a test fixture, or code
            // deliberately writing on another tenant's behalf inside
            // TenantContext.callAsTenant. Overwriting it here would silently
            // redirect the row, and the WITH CHECK clause will reject it anyway
            // if it does not match the session.
            return;
        }
        entity.setTenantId(TenantContext.requireTenantId());
    }
}
