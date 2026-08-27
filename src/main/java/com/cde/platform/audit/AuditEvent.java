package com.cde.platform.audit;

import com.cde.platform.tenancy.TenantAssigningListener;
import com.cde.platform.tenancy.TenantScoped;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One record in a tenant's audit trail.
 *
 * <p>Deliberately without setters and without a builder that can produce a
 * half-formed record: every field is supplied at construction, and the two
 * hashes are computed from the rest rather than passed in. A record whose
 * {@code recordHash} did not follow from its contents would satisfy the
 * database and prove nothing.
 *
 * <p>There is no update path. The application role holds {@code INSERT} and
 * {@code SELECT} only (see {@code V5__audit_trail.sql}), so an attempt to
 * modify one fails at the database whatever the code does — which is the point:
 * the immutability is a grant, not a convention.
 */
@Entity
@Table(name = "audit_events")
@EntityListeners(TenantAssigningListener.class)
public class AuditEvent implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "sequence_number", nullable = false)
    private Long sequenceNumber;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditOutcome outcome;

    /** Null for an unauthenticated attempt, which is an event worth keeping. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_label", nullable = false, length = 255)
    private String actorLabel;

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id", length = 64)
    private String targetId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** Bounded JSON. Never a body, a credential, or raw personal data. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_summary", columnDefinition = "jsonb")
    private String changeSummary;

    @Column(name = "previous_hash", nullable = false, length = 64)
    private String previousHash;

    @Column(name = "record_hash", nullable = false, length = 64)
    private String recordHash;

    protected AuditEvent() {
        // Hibernate.
    }

    AuditEvent(Long tenantId, long sequenceNumber, OffsetDateTime occurredAt,
               AuditAction action, AuditOutcome outcome,
               Long actorUserId, String actorLabel,
               String sourceIp, String userAgent,
               String targetType, String targetId, String traceId,
               String changeSummary, String previousHash, String recordHash) {
        this.tenantId = tenantId;
        this.sequenceNumber = sequenceNumber;
        this.occurredAt = occurredAt;
        this.action = action;
        this.outcome = outcome;
        this.actorUserId = actorUserId;
        this.actorLabel = actorLabel;
        this.sourceIp = sourceIp;
        this.userAgent = userAgent;
        this.targetType = targetType;
        this.targetId = targetId;
        this.traceId = traceId;
        this.changeSummary = changeSummary;
        this.previousHash = previousHash;
        this.recordHash = recordHash;
    }

    public Long getId() {
        return id;
    }

    @Override
    public Long getTenantId() {
        return tenantId;
    }

    @Override
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getChangeSummary() {
        return changeSummary;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public String getRecordHash() {
        return recordHash;
    }
}
