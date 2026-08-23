package com.cde.platform.tenancy;

/**
 * The narrow seam through which authentication code — and only authentication
 * code — binds a tenant to the current request thread.
 *
 * <p>{@link TenantContext#bind} is package-private so that no service or
 * controller can nominate its own tenant; this class is the single sanctioned
 * caller. Everything else uses {@link TenantContext#callAsTenant}, which always
 * restores the previous value and therefore cannot leak.
 */
public final class TenantContextBinder {

    private TenantContextBinder() {
    }

    public static void bind(long tenantId) {
        TenantContext.bind(tenantId);
    }

    public static void clear() {
        TenantContext.clear();
    }

    /**
     * Puts back whatever was bound before, or clears if nothing was.
     *
     * <p>Filters must restore rather than clear. Clearing unconditionally
     * destroys context the filter did not establish — an outer caller's, or a
     * test fixture's — and the symptom is not an error but an empty result set,
     * because an unbound context matches no rows rather than failing.
     */
    public static void restore(java.util.Optional<Long> previous) {
        previous.ifPresentOrElse(TenantContext::bind, TenantContext::clear);
    }
}
