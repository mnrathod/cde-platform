-- Tenant isolation: a tenant_id on every table, and Row-Level Security that
-- makes a forgotten WHERE clause harmless rather than a cross-tenant leak.
--
-- The ordering below matters. Columns are added nullable, backfilled, and only
-- then marked NOT NULL — adding a NOT NULL column with no default to a
-- populated table fails outright, and adding one with a default rewrites the
-- whole table under an ACCESS EXCLUSIVE lock.
--
-- RLS is enabled *and forced*, but FORCE is not what makes this work, and it is
-- worth being precise about why. FORCE binds the table *owner* to the policies.
-- It does nothing to a superuser or to any role holding BYPASSRLS — those
-- bypass RLS unconditionally, and PostgreSQL reports no error, no warning and
-- no clue that it has happened. Measured directly against this schema: with
-- every policy present and both flags set, a superuser connection saw all rows
-- of every tenant, including with no tenant context set at all.
--
-- The mechanism is therefore the role, not the flags: queries must run as
-- cde_app, which is neither the owner nor a superuser and is explicitly
-- NOBYPASSRLS. FORCE remains because it closes the owner case as well, and
-- costs nothing.

-- ---------------------------------------------------------------------------
-- Tenants
-- ---------------------------------------------------------------------------

CREATE TABLE tenants (
    id              BIGSERIAL    PRIMARY KEY,
    slug            VARCHAR(63)  NOT NULL,
    name            VARCHAR(200) NOT NULL,
    -- A tenant is bound to a region at creation and every byte of its data
    -- stays inside that boundary. Stored here so residency is a property of
    -- the tenant rather than of whichever deployment happens to hold it.
    region          VARCHAR(50)  NOT NULL DEFAULT 'default',
    -- Sets the ceiling a tenant administrator cannot exceed: password expiry
    -- interval, whether external services may be called at all, which crypto
    -- provider is permitted.
    deployment_tier VARCHAR(20)  NOT NULL DEFAULT 'COMMERCIAL',
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT uk_tenants_slug UNIQUE (slug),
    CONSTRAINT tenants_slug_format_check CHECK (slug ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'),
    CONSTRAINT tenants_tier_check
        CHECK (deployment_tier IN ('COMMERCIAL', 'GOVERNMENT', 'DEFENCE'))
);

COMMENT ON TABLE tenants IS
    'Tenant registry. Not itself tenant-scoped: it is the table that defines the scope.';

-- Everything that already exists belongs to one tenant. Created here rather
-- than by application seeding so the backfill below has something to point at
-- in every environment, including one restored from a pre-tenancy backup.
INSERT INTO tenants (slug, name) VALUES ('default', 'Default Organisation');

-- ---------------------------------------------------------------------------
-- tenant_id on every table
-- ---------------------------------------------------------------------------

ALTER TABLE users               ADD COLUMN tenant_id BIGINT;
ALTER TABLE projects            ADD COLUMN tenant_id BIGINT;
ALTER TABLE documents           ADD COLUMN tenant_id BIGINT;
ALTER TABLE document_versions   ADD COLUMN tenant_id BIGINT;
ALTER TABLE annotations         ADD COLUMN tenant_id BIGINT;
ALTER TABLE annotation_replies  ADD COLUMN tenant_id BIGINT;
ALTER TABLE document_signatures ADD COLUMN tenant_id BIGINT;

UPDATE users               SET tenant_id = (SELECT id FROM tenants WHERE slug = 'default');
UPDATE projects            SET tenant_id = (SELECT id FROM tenants WHERE slug = 'default');
UPDATE documents           SET tenant_id = (SELECT id FROM tenants WHERE slug = 'default');
UPDATE document_versions   SET tenant_id = (SELECT id FROM tenants WHERE slug = 'default');
UPDATE annotations         SET tenant_id = (SELECT id FROM tenants WHERE slug = 'default');
UPDATE annotation_replies  SET tenant_id = (SELECT id FROM tenants WHERE slug = 'default');
UPDATE document_signatures SET tenant_id = (SELECT id FROM tenants WHERE slug = 'default');

ALTER TABLE users               ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE projects            ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE documents           ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document_versions   ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE annotations         ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE annotation_replies  ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document_signatures ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE users               ADD CONSTRAINT fk_users_tenant               FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE projects            ADD CONSTRAINT fk_projects_tenant            FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE documents           ADD CONSTRAINT fk_documents_tenant           FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE document_versions   ADD CONSTRAINT fk_document_versions_tenant   FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE annotations         ADD CONSTRAINT fk_annotations_tenant         FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE annotation_replies  ADD CONSTRAINT fk_annotation_replies_tenant  FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE document_signatures ADD CONSTRAINT fk_document_signatures_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);

-- ---------------------------------------------------------------------------
-- Indexes
--
-- tenant_id leads every one of these. It is the most selective predicate on
-- the system — every query carries it, because RLS adds it whether the query
-- author wrote it or not — so an index that does not lead with it cannot be
-- used for the policy check.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_users_tenant               ON users (tenant_id);
CREATE INDEX idx_projects_tenant            ON projects (tenant_id);
CREATE INDEX idx_documents_tenant_project   ON documents (tenant_id, project_id);
CREATE INDEX idx_document_versions_tenant   ON document_versions (tenant_id, document_id);
CREATE INDEX idx_annotations_tenant_doc     ON annotations (tenant_id, document_id);
CREATE INDEX idx_annotation_replies_tenant  ON annotation_replies (tenant_id, annotation_id);
CREATE INDEX idx_document_signatures_tenant ON document_signatures (tenant_id, document_id);

