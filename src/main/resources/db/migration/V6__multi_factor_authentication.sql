-- ---------------------------------------------------------------------------
-- V6: multi-factor authentication (CLAUDE.md 4.4)
--
-- Two tables: one enrolment per user, and their single-use recovery codes.
--
-- Neither holds a usable credential in the clear. The TOTP secret is encrypted
-- at the application layer with AES-256-GCM before it reaches here, and
-- recovery codes are stored as SHA-256 digests. A database dump therefore
-- yields no second factor for anybody — which is the whole point, since unlike
-- a password hash a TOTP secret has nothing to crack: the plaintext *is* the
-- credential.
-- ---------------------------------------------------------------------------

CREATE TABLE user_mfa_enrolment (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,

    -- AES-256-GCM ciphertext, base64, nonce prefixed. Never the raw secret.
    encrypted_secret    TEXT         NOT NULL,

    -- SHA1 or SHA256, and the digit count, recorded per enrolment so that
    -- changing the deployment default does not invalidate everyone already
    -- enrolled with an authenticator app configured the old way.
    algorithm           VARCHAR(16)  NOT NULL DEFAULT 'SHA1',
    digits              SMALLINT     NOT NULL DEFAULT 6,

    -- Enrolment is not complete until one code has been verified. An
    -- unconfirmed row means the user scanned a QR code and never proved the
    -- authenticator actually works; activating without that check locks people
    -- out of their own accounts on a mistyped setup.
    confirmed_at        TIMESTAMPTZ,

    -- Replay protection (4.4). The last time step a code was accepted for;
    -- anything at or below it is refused even when arithmetically correct.
    -- Without this a code observed over a shoulder stays usable for its whole
    -- 90-second window.
    last_used_time_step BIGINT,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- One enrolment per user. A second row would mean two live second factors
    -- with no rule about which wins.
    CONSTRAINT user_mfa_enrolment_unique_user UNIQUE (tenant_id, user_id),
    CONSTRAINT user_mfa_enrolment_user_fk
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT user_mfa_enrolment_digits_valid CHECK (digits BETWEEN 6 AND 8),
    CONSTRAINT user_mfa_enrolment_algorithm_valid
        CHECK (algorithm IN ('SHA1', 'SHA256', 'SHA512'))
);

-- tenant_id leads, per 7.5: it is in every query's predicate via RLS.
CREATE INDEX idx_mfa_enrolment_tenant_user ON user_mfa_enrolment (tenant_id, user_id);

-- The foreign key needs its own index, which the composite above does not
-- provide because tenant_id leads it. Postgres does not index a referencing
-- column automatically, so ON DELETE CASCADE from users would sequential-scan
-- this table on every account deletion.
CREATE INDEX idx_mfa_enrolment_user ON user_mfa_enrolment (user_id);

CREATE TABLE user_recovery_code (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,

    -- SHA-256 of the code, hex. Recovery codes are high-entropy and
    -- server-generated, so a fast digest is right here: there is no dictionary
    -- to attack and no user-chosen value to guess. This is the same reasoning
    -- that makes a fast digest wrong for passwords (4.1).
    -- VARCHAR rather than CHAR: Postgres pads a CHAR to its declared width
    -- with spaces, so a value that arrived short would compare equal to a
    -- padded one. A hash lookup should not have a padding rule in it.
    code_hash      VARCHAR(64)  NOT NULL,

    -- Single use. Recorded rather than deleted so that using one is visible in
    -- the audit trail and so a user can see how many they have left.
    used_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT user_recovery_code_unique UNIQUE (tenant_id, user_id, code_hash),
    CONSTRAINT user_recovery_code_user_fk
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_recovery_code_tenant_user ON user_recovery_code (tenant_id, user_id);

-- Same reason as above: the referencing column needs an index of its own for
-- the cascade, and this table holds ten rows per enrolled user.
CREATE INDEX idx_recovery_code_user ON user_recovery_code (user_id);

-- Unused codes only: the lookup on redemption asks "is there an unused code
-- with this hash", and the partial index is both smaller and exactly matched
-- to that question.
CREATE INDEX idx_recovery_code_unused
    ON user_recovery_code (tenant_id, user_id, code_hash)
    WHERE used_at IS NULL;

-- ---------------------------------------------------------------------------
-- Tenant isolation (5.6): enabled AND forced, as on every other tenant table.
-- FORCE matters because it applies the policy to the table owner too; without
-- it, a role that happens to own the table reads every tenant's rows.
-- ---------------------------------------------------------------------------
ALTER TABLE user_mfa_enrolment ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_mfa_enrolment FORCE  ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON user_mfa_enrolment
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT);

ALTER TABLE user_recovery_code ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_recovery_code FORCE  ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON user_recovery_code
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::BIGINT);

GRANT SELECT, INSERT, UPDATE, DELETE ON user_mfa_enrolment TO cde_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON user_recovery_code TO cde_app;
GRANT USAGE, SELECT ON SEQUENCE user_mfa_enrolment_id_seq TO cde_app;
GRANT USAGE, SELECT ON SEQUENCE user_recovery_code_id_seq TO cde_app;

COMMENT ON TABLE user_mfa_enrolment IS
    'One TOTP enrolment per user. The secret is AES-256-GCM ciphertext, never plaintext.';
COMMENT ON COLUMN user_mfa_enrolment.last_used_time_step IS
    'Replay protection: the last time step accepted. Codes at or below it are refused.';
COMMENT ON COLUMN user_mfa_enrolment.confirmed_at IS
    'Set when the user proves their authenticator works. Until then the enrolment is not a factor.';
COMMENT ON TABLE user_recovery_code IS
    'Single-use recovery codes, stored as SHA-256 digests. Marked used rather than deleted, so redemption is auditable.';
