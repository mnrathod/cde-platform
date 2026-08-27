package com.cde.platform.audit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What changed, as a bounded set of named values.
 *
 * <p>Its real job is refusal. §5.7 forbids passwords, tokens, session
 * identifiers, MFA secrets and raw personal data from reaching a log, and the
 * audit trail is the log most likely to be handed to an auditor, streamed to a
 * customer's SIEM, and retained for seven years — so a credential written here
 * by mistake is a credential in all three places.
 *
 * <p>Leaving that to callers' discipline fails eventually and silently. This
 * refuses at the point of construction instead: a key that looks like a secret
 * throws rather than being quietly dropped, because a silently dropped field
 * teaches nobody and the next caller writes it again.
 */
public final class AuditableChange {

    /**
     * Substrings that make a key a refusal. Matched case-insensitively against
     * the whole key, so {@code newPassword} and {@code password_hash} are both
     * caught.
     *
     * <p>A deny-list is the wrong shape for deciding what to <em>send</em>
     * somewhere (see {@code AiPayloadSanitiser}, which allow-lists). It is the
     * right shape here, because this is a guard against a mistake in code we
     * control rather than a filter over data an attacker chooses: an audit
     * record with fields the guard has never seen is normal, and one with a
     * field called "password" is a bug.
     */
    private static final Set<String> FORBIDDEN_KEY_FRAGMENTS = Set.of(
        "password", "passwd", "secret", "token", "credential", "apikey",
        "api_key", "privatekey", "private_key", "sessionid", "session_id",
        "cookie", "authorization", "totp", "mfa", "otp", "recoverycode",
        "recovery_code", "ssn", "nationalid", "national_id", "cardnumber",
        "card_number", "cvv", "iban");

    /**
     * Bounded so that one record cannot become a dumping ground for a request
     * body. Anything larger is not a change summary.
     */
    private static final int MAX_VALUE_LENGTH = 512;
    private static final int MAX_FIELDS = 32;

    private final Map<String, String> fields = new LinkedHashMap<>();

    public static AuditableChange none() {
        return new AuditableChange();
    }

    public static AuditableChange of(String field, Object value) {
        return new AuditableChange().and(field, value);
    }

    /**
     * @throws IllegalArgumentException when the field name looks like a secret,
     *         when there are too many fields, or when the value is too long
     */
    public AuditableChange and(String field, Object value) {
        String normalised = field.toLowerCase(Locale.ROOT).replace("-", "");
        FORBIDDEN_KEY_FRAGMENTS.stream()
            .filter(normalised::contains)
            .findFirst()
            .ifPresent(fragment -> {
                throw new IllegalArgumentException(
                    "Audit change field '" + field + "' contains '" + fragment
                    + "', which must never be written to the audit trail. "
                    + "Record an identifier or a boolean instead of the value.");
            });

        if (fields.size() >= MAX_FIELDS) {
            throw new IllegalArgumentException(
                "An audit change summary carries at most " + MAX_FIELDS
                + " fields; this is a summary, not a copy of the request.");
        }

        String rendered = value == null ? null : String.valueOf(value);
        if (rendered != null && rendered.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                "Audit change field '" + field + "' is " + rendered.length()
                + " characters; the limit is " + MAX_VALUE_LENGTH
                + ". Record a reference rather than the content.");
        }

        fields.put(field, rendered);
        return this;
    }

    /** Records that a value changed without recording either value. */
    public AuditableChange andRedacted(String field) {
        fields.put(field, "[redacted]");
        return this;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    Map<String, String> fields() {
        // Not Map.copyOf: it rejects null values, and a null here is
        // meaningful — "this field was cleared" is a change worth recording,
        // and coercing it to the string "null" would make it indistinguishable
        // from a field actually set to that text.
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
