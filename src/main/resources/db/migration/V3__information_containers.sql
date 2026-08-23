-- The ISO 19650 Common Data Environment.
--
-- The information container and its revisions are first-class entities, on the
-- same footing as tenant and user, rather than attributes of a file record.
-- That is not a modelling preference: every document-touching feature, every
-- permission check and every audit trail assumes them, so retrofitting later
-- means migrating all of it at once.
--
-- The container is the identity that persists; revisions are what actually
-- carry state and content. A published revision is the contractual record and
-- is never edited — a change produces a new revision that supersedes it, and
-- the superseded one stays retrievable for the operational life of the asset.

-- ---------------------------------------------------------------------------
-- Containers
-- ---------------------------------------------------------------------------

CREATE TABLE information_containers (
    id                  BIGSERIAL    PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL,
    project_id          BIGINT       NOT NULL,

    -- The assembled, delimited identifier a person reads and searches on.
    container_reference VARCHAR(255) NOT NULL,

    -- The individual naming fields, held as JSON rather than as columns.
    --
    -- ISO 19650-2 describes a field pattern (project, originator, volume,
    -- level, type, role, number) but the delimiter, the field set and the
    -- permitted values are all configurable per project — organisations differ,
    -- and the standard's own code tables are copyrighted and must not be
    -- reproduced in the product. Fixed columns would hardcode one
    -- organisation's convention into the schema.
    naming_fields       JSONB        NOT NULL DEFAULT '{}'::jsonb,

    created_by          BIGINT       NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT fk_containers_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_containers_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_containers_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uk_container_reference UNIQUE (tenant_id, project_id, container_reference)
);

CREATE INDEX idx_containers_tenant_project ON information_containers (tenant_id, project_id);

-- ---------------------------------------------------------------------------
-- Suitability and revision codes
--
-- Tenant- and project-populated, never shipped. Two reasons, and both are
-- binding: organisations customise these lists, and the code tables in the
-- standard itself are copyrighted material that may not be reproduced in the
-- product. What ships is the mechanism.
-- ---------------------------------------------------------------------------

CREATE TABLE suitability_codes (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    -- NULL means the code applies across the tenant rather than to one project.
    project_id    BIGINT,
    code          VARCHAR(20)  NOT NULL,
    description   VARCHAR(255) NOT NULL,
    display_order INT          NOT NULL DEFAULT 0,
    -- Which container state this code is valid in, so a drawing cannot be
    -- marked "issued for construction" while still work in progress.
    valid_in_state VARCHAR(20),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT fk_suitability_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_suitability_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT uk_suitability_code UNIQUE (tenant_id, project_id, code),
    CONSTRAINT suitability_state_check
        CHECK (valid_in_state IS NULL OR valid_in_state IN
               ('WORK_IN_PROGRESS', 'SHARED', 'PUBLISHED', 'ARCHIVED'))
);

CREATE INDEX idx_suitability_tenant ON suitability_codes (tenant_id, project_id);

-- ---------------------------------------------------------------------------
-- Revisions
-- ---------------------------------------------------------------------------

