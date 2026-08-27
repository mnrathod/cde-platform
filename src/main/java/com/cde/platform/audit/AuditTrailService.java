package com.cde.platform.audit;

import com.cde.platform.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Appends to, and verifies, a tenant's audit trail.
 *
 * <p>Records are written <em>in the caller's transaction</em>, not after it and
 * not on another thread. §5.7 requires that, and the reason is specific: an
 * audit write that commits separately can succeed for a change that then rolls
 * back — producing a record of something that never happened — or fail for one
 * that commits, losing the record of something that did. Sharing the
 * transaction makes the record and the change atomic in both directions.
 *
 * <p>The cost is that a failure here fails the business operation. That is the
 * correct trade for a control whose whole value is completeness: an audit trail
 * with silent holes in it is worse than none, because it is believed.
 */
@Service
public class AuditTrailService {

    private static final Logger log = LoggerFactory.getLogger(AuditTrailService.class);

    /**
     * The high half of the advisory lock key, distinguishing this lock from any
     * other advisory lock the application might take. Arbitrary, but fixed —
     * changing it would let two deployments of different versions append
     * concurrently.
     */
    private static final long AUDIT_LOCK_NAMESPACE = 19_650L;

    /**
     * Composes the 64-bit advisory lock key: namespace in the high 32 bits,
     * tenant in the low 32.
     *
     * <p>Done here rather than by calling PostgreSQL's two-argument
     * {@code pg_advisory_xact_lock(int, int)}, which takes {@code int4} and so
     * cannot be handed a {@code BIGINT} tenant id without a cast that silently
     * truncates.
     *
     * <p>Past 2^32 tenants two of them would share a lock key. That costs a
     * little throughput — their audit appends would serialise with each other —
     * and breaks nothing, which is the right way for this to degrade.
     */
    private static long lockKeyFor(long tenantId) {
        return (AUDIT_LOCK_NAMESPACE << 32) | (tenantId & 0xFFFF_FFFFL);
    }

