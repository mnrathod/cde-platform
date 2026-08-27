package com.cde.platform.security;

import java.util.Set;

/**
 * The permissions that gate administering a tenant's own membership.
 *
 * <p>Separate from the container vocabulary because it answers a different
 * question. The container permissions describe the ISO 19650 division of
 * labour — who originates information and who authorises it — and deliberately
 * do not stack into a seniority ladder. Managing who is in the organisation at
 * all is not a step on that ladder; it is a different axis, and an engineer
 * holding {@code container:write} should not thereby be able to invite people.
 */
public final class TenantPermission {

    private TenantPermission() {
    }

    /**
     * Invite people into the tenant, see who has been invited, and revoke an
     * invitation before it is used.
     *
     * <p>This is the authority to decide who is inside the isolation boundary,
     * so it is the most consequential permission the platform has: everything
     * else only decides what somebody already inside may do.
     */
    public static final String MANAGE_USERS = "tenant.user:manage";

    /**
     * Read and export this tenant's audit trail.
     *
     * <p>Separate from {@link #MANAGE_USERS} because reading the trail and
     * deciding who is in the organisation are different jobs, and an auditor
     * needs the first without the second. Both currently belong to the
     * administrator role, but keeping them distinct is what makes a read-only
     * Auditor role (§5.5) an assignment change rather than a redesign.
     *
     * <p>There is no corresponding write permission, and there will not be. The
     * trail is written by the platform as a side effect of what it does, not by
     * anyone holding a permission, and the application role cannot modify it at
     * all (see {@code V5__audit_trail.sql}).
     */
    public static final String READ_AUDIT_TRAIL = "tenant.audit:read";

    public static final Set<String> ALL = Set.of(MANAGE_USERS, READ_AUDIT_TRAIL);
}