CREATE TABLE container_revisions (
    id                       BIGSERIAL   PRIMARY KEY,
    tenant_id                BIGINT      NOT NULL,
    container_id             BIGINT      NOT NULL,

    -- Preliminary/contractual convention (P01.01, C01). The scheme is
    -- configurable per project, so this is free text validated by the
    -- application against the project's configuration rather than by a CHECK.
    revision_code            VARCHAR(20) NOT NULL,

    state                    VARCHAR(20) NOT NULL DEFAULT 'WORK_IN_PROGRESS',
    suitability_code_id      BIGINT,

    -- The bytes. Linked to the existing document record where one exists, so
    -- the viewer, markup and versioning all keep working unchanged.
    document_id              BIGINT,
    file_path                VARCHAR(1000),

    -- Lineage, recorded in both directions so it is reconstructible from either
    -- end without a recursive scan.
    supersedes_revision_id   BIGINT,
    superseded_by_revision_id BIGINT,

    created_by               BIGINT      NOT NULL,
    created_at               TIMESTAMP   NOT NULL DEFAULT now(),

    -- Authorisation, recorded at publication. Who authorised it is a
    -- contractual question, not an audit convenience.
    published_by             BIGINT,
    published_at             TIMESTAMP,
    approval_reason          VARCHAR(1000),

    CONSTRAINT fk_revisions_tenant      FOREIGN KEY (tenant_id)    REFERENCES tenants (id),
    CONSTRAINT fk_revisions_container   FOREIGN KEY (container_id) REFERENCES information_containers (id),
    CONSTRAINT fk_revisions_suitability FOREIGN KEY (suitability_code_id) REFERENCES suitability_codes (id),
    CONSTRAINT fk_revisions_document    FOREIGN KEY (document_id)  REFERENCES documents (id),
    CONSTRAINT fk_revisions_creator     FOREIGN KEY (created_by)   REFERENCES users (id),
    CONSTRAINT fk_revisions_publisher   FOREIGN KEY (published_by) REFERENCES users (id),
    CONSTRAINT fk_revisions_supersedes  FOREIGN KEY (supersedes_revision_id) REFERENCES container_revisions (id),
    CONSTRAINT fk_revisions_superseded  FOREIGN KEY (superseded_by_revision_id) REFERENCES container_revisions (id),

    CONSTRAINT uk_container_revision UNIQUE (tenant_id, container_id, revision_code),

    CONSTRAINT revisions_state_check
        CHECK (state IN ('WORK_IN_PROGRESS', 'SHARED', 'PUBLISHED', 'ARCHIVED')),

    -- A published revision must carry its authorisation. Enforced here rather
    -- than only in the service, so no future code path can publish anonymously.
    CONSTRAINT revisions_published_has_approver
        CHECK (state NOT IN ('PUBLISHED', 'ARCHIVED')
               OR (published_by IS NOT NULL AND published_at IS NOT NULL))
);

CREATE INDEX idx_revisions_tenant_container ON container_revisions (tenant_id, container_id);
CREATE INDEX idx_revisions_tenant_state     ON container_revisions (tenant_id, state);

-- Exactly one revision of a container may be current — that is, published and
-- not yet superseded. A partial unique index expresses this without
-- constraining the superseded ones, of which there may be many.
CREATE UNIQUE INDEX uk_one_current_published_revision
    ON container_revisions (tenant_id, container_id)
 WHERE state = 'PUBLISHED' AND superseded_by_revision_id IS NULL;

-- ---------------------------------------------------------------------------
-- Transition audit
--
-- Every state change, with its actor, its moment and its reason. Append-only:
-- the application role is granted INSERT and SELECT and nothing else, so a
-- transition cannot be rewritten or removed by the application at all.
-- ---------------------------------------------------------------------------

CREATE TABLE container_state_transitions (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    revision_id   BIGINT       NOT NULL,
    from_state    VARCHAR(20)  NOT NULL,
    to_state      VARCHAR(20)  NOT NULL,
    performed_by  BIGINT       NOT NULL,
    performed_at  TIMESTAMP    NOT NULL DEFAULT now(),
    reason        VARCHAR(1000),

    CONSTRAINT fk_transitions_tenant   FOREIGN KEY (tenant_id)   REFERENCES tenants (id),
    CONSTRAINT fk_transitions_revision FOREIGN KEY (revision_id) REFERENCES container_revisions (id),
    CONSTRAINT fk_transitions_actor    FOREIGN KEY (performed_by) REFERENCES users (id)
);

CREATE INDEX idx_transitions_tenant_revision
    ON container_state_transitions (tenant_id, revision_id, performed_at);

-- ---------------------------------------------------------------------------
-- Foreign key indexes
--
-- Separate from the composite indexes above, and not redundant with them.
-- Those lead with tenant_id because that is what every application query
-- filters on; a foreign key check does the opposite, looking up by the
-- referencing column alone when a parent row is updated or deleted. An index
-- leading with tenant_id cannot serve that, so PostgreSQL falls back to a
-- sequential scan — invisible on demo data, and not on real data.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_containers_project        ON information_containers (project_id);
CREATE INDEX idx_containers_creator        ON information_containers (created_by);
CREATE INDEX idx_suitability_project       ON suitability_codes (project_id);
CREATE INDEX idx_revisions_container       ON container_revisions (container_id);
CREATE INDEX idx_revisions_suitability     ON container_revisions (suitability_code_id);
CREATE INDEX idx_revisions_document        ON container_revisions (document_id);
CREATE INDEX idx_revisions_creator         ON container_revisions (created_by);
CREATE INDEX idx_revisions_publisher       ON container_revisions (published_by);
CREATE INDEX idx_revisions_supersedes      ON container_revisions (supersedes_revision_id);
CREATE INDEX idx_revisions_superseded_by   ON container_revisions (superseded_by_revision_id);
CREATE INDEX idx_transitions_revision      ON container_state_transitions (revision_id);
CREATE INDEX idx_transitions_actor         ON container_state_transitions (performed_by);

