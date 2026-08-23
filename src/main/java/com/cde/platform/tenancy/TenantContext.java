package com.cde.platform.tenancy;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The tenant whose data the current thread is permitted to see.
 *
 * <p>This is the single source of the value that {@link TenantAwareDataSource}
 * pushes into the database session, which is in turn the only thing the
 * Row-Level Security policies consult. It is set from the authenticated
 * principal and from nowhere else — never from a request parameter, header, or
 * body, because any of those would let a caller nominate the tenant they wanted
 * to read.
 *
 * <p>Absence is meaningful and safe: a thread with no tenant set produces a
 * database session with no {@code app.tenant_id}, and every policy then matches
 * no rows at all. A background job that forgets to establish context reads
 * nothing rather than reading everything.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<Long> currentTenantId() {
        return Optional.ofNullable(CURRENT_TENANT_ID.get());
    }

    public static boolean isSet() {
        return CURRENT_TENANT_ID.get() != null;
    }

    /**
     * @throws IllegalStateException when no tenant is bound. Callers that
     *         genuinely operate outside a tenant should not be asking.
     */
    public static long requireTenantId() {
        Long tenantId = CURRENT_TENANT_ID.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                "No tenant context bound to this thread. Requests establish it from the "
                + "authenticated principal; background work must use TenantContext.callAsTenant.");
        }
        return tenantId;
    }

    /**
     * Runs {@code work} with the given tenant bound, restoring whatever was
     * previously bound afterwards.
     *
     * <p>This is the only sanctioned way for scheduled tasks, message
     * consumers, and seeding to touch tenant data (§5.6). Restoring the
     * previous value rather than clearing it keeps nesting honest: work
     * dispatched for one tenant from inside another's request does not leave
     * the outer request looking at the wrong data when it returns.
     */
    public static <T> T callAsTenant(long tenantId, Supplier<T> work) {
        Long previous = CURRENT_TENANT_ID.get();
        CURRENT_TENANT_ID.set(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT_TENANT_ID.remove();
            } else {
                CURRENT_TENANT_ID.set(previous);
            }
        }
    }

    public static void runAsTenant(long tenantId, Runnable work) {
        callAsTenant(tenantId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Binds a tenant for the remainder of the current request.
     *
     * <p>Package-private on purpose — only the filter that reads the
     * authenticated principal may call it. Everything else goes through
     * {@link #callAsTenant}, which cannot leak because it always restores.
     */
    static void bind(long tenantId) {
        CURRENT_TENANT_ID.set(tenantId);
    }

    /**
     * Must be called in a {@code finally} by whatever called {@link #bind}.
     * Threads are pooled; a value left behind is inherited by whichever
     * unrelated request picks the thread up next, which is a cross-tenant leak
     * with no bug anywhere near it.
     */
    static void clear() {
        CURRENT_TENANT_ID.remove();
    }
}
