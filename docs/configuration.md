# Configuration

Every setting the application reads, its type, default, valid range, whether it
is required, and whether it is a secret.

All configuration comes from environment variables or mounted files — there are
no environment-specific code branches, and the same image runs everywhere.
Properties marked **required** are validated at startup by a
`@ConfigurationProperties` class with Bean Validation; the application fails to
start rather than booting half-configured, so a missing value surfaces at deploy
time rather than on the first request that happens to need it.

> **Secrets are never logged, never returned by an API, and never included in
> an error message or support bundle.** `management.info.env.enabled` is `false`
> precisely because it would publish every `cde.*` property, including the
> signing key.

---

## Authentication

### `cde.jwt.secret`

| | |
|---|---|
| Environment variable | `CDE_JWT_SECRET` |
| Type | string |
| Default | **none — required** |
| Constraint | at least 32 bytes of UTF-8 |
| Secret | **yes** |

The HMAC key that signs and verifies bearer tokens.

There is deliberately no default. A signing key with a fallback value is a
published key: anyone who can read the repository can mint a token for any
account, administrator included, against every deployment still using it.
Rotating it afterwards is a coordinated redeploy rather than a config change.

Three things are refused at startup, each stopping the process with a message
naming the fix:

- **Missing or blank.** Obvious the first time anyone logs in — but by then it
  is running.
- **Shorter than 32 bytes.** HS256 derives no more strength than the key
  carries, and RFC 7518 §3.2 requires a key at least as wide as the digest.
- **The placeholder that used to ship in `application.yml`.** It is in the git
  history permanently, so it is refused by name. It is long enough to pass the
  length check, which is exactly why it needs its own rule — nothing else about
  it looks wrong.

Generate one with:

```
openssl rand -base64 48
```

### `cde.jwt.expiration-ms`

| | |
|---|---|
| Environment variable | `CDE_JWT_EXPIRATION_MS` |
| Type | long (milliseconds) |
| Default | `86400000` (24 hours) |
| Constraint | positive |
| Secret | no |

---

## Password storage

Two key derivation functions are offered because the choice is a deployment
concern, not an engineering one. **Both are always registered for
verification** whatever is configured — the setting selects only what newly-set
passwords are hashed with.

Changing any value here invalidates nothing. Each user's stored hash is
re-derived to the current parameters on their next successful login, in place
and without a prompt. That is what makes raising the cost an operational change
rather than a mass password reset — and a forced reset is precisely the event
that trains users to pick weaker passwords.

### `cde.security.password.kdf`

| | |
|---|---|
| Environment variable | `CDE_SECURITY_PASSWORD_KDF` |
| Type | enum: `pbkdf2-sha256` \| `argon2id` |
| Default | `pbkdf2-sha256` |
| Secret | no |

`pbkdf2-sha256` is the default because it is FIPS 140-validated and on the ASD
ISM approved algorithm list, which government and Defence deployments are
frequently required to use.

`argon2id` resists GPU and ASIC attack considerably better, being memory-hard,
and is the better choice wherever a validated module is not contractually
required.

### `cde.security.password.pbkdf2-iterations`

| | |
|---|---|
| Environment variable | `CDE_SECURITY_PASSWORD_PBKDF2_ITERATIONS` |
| Type | int |
| Default | `600000` |
| Constraint | **minimum 600000 — the floor is enforced, not merely defaulted** |
| Secret | no |

Tune upward to roughly 500 ms on production hardware and re-baseline annually
as hardware improves. **Never reduce it.**

The iteration count is the only thing standing between a stolen hash table and
an offline crack: SHA-256 is designed to be fast, and a commodity GPU tests
billions of raw candidates per second. That is why the floor is a validation
constraint rather than a default — a deployment cannot quietly weaken it.

The count is stored inside each hash (`{pbkdf2-sha256}$600000$salt$hash`), so
raising it is safe. Spring Security's own `Pbkdf2PasswordEncoder` is not used
here for exactly this reason: it stores only salt and digest and re-derives with
whatever count is configured at verification time, so raising the cost makes
every existing password stop verifying while `upgradeEncoding` reports nothing
is stale. The failure is silent and total — a routine cost increase locks out
the whole user base and presents as a wave of wrong passwords.

### `cde.security.password.argon2-memory-kib`

| | |
|---|---|
| Environment variable | `CDE_SECURITY_PASSWORD_ARGON2_MEMORY_KIB` |
| Type | int (kibibytes) |
| Default | `19456` (19 MiB) |
| Constraint | minimum `19456`, the OWASP baseline |
| Secret | no |

