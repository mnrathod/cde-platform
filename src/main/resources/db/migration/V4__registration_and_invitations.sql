-- Self-service registration stops joining the default tenant.
--
-- Registration put every new account into the deployment's default tenant,
-- because the alternative on the table was accepting a tenant identifier from
-- the request body — and that endpoint requires no credential, so anything it
-- accepts is something a stranger can assert about themselves.
--
-- Defaulting was the safer of those two, and it was still wrong: anyone who
-- could reach /api/auth/register got read access to every project in the
-- deployment. Row-Level Security was working perfectly the whole time. It had
-- nothing to separate, because registration put everybody on the same side of
-- the boundary.
--
-- What follows is the third option: a registration either creates its own
-- tenant, or presents an invitation issued from inside the tenant it wants to
-- join. The caller still cannot name a tenant — an invitation is proof, not an
-- assertion.

-- ---------------------------------------------------------------------------
-- Invitations
--
-- Tenant-scoped, so an invitation is only visible to the organisation that
-- issued it, and only an administrator of that organisation can issue one.
-- ---------------------------------------------------------------------------

CREATE TABLE invitations (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,

    -- Who it is for. Registration must present this same address: a token that
    -- admits whoever holds it turns a forwarded email into an account in
    -- someone else's organisation.
    email         VARCHAR(254) NOT NULL,

    -- The role the invitee receives. Chosen by the inviting administrator,
    -- never by the person redeeming it.
    role          VARCHAR(20)  NOT NULL,

    -- SHA-256 of the token, hex. The token itself is shown once, at creation,
    -- and is not recoverable afterwards — the same treatment as an API key
    -- (§4.6), and for the same reason: a readable invitation table is a set of
    -- credentials for every pending account.
    --
    -- VARCHAR rather than CHAR: CHAR pads to width and compares ignoring
    -- trailing spaces, which is a subtlety nobody wants anywhere near a lookup
    -- key. The check constraint pins the format instead, so a plaintext token
    -- written here by mistake is rejected by the database rather than stored.
    token_hash    VARCHAR(64)  NOT NULL,

    expires_at    TIMESTAMP    NOT NULL,

    -- Single use. Set when redeemed; a second attempt with the same token is
    -- refused rather than silently creating a second account.
    accepted_at   TIMESTAMP,
    accepted_by   BIGINT,

    revoked_at    TIMESTAMP,

    created_by    BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT fk_invitations_tenant   FOREIGN KEY (tenant_id)   REFERENCES tenants (id),
    CONSTRAINT fk_invitations_creator  FOREIGN KEY (created_by)  REFERENCES users (id),
    CONSTRAINT fk_invitations_acceptor FOREIGN KEY (accepted_by) REFERENCES users (id),

    -- Globally unique, not per tenant. The hash of a 256-bit random value
    -- colliding across tenants is not a real risk, but a token that resolved
    -- to two tenants would be, and this makes that unrepresentable rather than
    -- something the lookup has to defend against.
    CONSTRAINT uk_invitations_token UNIQUE (token_hash),

    CONSTRAINT invitations_role_check
        CHECK (role IN ('ADMIN', 'ENGINEER', 'REVIEWER', 'VIEWER')),

    CONSTRAINT invitations_token_hash_format_check
        CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_invitations_tenant_email ON invitations (tenant_id, lower(email));

-- Both foreign keys, indexed. An unindexed one makes every delete of the
-- referenced row scan this table, and PostgreSQL takes the referencing rows'
-- locks while it does it.
CREATE INDEX idx_invitations_created_by  ON invitations (created_by);
CREATE INDEX idx_invitations_accepted_by ON invitations (accepted_by);

-- Redemption looks a token up by hash, and only unredeemed ones matter.
CREATE INDEX idx_invitations_pending ON invitations (token_hash)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitations FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON invitations
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT);

GRANT SELECT, INSERT, UPDATE, DELETE ON invitations TO cde_app;
GRANT USAGE, SELECT ON SEQUENCE invitations_id_seq TO cde_app;

-- ---------------------------------------------------------------------------
-- Resolving an invitation before there is a tenant context
--
-- The same circularity as resolve_tenant_for_login, and solved the same way.
-- Redemption happens on an unauthenticated request: the invitation is behind
-- the tenant policy, but reading it is how the tenant gets established.
--
-- This returns exactly one BIGINT and never row data. It cannot disclose the
-- invited address, the role, or who issued it — the caller learns only which
-- tenant a token it already holds belongs to, and it only answers for a token
-- that is currently redeemable. Everything else is re-checked by the
-- application once the context is bound and the row can be read under the
-- policy in the normal way.
-- ---------------------------------------------------------------------------

CREATE FUNCTION resolve_tenant_for_invitation(p_token_hash TEXT)
RETURNS BIGINT
LANGUAGE sql
STABLE
SECURITY DEFINER
-- Fixed search_path: a SECURITY DEFINER function that resolves unqualified
-- names through the caller's search_path can be hijacked by a same-named
-- object in a schema the caller controls.
SET search_path = pg_catalog, public
AS $$
    SELECT tenant_id
    FROM public.invitations
    WHERE token_hash  = p_token_hash
      AND accepted_at IS NULL
      AND revoked_at  IS NULL
      AND expires_at  > now()
$$;

COMMENT ON FUNCTION resolve_tenant_for_invitation(TEXT) IS
    'Returns the tenant owning a redeemable invitation token hash, for establishing context before authentication. Returns exactly one BIGINT and never row data.';

-- ---------------------------------------------------------------------------
-- Checking a username or email is free across the whole deployment
--
-- users.username and users.email are globally unique by design, because login
-- resolves the tenant from the username alone. Registration checked them with
-- an ordinary query, which under RLS only ever saw the caller's own tenant —
-- harmless while every account landed in one tenant, and wrong the moment they
-- stop: the check would pass and the insert would then fail on the global
-- constraint, turning a name clash into a 500.
--
-- Returns a boolean and nothing else. It tells a caller no more than the 409
-- that registration already returns for a taken name.
-- ---------------------------------------------------------------------------

CREATE FUNCTION registration_identity_taken(p_username TEXT, p_email TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.users
        WHERE username = p_username
           OR lower(email) = lower(p_email)
    )
$$;

COMMENT ON FUNCTION registration_identity_taken(TEXT, TEXT) IS
    'Whether a username or email is already in use anywhere. Returns a boolean and never row data; discloses no more than the conflict response registration already returns.';

GRANT EXECUTE ON FUNCTION resolve_tenant_for_invitation(TEXT) TO cde_app;
GRANT EXECUTE ON FUNCTION registration_identity_taken(TEXT, TEXT) TO cde_app;