-- ---------------------------------------------------------------------------
-- Published revisions are immutable — enforced by the database
--
-- The application refuses to edit a published revision too. That is not enough
-- on its own: an application-level rule holds only for as long as every future
-- call site remembers to consult it, and the cost of one that forgets is a
-- silently rewritten contractual record. The same reasoning that puts tenant
-- isolation in RLS rather than in repository methods applies here.
--
-- Two changes remain legal after publication, because the lifecycle requires
-- them:
--
--   * state may move PUBLISHED -> ARCHIVED, which is supersession completing;
--   * superseded_by_revision_id may be set once, from NULL, to record what
--     replaced this revision.
--
-- Everything else — content, revision code, suitability, approver, the file
-- itself — is frozen, and DELETE is refused outright.
-- ---------------------------------------------------------------------------

CREATE FUNCTION reject_modification_of_published_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'Revision % of container % is % and cannot be deleted. A published '
            'container is the contractual record; supersede it with a new revision.',
            OLD.revision_code, OLD.container_id, OLD.state
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- Archiving a published revision.
    IF OLD.state = 'PUBLISHED' AND NEW.state = 'ARCHIVED' THEN
        NULL;
    ELSIF OLD.state IS DISTINCT FROM NEW.state THEN
        RAISE EXCEPTION
            'Revision % is % and cannot change state to %.',
            OLD.revision_code, OLD.state, NEW.state
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- Recording supersession, once.
    IF OLD.superseded_by_revision_id IS NOT NULL
       AND OLD.superseded_by_revision_id IS DISTINCT FROM NEW.superseded_by_revision_id THEN
        RAISE EXCEPTION
            'Revision % is already superseded by revision %; supersession is not rewritable.',
            OLD.revision_code, OLD.superseded_by_revision_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- Everything else must be untouched.
    IF  NEW.revision_code       IS DISTINCT FROM OLD.revision_code
     OR NEW.container_id        IS DISTINCT FROM OLD.container_id
     OR NEW.tenant_id           IS DISTINCT FROM OLD.tenant_id
     OR NEW.suitability_code_id IS DISTINCT FROM OLD.suitability_code_id
     OR NEW.document_id         IS DISTINCT FROM OLD.document_id
     OR NEW.file_path           IS DISTINCT FROM OLD.file_path
     OR NEW.created_by          IS DISTINCT FROM OLD.created_by
     OR NEW.created_at          IS DISTINCT FROM OLD.created_at
     OR NEW.published_by        IS DISTINCT FROM OLD.published_by
     OR NEW.published_at        IS DISTINCT FROM OLD.published_at
     OR NEW.approval_reason     IS DISTINCT FROM OLD.approval_reason
     OR NEW.supersedes_revision_id IS DISTINCT FROM OLD.supersedes_revision_id
    THEN
        RAISE EXCEPTION
            'Revision % is % and its content cannot be modified. Issue a new '
            'revision that supersedes it.',
            OLD.revision_code, OLD.state
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_published_revisions_are_immutable
    BEFORE UPDATE OR DELETE ON container_revisions
    FOR EACH ROW
    WHEN (OLD.state IN ('PUBLISHED', 'ARCHIVED'))
    EXECUTE FUNCTION reject_modification_of_published_revision();

-- ---------------------------------------------------------------------------
-- Tenant isolation for the new tables
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    tenant_table TEXT;
BEGIN
    FOREACH tenant_table IN ARRAY ARRAY[
        'information_containers', 'container_revisions',
        'container_state_transitions', 'suitability_codes'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tenant_table);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', tenant_table);
        EXECUTE format($policy$
            CREATE POLICY tenant_isolation ON %I
                USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT)
                WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT)
        $policy$, tenant_table);
    END LOOP;
END $$;

-- The audit table is append-only to the application. Revoking UPDATE and
-- DELETE means a transition record cannot be altered by any code path, however
-- privileged within the application, rather than only by code that behaves.
REVOKE UPDATE, DELETE ON container_state_transitions FROM cde_app;
