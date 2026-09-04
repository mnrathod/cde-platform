-- ---------------------------------------------------------------------------
-- V7: conversion jobs (CLAUDE.md 7.1, ADR 12)
--
-- Converting a document is bulk work by 7.1's definition — its cost scales
-- with the file, and a 2 GB federated model does not convert inside a second.
-- So submission returns 202 with a job resource and the work happens on a
-- queue. This table is that resource: what was asked for, how far it got, and
-- what came of it.
--
-- ---------------------------------------------------------------------------
-- The source URL is deliberately NOT stored.
-- ---------------------------------------------------------------------------
-- ADR 12 has the integrator hand us a short-lived link — a Graph download URL,
-- an S3 presigned GET, an Azure SAS, a GCS signed URL. Every one of those
-- carries its own authorisation in the query string, which makes the URL a
-- bearer credential: whoever holds it has the access.
--
-- Persisting it would put that credential in the database, in every backup, in
-- every replica, and in front of anyone who can run a SELECT — the same
-- objection 5.7 makes to logging tokens. Encrypting the column would reduce
-- that rather than remove it, and would need a key, a rotation story and a
-- test suite of its own.
--
-- So the link lives only in the executor's memory for the life of the fetch.
-- What survives here is the host, for diagnostics and audit, and never the
-- query string. The cost is that a job still waiting when the application
-- restarts cannot be retried and is failed with that reason at startup — which
-- is barely a cost, because a presigned URL typically expires within fifteen
-- minutes and one that waited through a restart would very likely have expired
-- anyway. Resubmitting is a fresh link, which is the correct thing to do.
-- ---------------------------------------------------------------------------

CREATE TABLE conversion_jobs (
    id                        BIGSERIAL PRIMARY KEY,
    tenant_id                 BIGINT       NOT NULL,

    -- What the API exposes, rather than the primary key. A sequential id in a
    -- URL invites a caller to try id-1 and see what happens; RLS would refuse
    -- them, but the count of jobs in the system should not be readable from a
    -- URL bar either (5.13.13).
    public_id                 UUID         NOT NULL,

    submitted_by              BIGINT       NOT NULL,

    -- PENDING -> RUNNING -> SUCCEEDED | FAILED | CANCELLED. Enforced in the
    -- entity, which has no setter for this; the constraint is the backstop
    -- for anything reaching the table another way.
    status                    VARCHAR(16)  NOT NULL DEFAULT 'PENDING',

    -- The host only. Never the path, never the query string: the query string
    -- is where a presigned URL keeps its signature.
    source_host               VARCHAR(255) NOT NULL,

    -- What the far end called the file, sanitised for display. Never used to
    -- decide where anything is written: storage keys are server-generated
    -- (5.13.6, 11), so this cannot influence a path.
    source_file_name          VARCHAR(255) NOT NULL DEFAULT '',

    target_format             VARCHAR(16)  NOT NULL,

    -- The server-generated object id of the result, once there is one. Not a
    -- path: the storage key is rebuilt from tenant and category, so a row
    -- cannot name an object outside its own tenant's prefix.
    result_object_id          VARCHAR(160),
    result_size_bytes         BIGINT,

    -- Written for whoever submitted the job: what happened and what to do
    -- about it (1.4). Never a stack trace, never an internal address, never
    -- the resolved address of a refused host.
    failure_reason            TEXT,

    progress_percent          SMALLINT     NOT NULL DEFAULT 0,

    -- Requested, not applied. Cancellation is co-operative — the executor
    -- notices between chunks — so the request and the outcome are different
    -- facts and a single status column cannot hold both.
    cancellation_requested_at TIMESTAMPTZ,

    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at                TIMESTAMPTZ,
    finished_at               TIMESTAMPTZ,

    CONSTRAINT conversion_jobs_public_id_unique UNIQUE (tenant_id, public_id),
    CONSTRAINT conversion_jobs_submitted_by_fk
        FOREIGN KEY (submitted_by) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT conversion_jobs_status_valid
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT conversion_jobs_progress_valid
        CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT conversion_jobs_target_format_valid
        CHECK (target_format IN ('PDF')),

    -- A terminal job has an outcome. Without this a row can claim to have
    -- succeeded with nothing to show, or to have failed with no reason, and
    -- the API would then have to invent one.
    CONSTRAINT conversion_jobs_succeeded_has_result
        CHECK (status <> 'SUCCEEDED' OR result_object_id IS NOT NULL),
    CONSTRAINT conversion_jobs_failed_has_reason
        CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL)
);

-- tenant_id leads, per 7.5: RLS puts it in every query's predicate, so an
-- index that does not start with it cannot serve the query the application
-- actually issues.
CREATE UNIQUE INDEX idx_conversion_jobs_tenant_public
    ON conversion_jobs (tenant_id, public_id);

-- The listing query: one tenant's jobs, newest first, optionally by status.
CREATE INDEX idx_conversion_jobs_tenant_status_created
    ON conversion_jobs (tenant_id, status, created_at DESC);

-- Postgres does not index a referencing column automatically, so ON DELETE
-- CASCADE from users would sequential-scan this table on every account
-- deletion.
CREATE INDEX idx_conversion_jobs_submitted_by ON conversion_jobs (submitted_by);

-- Startup recovery reads exactly this: jobs left mid-flight by a restart. A
-- partial index because the rows it wants are a vanishing fraction of the
-- table once the system has been running.
CREATE INDEX idx_conversion_jobs_unfinished
    ON conversion_jobs (status)
    WHERE status IN ('PENDING', 'RUNNING');

-- ---------------------------------------------------------------------------
-- Tenant isolation (5.6): enabled AND forced, as on every other tenant table.
-- FORCE matters because it applies the policy to the table owner too; without
-- it, a role that happens to own the table reads every tenant's rows.
-- ---------------------------------------------------------------------------
ALTER TABLE conversion_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversion_jobs FORCE  ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON conversion_jobs
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT);

GRANT SELECT, INSERT, UPDATE, DELETE ON conversion_jobs TO cde_app;
GRANT USAGE, SELECT ON SEQUENCE conversion_jobs_id_seq TO cde_app;

COMMENT ON TABLE conversion_jobs IS
    'Asynchronous document conversions. The source URL is never stored: it is a presigned bearer credential, and only its host is retained.';
COMMENT ON COLUMN conversion_jobs.source_host IS
    'Host only, for diagnostics and audit. Never the path or query string, which is where a presigned URL keeps its signature.';
COMMENT ON COLUMN conversion_jobs.public_id IS
    'The identifier the API exposes, so job counts are not readable from a URL.';
COMMENT ON COLUMN conversion_jobs.cancellation_requested_at IS
    'Cancellation is co-operative: this records the request, status records the outcome.';
COMMENT ON COLUMN conversion_jobs.failure_reason IS
    'Written for the submitter. Never a stack trace, an internal address, or a resolved address.';