    private final AuditEventRepository events;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public AuditTrailService(AuditEventRepository events,
                             EntityManager entityManager,
                             ObjectMapper objectMapper) {
        this.events = events;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    /**
     * Appends one record to the current tenant's chain.
     *
     * @throws IllegalStateException when no tenant context is bound. A
     *         security-relevant event with nowhere to belong is a programming
     *         error, not something to write to a default tenant.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEvent record(AuditRequest request) {
        long tenantId = TenantContext.requireTenantId();

        // Serialise appends within this tenant for the rest of the
        // transaction. Without it two concurrent requests read the same
        // "latest" record and chain from it, forking the chain — and the
        // unique constraint on (tenant_id, sequence_number) would then fail
        // one of them, rolling back a business operation for a reason that has
        // nothing to do with it. The lock releases at commit; it is per tenant,
        // so one busy organisation does not serialise another.
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
            .setParameter("key", lockKeyFor(tenantId))
            // COMMIT, not the default AUTO. A query normally makes Hibernate
            // flush everything pending first, so acquiring this lock would
            // execute the caller's not-yet-flushed writes — and any constraint
            // they violate would then surface from this native query rather
            // than from the repository call that made them, arriving as a raw
            // HibernateException instead of the translated DataAccessException
            // the caller's error handling expects. Taking a lock is not a data
            // read and has no need to see pending changes.
            .setFlushMode(jakarta.persistence.FlushModeType.COMMIT)
            .getSingleResult();

        String previousHash = events.findFirstByOrderBySequenceNumberDesc()
            .map(AuditEvent::getRecordHash)
            .orElse(AuditRecordHash.GENESIS_HASH);
        long sequenceNumber = events.highestSequenceNumberFor(tenantId) + 1;

        // Truncated to microseconds because that is what PostgreSQL's
        // TIMESTAMPTZ stores. Hashing a nanosecond-precision value and then
        // storing a microsecond-precision one means the record read back never
        // reproduces its own hash — every chain would verify as broken, and the
        // verification would be reporting a rounding difference as tampering.
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MICROS);
        String changeSummary = renderChangeSummary(request.change());

        // Hashed in its canonical form, because the jsonb column stores a
        // parsed value and returns a differently-rendered string. Hashing what
        // was written rather than what will be read back makes every record
        // fail its own verification.
        String recordHash = AuditRecordHash.of(
            previousHash, tenantId, sequenceNumber, occurredAt,
            request.action(), request.outcome(),
            request.actorUserId(), request.actorLabel(),
            request.sourceIp(), request.userAgent(),
            request.targetType(), request.targetId(), request.traceId(),
            CanonicalJson.canonicalise(objectMapper, changeSummary));

        return events.save(new AuditEvent(
            tenantId, sequenceNumber, occurredAt,
            request.action(), request.outcome(),
            request.actorUserId(), request.actorLabel(),
            request.sourceIp(), request.userAgent(),
            request.targetType(), request.targetId(), request.traceId(),
            changeSummary, previousHash, recordHash));
    }

    /**
     * Recomputes every hash in the current tenant's chain.
     *
     * <p>Reads the whole chain, so it is an operator and scheduled-job
     * operation rather than something a request calls.
     *
     * @return the outcome, naming the first record that does not verify
     */
    @Transactional(readOnly = true)
    public ChainVerification verifyChain() {
        List<AuditEvent> chain = events.findAllByOrderBySequenceNumberAsc();

        String expectedPreviousHash = AuditRecordHash.GENESIS_HASH;
        long expectedSequence = 1;

        for (AuditEvent event : chain) {
            if (event.getSequenceNumber() != expectedSequence) {
                return ChainVerification.broken(expectedSequence,
                    "a record is missing: expected sequence " + expectedSequence
                    + " but found " + event.getSequenceNumber());
            }
            if (!expectedPreviousHash.equals(event.getPreviousHash())) {
                return ChainVerification.broken(event.getSequenceNumber(),
                    "the record does not follow the one before it");
            }

            String recomputed = AuditRecordHash.of(
                event.getPreviousHash(), event.getTenantId(), event.getSequenceNumber(),
                event.getOccurredAt(), event.getAction(), event.getOutcome(),
                event.getActorUserId(), event.getActorLabel(),
                event.getSourceIp(), event.getUserAgent(),
                event.getTargetType(), event.getTargetId(), event.getTraceId(),
                CanonicalJson.canonicalise(objectMapper, event.getChangeSummary()));

            if (!recomputed.equals(event.getRecordHash())) {
                return ChainVerification.broken(event.getSequenceNumber(),
                    "the record's contents do not match its hash");
            }

            expectedPreviousHash = event.getRecordHash();
            expectedSequence++;
        }

        return ChainVerification.intact(chain.size());
    }

    private String renderChangeSummary(AuditableChange change) {
        if (change == null || change.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(change.fields());
        } catch (JsonProcessingException e) {
            // Recorded as an explicit marker rather than dropped: a record
            // whose summary silently vanished looks identical to one that
            // never had a change to describe.
            log.warn("An audit change summary could not be serialised for action {}",
                     change.getClass().getSimpleName(), e);
            return "{\"error\":\"the change summary could not be serialised\"}";
        }
    }

    /**
     * @param intact          whether every record verified
     * @param recordsChecked  how many records were read
     * @param firstBrokenAt   the sequence number where verification stopped
     * @param reason          what was wrong, in words
     */
    public record ChainVerification(boolean intact, int recordsChecked,
                                    Long firstBrokenAt, String reason) {

        static ChainVerification intact(int recordsChecked) {
            return new ChainVerification(true, recordsChecked, null, null);
        }

        static ChainVerification broken(long firstBrokenAt, String reason) {
            return new ChainVerification(false, 0, firstBrokenAt, reason);
        }
    }
}
