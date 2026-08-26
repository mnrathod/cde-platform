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

    public static final Set<String> ALL = Set.of(MANAGE_USERS);
}
