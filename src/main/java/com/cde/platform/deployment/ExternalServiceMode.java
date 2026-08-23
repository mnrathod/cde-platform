package com.cde.platform.deployment;

/**
 * How a dependency outside our own infrastructure is reached — or whether it is
 * reached at all.
 *
 * <p>Every such dependency carries one of these rather than a boolean, because
 * "off" and "running locally" are genuinely different answers. An air-gapped
 * deployment still wants breached-password checking; it just cannot ask
 * anybody else to do it.
 */
public enum ExternalServiceMode {

    /** Call the third-party service. Only ever permitted on a commercial tier. */
    ONLINE_API,

    /**
     * Use a copy of the data held on our own infrastructure, refreshed on a
     * controlled schedule. Same function, no egress.
     */
    LOCAL_DATASET,

    /**
     * Off entirely. For anything that is a security control, this requires a
     * documented risk acceptance — it is not a neutral default.
     */
    DISABLED;

    public boolean requiresEgress() {
        return this == ONLINE_API;
    }
}
