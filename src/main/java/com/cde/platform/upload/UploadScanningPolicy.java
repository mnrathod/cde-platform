package com.cde.platform.upload;

/**
 * What happens when the malware scanner cannot answer.
 *
 * <p>The interesting decision is not "do we scan" — it is what an unreachable
 * scanner means. A boolean would collapse "no scanner configured" and "the
 * scanner is down right now" into the same answer, and those need opposite
 * treatment: the first is a deployment that has chosen not to scan, and the
 * second is a deployment that has chosen to scan and currently cannot.
 */
public enum UploadScanningPolicy {

    /**
     * Uploads are refused unless a scanner confirms them clean. A scanner that
     * is down stops uploads.
     *
     * <p>The correct setting wherever uploads are shared between organisations,
     * which is what a Common Data Environment is for: a file admitted
     * unscanned is one every appointed party on the project will open.
     */
    REQUIRED,

    /**
     * Scan when the scanner answers; admit the file with an audited warning
     * when it does not.
     *
     * <p>Availability over certainty. Defensible for a single-tenant internal
     * deployment; not for a shared one.
     */
    BEST_EFFORT,

    /**
     * Do not scan. A deliberate choice with a documented risk acceptance, not a
     * neutral default — which is why it has to be named rather than reached by
     * leaving something unset.
     */
    DISABLED;

    public boolean requiresACleanVerdict() {
        return this == REQUIRED;
    }

    public boolean scansAtAll() {
        return this != DISABLED;
    }
}
