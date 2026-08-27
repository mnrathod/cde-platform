package com.cde.platform.audit;

/**
 * The closed vocabulary of security-relevant events.
 *
 * <p>An enum rather than free text so that the trail stays queryable and can be
 * mapped to a control matrix. Adding a value here is how a new audited action
 * is declared; the database deliberately does not constrain the column, because
 * requiring a migration to audit something new is a reliable way of getting
 * the something-new without the audit.
 *
 * <p>Names read as past-tense facts. An audit record states what happened, not
 * what was attempted — {@link AuditOutcome} carries whether it succeeded, so
 * {@code SIGN_IN} with {@code FAILURE} is a refused sign-in rather than a
 * separate action.
 */
public enum AuditAction {

    // Authentication and session
    SIGN_IN,
    SIGN_OUT,
    REGISTRATION,
    PASSWORD_CHANGE,

    // Membership and authority
    INVITATION_ISSUED,
    INVITATION_REVOKED,
    INVITATION_REDEEMED,
    ROLE_CHANGED,

    /**
     * A request that carried a valid identity but lacked the permission. The
     * single most useful signal for detecting an account probing beyond its
     * authority, and the one most often missing.
     */
    AUTHORISATION_DENIED,

    // Information containers (§6.7). A state transition is the contractual
    // event in a CDE — who authorised this revision, and when.
    CONTAINER_CREATED,
    CONTAINER_TRANSITIONED,
    CONTAINER_SUPERSEDED,

    // Documents
    DOCUMENT_UPLOADED,
    DOCUMENT_DOWNLOADED,
    DOCUMENT_DELETED,
    DOCUMENT_REJECTED_BY_SCAN,

    /**
     * Bulk read or export. Separate from a single download because volume is
     * the signal: one person reading one drawing is work, and the same person
     * reading every drawing on a sensitive project is the aggregation risk
     * ISO 19650-5 is about.
     */
    BULK_EXPORT,

    // Third-party processing (§10.1). Recorded for every call: who, when,
    // which feature and provider, and whether redaction fired — never the
    // payload.
    AI_REQUEST,

    // Configuration
    TENANT_SETTING_CHANGED,
    API_KEY_ISSUED,
    API_KEY_REVOKED
}