### `cde.security.password.argon2-iterations`

| | |
|---|---|
| Environment variable | `CDE_SECURITY_PASSWORD_ARGON2_ITERATIONS` |
| Type | int |
| Default | `2` |
| Constraint | minimum `2` |
| Secret | no |

Parallelism is fixed at 1, the OWASP baseline. Raising it without also raising
memory weakens the function rather than strengthening it.

---

## Tenant isolation

### `cde.tenancy.application-role`

| | |
|---|---|
| Environment variable | `CDE_TENANCY_APPLICATION_ROLE` |
| Type | SQL identifier |
| Default | `cde_app` |
| Secret | no |

The restricted PostgreSQL role every application query runs as. It is assumed
via `SET ROLE` on each pooled connection and released when the connection is
returned.

**This role, not the RLS flags, is what enforces tenant isolation.** PostgreSQL
exempts superusers and any role holding `BYPASSRLS` from Row-Level Security
unconditionally, and reports nothing when it does so. `FORCE ROW LEVEL SECURITY`
does not help: it binds the table *owner* only. Measured directly against this
schema — every policy present, both flags set — a superuser connection returned
every tenant's rows, including with no tenant context set at all. A
containerised PostgreSQL creates its `POSTGRES_USER` as a superuser, so that is
the default configuration, not an exotic one.

The role must therefore be:

- not a superuser
- not the owner of the tables
- explicitly `NOBYPASSRLS`

`V2__tenant_isolation.sql` creates it that way and grants the migration runner
membership so `SET ROLE` is permitted. `TenantIsolationCoverageTest` asserts at
build time that the role the application actually runs as satisfies all three.

**Stronger deployment:** give the role its own password out of band and connect
as it directly, so no session on that connection has the privilege to
`RESET ROLE` at all:

```
ALTER ROLE cde_app WITH LOGIN PASSWORD '...';
```

Then point `SPRING_DATASOURCE_USERNAME` at it and give Flyway the owner
credentials separately. The application code is unchanged — `SET ROLE` to the
role you already are is a no-op.

### `cde.tenancy.default-tenant-slug`

| | |
|---|---|
| Environment variable | `CDE_TENANCY_DEFAULT_TENANT_SLUG` |
| Type | string |
| Default | `default` |
| Secret | no |

The tenant that owns rows created before tenancy existed, and the one
self-service registration joins. It is created by `V2__tenant_isolation.sql`,
not by application seeding, so a database restored from a pre-tenancy backup
has something for the backfill to point at.

---

## Database

### `spring.datasource.url` / `username` / `password`

| | |
|---|---|
| Environment variables | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` |
| Default | `jdbc:postgresql://localhost:5432/cdedb`, `cde`, `cde` |
| Secret | password: **yes** |

The defaults point at a local PostgreSQL so that running with no environment
set fails to connect, rather than silently succeeding against an in-memory
database that discards everything on exit.

### `DB_POOL_MAX`

| | |
|---|---|
| Type | int |
| Default | `10` |
| Secret | no |

One pool per replica. This multiplies against PostgreSQL's `max_connections`,
which is where a scaled-out deployment usually runs out first.

---

## Storage and services

### `cde.storage.upload-dir`

| | |
|---|---|
| Type | path |
| Default | `./uploads` |
| Secret | no |

### `cde.converter.url`

| | |
|---|---|
| Type | URL |
| Default | `http://localhost:5001` |
| Secret | no |

The CAD/Office conversion service. Its reachability is reported by
`ConverterHealthIndicator` as `DEGRADED` rather than `DOWN` — the service is
still worth routing traffic to without it, so it is deliberately excluded from
the readiness group.

### `cde.anthropic.api-key`

| | |
|---|---|
| Environment variable | `ANTHROPIC_API_KEY` |
| Type | string |
| Default | empty (feature disabled) |
| Secret | **yes** |

---

## Not yet implemented

These are required by the guidelines and are **not** configurable yet. Listed
so the gap is visible rather than discovered:

- Deployment tier ceilings (`commercial` / `government` / `defence`) constraining
  what a tenant admin may set — the `deployment_tier` column exists on `tenants`
  and is not yet read by anything
- Password policy: length, complexity, history, expiry, lockout
- Compromised-password checking mode (`ONLINE_API` / `LOCAL_DATASET` / `DISABLED`)
- Storage provider selection (`azure` / `s3` / `gcs` / `local`)
- Per-tenant AI kill switch
