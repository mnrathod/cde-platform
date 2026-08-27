package com.cde.platform.audit;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * What the audit trail looks like on the wire.
 *
 * <p>The two hashes are exposed deliberately. A tenant streaming its trail to
 * its own SIEM can then verify the chain independently — the guarantee is only
 * worth something if the party relying on it can check it, rather than being
 * told the platform checked it.
 */
public final class AuditDtos {

    private AuditDtos() {
    }

    @Schema(name = "AuditEventResponse",
        description = "One record in this organisation's audit trail. Records are append-only "
                    + "and hash-chained: each carries the SHA-256 of the record before it, so "
                    + "an alteration anywhere breaks every hash after it.")
    public record AuditEventResponse(

        @Schema(description = "Position in this organisation's chain, contiguous from 1. "
                            + "A gap means a record is missing.",
                example = "142", requiredMode = Schema.RequiredMode.REQUIRED)
        long sequenceNumber,

        @Schema(description = "When it happened, UTC.",
                example = "2026-08-27T09:15:04.123456789Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        OffsetDateTime occurredAt,

        @Schema(description = "What happened.", example = "CONTAINER_TRANSITIONED",
                requiredMode = Schema.RequiredMode.REQUIRED)
        AuditAction action,

        @Schema(description = "Whether it took effect. DENIED means authenticated but not "
                            + "permitted, which is the signal worth alerting on.",
                example = "SUCCESS", requiredMode = Schema.RequiredMode.REQUIRED)
        AuditOutcome outcome,

        @Schema(description = "The account that did it, or null for an unauthenticated attempt.",
                example = "41", nullable = true)
        Long actorUserId,

        @Schema(description = "The readable identity, kept so the record still means something "
                            + "after the account is deleted. For an unauthenticated attempt it "
                            + "is what the caller claimed to be.",
                example = "j.okafor", requiredMode = Schema.RequiredMode.REQUIRED)
        String actorLabel,

        @Schema(description = "The address this deployment saw the request arrive from. Behind "
                            + "a reverse proxy that is the proxy unless the web tier is "
                            + "configured to forward the original.",
                example = "203.0.113.17", nullable = true)
        String sourceIp,

        @Schema(description = "The client's self-reported user agent, truncated to 512 "
                            + "characters.",
                example = "Mozilla/5.0", nullable = true)
        String userAgent,

        @Schema(description = "The kind of thing acted on.", example = "InformationContainer",
                nullable = true)
        String targetType,

        @Schema(description = "Which one.", example = "8814", nullable = true)
        String targetId,

        @Schema(description = "Correlates this record with the request that produced it and "
                            + "every log line for that request.",
                example = "4f2c1ab99e0d4e7a8f3b6c1d2e5a7b90", nullable = true)
        String traceId,

        @Schema(description = "What changed, as a JSON object of field names to values. Never "
                            + "carries a credential, a request body, or raw personal data.",
                example = "{\"state\":\"PUBLISHED\",\"previousState\":\"SHARED\"}",
                nullable = true)
        String changeSummary,

        @Schema(description = "SHA-256 of the preceding record in this chain, hex.",
                example = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String previousHash,

        @Schema(description = "SHA-256 of this record's contents together with previousHash, hex.",
                example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String recordHash
    ) {

        public static AuditEventResponse from(AuditEvent event) {
            return new AuditEventResponse(
                event.getSequenceNumber(), event.getOccurredAt(),
                event.getAction(), event.getOutcome(),
                event.getActorUserId(), event.getActorLabel(),
                event.getSourceIp(), event.getUserAgent(),
                event.getTargetType(), event.getTargetId(), event.getTraceId(),
                event.getChangeSummary(), event.getPreviousHash(), event.getRecordHash());
        }
    }

    @Schema(name = "AuditChainVerificationResponse",
        description = "The result of recomputing every hash in this organisation's chain.")
    public record AuditChainVerificationResponse(

        @Schema(description = "True when every record follows from the one before it.",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean intact,

        @Schema(description = "How many records were read.", example = "1420",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int recordsChecked,

        @Schema(description = "The sequence number where verification stopped, or null when "
                            + "the chain is intact.",
                example = "97", nullable = true)
        Long firstBrokenAt,

        @Schema(description = "What was wrong, in words, or null when the chain is intact.",
                example = "the record's contents do not match its hash", nullable = true)
        String reason
    ) {

        public static AuditChainVerificationResponse from(
                AuditTrailService.ChainVerification verification) {
            return new AuditChainVerificationResponse(
                verification.intact(), verification.recordsChecked(),
                verification.firstBrokenAt(), verification.reason());
        }
    }
}
