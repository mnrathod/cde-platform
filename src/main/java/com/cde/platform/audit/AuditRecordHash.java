package com.cde.platform.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * The hash that chains one audit record to the one before it.
 *
 * <p>SHA-256, per the platform hashing standard. This is an integrity hash over
 * data the application itself wrote, not a password, so it is used directly —
 * the iteration count that {@code Pbkdf2Sha256PasswordEncoder} needs exists to
 * make guessing a secret expensive, and there is no secret here to guess.
 *
 * <p>The encoding is length-prefixed rather than delimiter-separated. With a
 * delimiter, two different records can hash identically by moving the delimiter
 * inside a field: an actor called {@code "alice|DENIED"} and an actor called
 * {@code "alice"} with outcome {@code DENIED} produce the same joined string,
 * so one could be rewritten as the other without breaking the chain. Prefixing
 * each field with its length makes the encoding unambiguous, and the fields
 * here are attacker-influenced (usernames, user agents, target identifiers),
 * so that is not a theoretical concern.
 */
public final class AuditRecordHash {

    /**
     * What the first record in a tenant's chain points at. Using the hash of
     * the empty string rather than a sentinel means verification has no special
     * case for the first record.
     */
    public static final String GENESIS_HASH = sha256Hex("");

    /**
     * Fixed offset and format so that a record hashed on one machine verifies
     * on another. A local-time or default-zone rendering would make the chain
     * depend on where it was computed.
     */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'");

    private AuditRecordHash() {
    }

    /**
     * @param previousHash the {@code recordHash} of the preceding record in
     *                     this tenant's chain, or {@link #GENESIS_HASH}
     * @return hex SHA-256 over the previous hash and this record's contents
     */
    public static String of(String previousHash,
                            Long tenantId,
                            long sequenceNumber,
                            OffsetDateTime occurredAt,
                            AuditAction action,
                            AuditOutcome outcome,
                            Long actorUserId,
                            String actorLabel,
                            String sourceIp,
                            String userAgent,
                            String targetType,
                            String targetId,
                            String traceId,
                            String changeSummary) {
        var canonical = new StringBuilder();
        appendField(canonical, previousHash);
        appendField(canonical, String.valueOf(tenantId));
        appendField(canonical, String.valueOf(sequenceNumber));
        appendField(canonical, occurredAt.withOffsetSameInstant(ZoneOffset.UTC)
                                         .format(TIMESTAMP_FORMAT));
        appendField(canonical, action.name());
        appendField(canonical, outcome.name());
        appendField(canonical, actorUserId == null ? "" : String.valueOf(actorUserId));
        appendField(canonical, actorLabel);
        appendField(canonical, sourceIp);
        appendField(canonical, userAgent);
        appendField(canonical, targetType);
        appendField(canonical, targetId);
        appendField(canonical, traceId);
        appendField(canonical, changeSummary);
        return sha256Hex(canonical.toString());
    }

    /**
     * A null field and an empty one must not encode identically either, so the
     * absent case gets its own length marker rather than being coerced to "".
     */
    private static void appendField(StringBuilder canonical, String value) {
        if (value == null) {
            canonical.append("-:");
            return;
        }
        canonical.append(value.length()).append(':').append(value).append(';');
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JVM, so this
            // is not a condition to recover from.
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }
}