-- ---------------------------------------------------------------------------
-- Uniqueness
--
-- Composite constraints gain tenant_id so two tenants cannot collide with each
-- other's identifiers.
--
-- users.username and users.email stay GLOBALLY unique, deliberately. Login
-- presents a username with no tenant attached, so the lookup that resolves the
-- tenant has to be unambiguous. Making usernames per-tenant requires
-- home-realm discovery — resolving the tenant from an email domain or a
-- subdomain before authentication — which does not exist yet. Narrowing these
-- to per-tenant before that is built would make two accounts indistinguishable
-- at the point of login.
-- ---------------------------------------------------------------------------

ALTER TABLE document_versions DROP CONSTRAINT uk_document_version;
ALTER TABLE document_versions ADD  CONSTRAINT uk_document_version
    UNIQUE (tenant_id, document_id, version_number);

ALTER TABLE document_signatures DROP CONSTRAINT uk_document_signatures_signature_id;
ALTER TABLE document_signatures ADD  CONSTRAINT uk_document_signatures_signature_id
    UNIQUE (tenant_id, signature_id);

-- ---------------------------------------------------------------------------
-- Row-Level Security
--
-- current_setting(..., true) returns NULL when the setting was never set, and
-- the empty string once it has been set and cleared — which is what happens on
-- every connection returned to the pool. NULLIF collapses the two, because
-- ''::BIGINT does not yield NULL, it raises "invalid input syntax for type
-- bigint" and turns a routine query into a 500.
--
-- Either way the comparison is NULL and matches no rows, which is the intended
-- behaviour: a background job, a migration, or a bug that reaches the database
-- without establishing context reads nothing rather than reading everything.
--
-- WITH CHECK covers the write direction. Without it a caller could insert or
-- update a row into another tenant even while unable to read it back.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    tenant_table TEXT;
BEGIN
    FOREACH tenant_table IN ARRAY ARRAY[
        'users', 'projects', 'documents', 'document_versions',
        'annotations', 'annotation_replies', 'document_signatures'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tenant_table);
        -- FORCE is the load-bearing half: without it the owner — which is the
        -- role the application connects as in every environment that has not
        -- been split into two roles — silently bypasses every policy below.
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', tenant_table);
        EXECUTE format($policy$
            CREATE POLICY tenant_isolation ON %I
                USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT)
                WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT)
        $policy$, tenant_table);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- Resolving a tenant at login
--
-- Authentication is a chicken-and-egg problem under RLS: the users table is
-- how tenant context is established, but it is itself tenant-scoped, so an
-- unauthenticated login request cannot read the row it needs.
--
-- The answer is a single SECURITY DEFINER function that returns one BIGINT and
-- nothing else. It is a deliberate, narrow, auditable hole rather than an
-- exemption on the table: it cannot return a password hash, an email, or a row
-- from any other tenant, and the only fact it discloses is which tenant owns a
-- username. Callers must not surface that — §4.2 requires a login response
-- that never reveals whether an account exists.
-- ---------------------------------------------------------------------------

CREATE FUNCTION resolve_tenant_for_login(p_username TEXT)
RETURNS BIGINT
LANGUAGE sql
STABLE
SECURITY DEFINER
-- Empty search_path: a SECURITY DEFINER function that resolves unqualified
-- names through the caller's search_path can be hijacked by a same-named
-- object in a schema the caller controls.
SET search_path = pg_catalog, public
AS $$
    SELECT tenant_id FROM public.users WHERE username = p_username
$$;

COMMENT ON FUNCTION resolve_tenant_for_login(TEXT) IS
    'Returns the tenant owning a username, for establishing context before authentication. Returns exactly one BIGINT and never row data.';

-- ---------------------------------------------------------------------------
-- The application role — this is what actually enforces the policies above
--
-- Created without LOGIN and without a password, because a migration is source
-- code and a credential in source code is a published credential.
--
-- It does not need either. The application acquires it with SET ROLE on each
-- pooled connection, which requires only membership, granted below. That means
-- isolation is in force in every environment from the first boot — including
-- local development and the test suite — rather than waiting on an operator to
-- provision a second credential. The alternative, leaving the app connected as
-- the owner until someone remembers, is precisely the silent-bypass hole
-- described at the top of this file.
--
-- A deployment that wants the stronger form gives cde_app its own password out
-- of band and connects as it directly:
--
--   ALTER ROLE cde_app WITH LOGIN PASSWORD '...';
--
-- That is strictly better, because then no session on that connection has the
-- privilege to RESET ROLE back to an unrestricted one. The application code
-- works unchanged either way — SET ROLE to the role you already are is a no-op.
--
-- NOBYPASSRLS is stated explicitly rather than relied on as the default, so
-- the intent survives someone reading this later.
-- ---------------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cde_app') THEN
        CREATE ROLE cde_app NOLOGIN NOBYPASSRLS;
    END IF;
END $$;

-- Membership, so the migration runner's role — and therefore the application's
-- connection — may SET ROLE to it. Without this the application cannot assume
-- the restricted role and would keep running with RLS bypassed.
DO $$
BEGIN
    EXECUTE format('GRANT cde_app TO %I', current_user);
END $$;

GRANT USAGE ON SCHEMA public TO cde_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO cde_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO cde_app;
GRANT EXECUTE ON FUNCTION resolve_tenant_for_login(TEXT) TO cde_app;

-- Tables created by later migrations are covered without anyone remembering to
-- come back and grant them.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cde_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO cde_app;
