-- The immutable audit trail.
--
-- There was none. Every security-relevant thing the platform did — who signed
-- in, who was refused, who was invited, who moved a container from Shared to
-- Published, who exported what — happened and left no record. That is not a
-- gap in reporting: without it there is no way to answer "what did this
-- account do" after an incident, and nothing to hand an auditor.
--
-- Two properties make an audit trail worth having, and both are structural
-- rather than procedural:
--
--   1. It is append-only. The application role is granted INSERT and SELECT
--      and nothing else, so a compromised application cannot rewrite history
--      even with full database credentials. Redaction, retention and export
--      are operations for a different role.
--
--   2. It is tamper-evident. Each record carries the SHA-256 of the record
--      before it, so altering or removing one breaks every hash after it.
--      Detecting tampering is the achievable property; preventing it needs
--      storage the database cannot reach (WORM export, §5.7), which this
--      table is shaped to feed.

CREATE TABLE audit_events (
    id                BIGSERIAL     PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL,

    -- Position within this tenant's chain. Contiguous from 1: a gap is itself
    -- evidence, which is why the chain is per tenant rather than global —
    -- Row-Level Security means the application can only ever read its own
    -- tenant's rows, so a global chain would be one the application could
    -- never verify.
    sequence_number   BIGINT        NOT NULL,

    occurred_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- What happened, from the closed vocabulary in AuditAction. A free-text
    -- action column becomes unqueryable within a year and unmappable to a
    -- control matrix ever.
    --
    -- Deliberately not a CHECK constraint listing every value: adding an
    -- audited action would then need a migration, and the predictable result
    -- is that someone adds the action without the audit. The vocabulary is
    -- closed in Java, where it is also what the recorder's callers must name.
    action            VARCHAR(64)   NOT NULL,
    outcome           VARCHAR(16)   NOT NULL,

    -- Who did it. actor_user_id is null for an unauthenticated attempt — a
    -- failed sign-in is exactly the event worth keeping, and it has no user.
    -- actor_label carries what was known instead (the attempted username),
    -- which is why it is bounded and never a free-form body.
    actor_user_id     BIGINT,
    actor_label       VARCHAR(255)  NOT NULL,

    -- Where from. Both are attacker-supplied in part, so both are bounded and
    -- neither is ever interpolated anywhere.
    source_ip         VARCHAR(45),
    user_agent        VARCHAR(512),

    -- What it was done to.
    target_type       VARCHAR(64),
    target_id         VARCHAR(64),

    -- Ties the record to the request that produced it and to everything else
    -- that request logged.
    trace_id          VARCHAR(64),

    -- What changed, as a bounded JSON object of field names to before/after
    -- values. Never a request body, never a file's contents, never a
    -- credential: the application's recorder rejects those keys outright
    -- rather than trusting callers to omit them.
    change_summary    JSONB,

    -- SHA-256, hex. previous_hash is the empty-string hash for the first
    -- record in a tenant's chain, so verification has no special case.
    previous_hash     VARCHAR(64)   NOT NULL,
    record_hash       VARCHAR(64)   NOT NULL,

    CONSTRAINT fk_audit_events_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),

    -- No foreign key to users. An audit record outlives the account it
    -- describes: a cascade would delete the evidence of what a deleted
    -- account did, and a restrict would make the account undeletable. The id
    -- is recorded, and actor_label preserves the readable identity.

    CONSTRAINT uk_audit_events_sequence UNIQUE (tenant_id, sequence_number),

    CONSTRAINT audit_events_outcome_check
        CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),

    CONSTRAINT audit_events_previous_hash_format_check
        CHECK (previous_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT audit_events_record_hash_format_check
        CHECK (record_hash ~ '^[0-9a-f]{64}$'),

    CONSTRAINT audit_events_sequence_positive_check
        CHECK (sequence_number > 0)
);

-- The query a tenant administrator actually runs: this organisation's events,
-- newest first. tenant_id leads, per the indexing rule.
CREATE INDEX idx_audit_events_tenant_time
    ON audit_events (tenant_id, occurred_at DESC);

-- Filtering by what happened, and by what it happened to.
CREATE INDEX idx_audit_events_tenant_action
    ON audit_events (tenant_id, action, occurred_at DESC);
CREATE INDEX idx_audit_events_target
    ON audit_events (tenant_id, target_type, target_id);

-- "Everything this account did", which is the first question asked after a
-- credential compromise. Partial, because most rows have an actor and the
-- null ones are never searched this way.
CREATE INDEX idx_audit_events_actor
    ON audit_events (tenant_id, actor_user_id, occurred_at DESC)
    WHERE actor_user_id IS NOT NULL;

ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON audit_events
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT);

-- ---------------------------------------------------------------------------
-- Append-only, enforced by the grant
--
-- SELECT and INSERT only. This is the difference between an audit trail and a
-- log table: with UPDATE and DELETE granted, anything able to run application
-- SQL can rewrite the record of what it did, and the hash chain then only
-- proves that whoever rewrote it also recomputed the hashes.
--
-- Retention and WORM export run as a separate role, out of the application's
-- reach, so deleting expired records cannot be reached from a request.
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT ON audit_events TO cde_app;
GRANT USAGE, SELECT ON SEQUENCE audit_events_id_seq TO cde_app;

REVOKE UPDATE, DELETE ON audit_events FROM cde_app;

COMMENT ON TABLE audit_events IS
    'Append-only, hash-chained record of security-relevant events, per tenant. The application role holds SELECT and INSERT only.';
COMMENT ON COLUMN audit_events.previous_hash IS
    'SHA-256 of the preceding record in this tenant''s chain; the SHA-256 of the empty string for the first.';
COMMENT ON COLUMN audit_events.change_summary IS
    'Bounded JSON of what changed. Never a request body, file contents, credential, or raw personal data.';
