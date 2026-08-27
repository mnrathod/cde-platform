package com.cde.platform.audit;

/**
 * One event, described completely, for {@link AuditTrailService#record}.
 *
 * <p>A parameter object rather than a fourteen-argument method (§3.3). It also
 * makes the builder below the only construction path, which is what keeps
 * callers from silently omitting the request context: the two {@code by}
 * factories name what an actor is, so an anonymous event is a deliberate
 * choice rather than a forgotten argument.
 *
 * @param actorLabel a readable identity kept alongside the id, so a record
 *                   stays meaningful after the account is deleted — and the
 *                   only identity available when there is no account, as on a
 *                   refused sign-in
 */
public record AuditRequest(AuditAction action,
                           AuditOutcome outcome,
                           Long actorUserId,
                           String actorLabel,
                           String sourceIp,
                           String userAgent,
                           String targetType,
                           String targetId,
                           String traceId,
                           AuditableChange change) {

    /** What is recorded when a request carries no identity at all. */
    public static final String ANONYMOUS_ACTOR = "anonymous";

    public static Builder by(Long userId, String username) {
        return new Builder(userId, username);
    }

    /**
     * For an event with no authenticated actor — a refused sign-in, a
     * registration, a redeemed invitation.
     *
     * @param attemptedIdentity what the caller claimed to be, which is the only
     *                          identity there is. Bounded by the column and
     *                          never interpolated anywhere.
     */
    public static Builder byUnauthenticated(String attemptedIdentity) {
        return new Builder(null, attemptedIdentity == null || attemptedIdentity.isBlank()
            ? ANONYMOUS_ACTOR : attemptedIdentity);
    }

    public static final class Builder {

        private static final int MAX_LABEL_LENGTH = 255;
        private static final int MAX_USER_AGENT_LENGTH = 512;
        private static final int MAX_TARGET_ID_LENGTH = 64;

        private final Long actorUserId;
        private final String actorLabel;
        private AuditAction action;
        private AuditOutcome outcome = AuditOutcome.SUCCESS;
        private String sourceIp;
        private String userAgent;
        private String targetType;
        private String targetId;
        private String traceId;
        private AuditableChange change = AuditableChange.none();

        private Builder(Long actorUserId, String actorLabel) {
            this.actorUserId = actorUserId;
            this.actorLabel = truncate(actorLabel, MAX_LABEL_LENGTH);
        }

        public Builder did(AuditAction action) {
            this.action = action;
            return this;
        }

        public Builder outcome(AuditOutcome outcome) {
            this.outcome = outcome;
            return this;
        }

        public Builder to(String targetType, Object targetId) {
            this.targetType = targetType;
            this.targetId = targetId == null
                ? null : truncate(String.valueOf(targetId), MAX_TARGET_ID_LENGTH);
            return this;
        }

        /**
         * Both values come from the request and are therefore partly
         * attacker-chosen, so both are truncated to the column width here
         * rather than at the database — an over-long user agent should not turn
         * an audited action into a failed one.
         */
        public Builder from(String sourceIp, String userAgent) {
            this.sourceIp = truncate(sourceIp, 45);
            this.userAgent = truncate(userAgent, MAX_USER_AGENT_LENGTH);
            return this;
        }

        public Builder correlatedBy(String traceId) {
            this.traceId = truncate(traceId, 64);
            return this;
        }

        public Builder changing(AuditableChange change) {
            this.change = change;
            return this;
        }

        public AuditRequest build() {
            if (action == null) {
                throw new IllegalStateException(
                    "An audit record must name its action; call did(...)");
            }
            return new AuditRequest(action, outcome, actorUserId, actorLabel,
                                    sourceIp, userAgent, targetType, targetId,
                                    traceId, change);
        }

        private static String truncate(String value, int limit) {
            if (value == null) {
                return null;
            }
            return value.length() <= limit ? value : value.substring(0, limit);
        }
    }
}
