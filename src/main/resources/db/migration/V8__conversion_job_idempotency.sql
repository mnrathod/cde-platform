-- ---------------------------------------------------------------------------
-- V8: idempotency keys for conversion submission (CLAUDE.md 3.4)
--
-- §3.4 requires an idempotency key on every POST that creates a resource, and
-- this one creates a job that costs a fetch, a scan and a conversion. Without
-- it, a client that times out and retries — which is exactly what a client
-- should do against a 202 endpoint — pays for the same conversion twice and
-- gets two job ids for one intent.
--
-- Separate from V7 rather than folded into it because V7 has shipped.
-- Migrations are forward-only (§7.5); editing one that has run is how a
-- checksum mismatch stops every environment that already applied it.
--
-- Additive and nullable, so this is a zero-downtime expand step: the running
-- version, which does not know the column, keeps inserting successfully.
-- ---------------------------------------------------------------------------

ALTER TABLE conversion_jobs
    ADD COLUMN idempotency_key VARCHAR(255);

-- Unique per tenant, and only where one was supplied. A partial index because
-- the key is optional: without the WHERE clause, every job submitted without a
-- key would collide with every other on NULL in some databases, and here would
-- simply bloat the index with rows it can never match.
--
-- tenant_id leads, as everywhere else: RLS puts it in the predicate of every
-- query the application issues (§7.5).
CREATE UNIQUE INDEX idx_conversion_jobs_idempotency
    ON conversion_jobs (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN conversion_jobs.idempotency_key IS
    'Client-supplied key making submission safe to retry. Unique per tenant where present.';
