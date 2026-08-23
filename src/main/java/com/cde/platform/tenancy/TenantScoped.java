package com.cde.platform.tenancy;

/**
 * Implemented by every entity whose rows belong to exactly one tenant.
 *
 * <p>The interface exists so {@link TenantAssigningListener} can populate the
 * column without reflection and without each entity repeating the same
 * lifecycle hook. Implementing it is also the declaration that a table is
 * tenant-scoped, which {@code TenantIsolationArchitectureTest} checks against
 * the set of tables the migrations actually protect — so a new entity added
 * without tenancy fails the build rather than shipping unprotected.
 */
public interface TenantScoped {

    Long getTenantId();

    void setTenantId(Long tenantId);
}
