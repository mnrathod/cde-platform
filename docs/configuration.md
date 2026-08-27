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

## Deployment tier

The regulatory envelope this deployment operates in. Set at deployment, never
by a tenant — that is what makes the ceilings below meaningful. A tenant
administrator can tighten a setting but cannot loosen it past what the tier
permits, because the bound is not stored anywhere a tenant can write.

### `cde.security.deployment.tier`

| | |
|---|---|
| Environment variable | `CDE_SECURITY_DEPLOYMENT_TIER` |
| Type | enum: `commercial` \| `government` \| `defence` |
| Default | `commercial` |
| Secret | no |

| Tier | Expiry default | Admin may set | Outbound calls |
|---|---|---|---|
| `commercial` | 90 days | 30–365 | permitted |
| `government` (IRAP-scoped) | 90 days | 30–90 | **prohibited** |
| `defence` (UK MOD / Australian Defence) | by contract | **not at all** | **prohibited** |

The default is `commercial` deliberately: a deployment is never silently locked
into a stricter posture than its operator asked for.

### `cde.security.deployment.contract-password-expiry-days`

| | |
|---|---|
| Type | int |
| Default | none |
| Required | **on the `defence` tier only** |
| Secret | no |

The interval fixed by contract. Required where an administrator cannot choose
one, and ignored on the other tiers. A `defence` deployment without it **fails
to start** — a contractual control with no configured value is not a control.

### External dependencies

| Property | Default |
|---|---|
| `cde.security.deployment.breached-password-check` | `online_api` |
| `cde.security.deployment.ai-features` | `online_api` |
| `cde.security.deployment.telemetry` | `online_api` |

Each takes `online_api`, `local_dataset` or `disabled` — three values rather
than a boolean, because "off" and "running on our own infrastructure" are
genuinely different answers. An air-gapped deployment still wants
breached-password checking; it just cannot ask anyone else to perform it.

**A `government` or `defence` deployment configured with `online_api` fails to
start.** Checked at boot rather than at first use, so the misconfiguration
surfaces in front of whoever deployed it — not weeks later in production, as an
outbound call the contract forbids.

`disabled` on a security control requires a documented risk acceptance. It is
not a neutral default.

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

The tenant that owns rows created before tenancy existed. It is created by
`V2__tenant_isolation.sql`, not by application seeding, so a database restored
from a pre-tenancy backup has something for the backfill to point at.

Self-service registration **no longer joins it**. It used to, which meant
anyone who could reach `/api/auth/register` got read access to every project in
the deployment — Row-Level Security was enforcing correctly throughout, and had
nothing to separate. See `cde.tenancy.self-registration` below.

### `cde.tenancy.self-registration`

| | |
|---|---|
| Environment variable | `CDE_TENANCY_SELF_REGISTRATION` |
| Type | enum: `CREATE_TENANT`, `INVITATION_ONLY`, `DISABLED` |
| Default | `CREATE_TENANT` |
| Secret | no |

What an anonymous caller gets from `POST /api/auth/register`.

| Value | Uninvited registration | With an invitation |
|---|---|---|
| `CREATE_TENANT` | Creates a new organisation; the registrant administers it | Joins the inviting organisation |
| `INVITATION_ONLY` | `403 invitation-required` | Joins the inviting organisation |
| `DISABLED` | `403 registration-closed` | `403 registration-closed` |

`CREATE_TENANT` is the default because an organisation has to be able to reach
the core workflow without an operator provisioning anything first.

**Set `DISABLED` for sovereign, air-gapped and Defence deployments** (§6.5,
§6.6), where an account created by whoever can reach the network is the thing
the deployment exists to prevent. Accounts are then provisioned out of band.

`INVITATION_ONLY` suits a deployment serving named organisations, where an
unrecognised signup is a mistake rather than a customer.

A caller can never name a tenant directly under any of these — that endpoint
requires no credential, so anything it accepts is something a stranger can
assert about themselves. An invitation is proof issued from inside the tenant.

### `cde.tenancy.invitation-validity`

| | |
|---|---|
| Environment variable | `CDE_TENANCY_INVITATION_VALIDITY` |
| Type | ISO-8601 duration |
| Default | `PT168H` (7 days) |
| Secret | no |

How long an unredeemed invitation stays valid. Applies from the moment it is
issued; shortening it does not extend invitations already outstanding, and
lengthening it does not revive expired ones.

Invitation tokens are stored as a SHA-256 hash and returned exactly once, at
creation — a lost one is reissued rather than looked up, the same treatment an
API key gets (§4.6).

---

## Browser hardening and cross-origin access

### `cde.web.allowed-origins`

| | |
|---|---|
| Type | Comma-separated list of full origins |
| Default | *(empty — no cross-origin caller is allowed)* |
| Required | No |
| Secret | No |
| Environment variable | `CDE_WEB_ALLOWED_ORIGINS` |

Which other origins a browser may let call this API.

**Empty means none, and none means no CORS configuration is registered at all**
— not one that allows everything. This was previously `allowedOrigins("*")`
with every request header reflected, which is the configuration that makes the
browser's same-origin policy stop applying: any page a user visited could call
this API from their browser and read the reply.

A same-origin deployment — the Angular build and this API behind one web tier,
which is how it is meant to run — needs nothing set here. Only a genuinely
separate front-end origin does.

Name each origin in full, including scheme and any non-default port. **A
wildcard is refused at startup**, because a wildcard that is never exercised in
testing is a wildcard that reaches production:

```bash
CDE_WEB_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com
```

Permitted request headers are enumerated (`Authorization`, `Content-Type`,
`Accept`, `X-Requested-With`, `Idempotency-Key`) rather than reflected;
reflecting whatever the caller asks for makes the allow-list a formality.

### `cde.web.hsts-enabled`

| | |
|---|---|
| Type | Boolean |
| Default | `true` |
| Required | No |
| Secret | No |
| Environment variable | `CDE_WEB_HSTS_ENABLED` |

Whether responses carry `Strict-Transport-Security`.

The header is only written on requests that arrived over TLS — a browser
ignores it otherwise — so leaving it on costs a plain-HTTP local deployment
nothing. It is on by default because switching it off in the one environment
that terminates TLS is the mistake worth preventing.

### `cde.web.hsts-max-age-seconds`

| | |
|---|---|
| Type | Long (seconds) |
| Default | `31536000` (one year) |
| Required | No |
| Secret | No |
| Environment variable | `CDE_WEB_HSTS_MAX_AGE_SECONDS` |

One year is the minimum most browser preload lists accept. Lower it only while
deliberately unwinding an HSTS commitment.

### `cde.web.csp-report-uri`

| | |
|---|---|
| Type | String (URL) |
| Default | *(empty — no reporting directive)* |
| Required | No |
| Secret | No |
| Environment variable | `CDE_WEB_CSP_REPORT_URI` |

Where a browser posts content-security-policy violation reports. Blank adds no
reporting directive: the policy still blocks, it just tells nobody that it did.

Two policies are served, chosen by path. The API's own is `default-src 'none'`
— this server answers with JSON and has no legitimate need to load a script,
stylesheet, frame or plugin. The documentation page at `/api/docs` gets a
narrower relaxation (`style-src 'self' 'unsafe-inline'`) because Swagger UI
applies inline styles as it renders; the concession is to styles, not scripts,
so an injected script is still refused.

---

## Demonstration data

### `cde.seed.enabled`

| | |
|---|---|
| Type | Boolean |
| Default | `false` |
| Required | No |
| Secret | No |
| Environment variable | `CDE_SEED_ENABLED` |

Whether to create a demonstration administrator and two sample projects at
startup.

**Off by default.** This used to run unconditionally and create an
administrator whose username and password were both written in the source, in
every environment including production — the default-credentials finding that
appears in every penetration-test report ever written, not mitigated by the
account being undocumented, because a value in a public repository is a known
value.

A deployment that says nothing starts with an empty database. The first
organisation is created by registering an account, which is the path a real
tenant takes (see `cde.tenancy.self-registration`).

### `cde.seed.admin-password`

| | |
|---|---|
| Type | String |
| Default | *(none — no fallback)* |
| Required | **Yes, when `cde.seed.enabled` is `true`** |
| Secret | **Yes** |
| Environment variable | `CDE_SEED_ADMIN_PASSWORD` |

The password for the seeded administrator.

There is no default, for the same reason `cde.jwt.secret` has none: a default
that exists is a default that ships. The application **refuses to start** when
seeding is on and this is missing, shorter than the 12-character policy
minimum, or equal to the value this seeder used to hard-code — that value is in
the repository's history permanently and is therefore public, so it is refused
by name.

```bash
export CDE_SEED_ENABLED=true
export CDE_SEED_ADMIN_PASSWORD="$(openssl rand -base64 24)"
```

The password is never written to a log, including at startup.

### `cde.seed.admin-username` / `cde.seed.admin-email`

| | |
|---|---|
| Type | String |
| Default | `admin` / `admin@cde.invalid` |
| Required | No |
| Secret | No |
| Environment variables | `CDE_SEED_ADMIN_USERNAME`, `CDE_SEED_ADMIN_EMAIL` |

Identity of the seeded administrator. The default email uses the reserved
`.invalid` top-level domain so it can never route to a real mailbox.

Only one account is seeded. A second existed so that a sample project could
have a different owner, which cost a second credential to keep out of the
repository; a deployment that wants a second role now invites one, which
demonstrates the product rather than working around it.

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

## API documentation

### `cde.api.version`

| | |
|---|---|
| Environment variable | `CDE_API_VERSION` |
| Type | string |
| Default | `1.0.0` |
| Secret | no |

The version reported in the OpenAPI document's `info.version`. It moves with
the API contract rather than with the build: path versioning (`/api/v1`) is the
compatibility boundary, and this records where within that boundary a given
deployment sits.

### `springdoc.api-docs.path`

| | |
|---|---|
| Type | string |
| Default | `/api/openapi` |
| Secret | no |

Where the specification is served. The YAML form is the same path with a
`.yaml` suffix. Both are public: a client generator, a linter and a reviewer
all read them and none has a credential, and the document describes the shape
of the API rather than anything held in it.

### `springdoc.swagger-ui.path`

| | |
|---|---|
| Type | string |
| Default | `/api/docs` |
| Secret | no |

Where the interactive documentation is served. It redirects to
`/api/swagger-ui/index.html`, so that prefix is public too — permitting only
`/swagger-ui/**` leaves the page reachable and its every asset refused, which
renders as a blank frame.

### `springdoc.swagger-ui.supported-submit-methods`

| | |
|---|---|
| Type | list of HTTP methods |
| Default | empty — the "try it" console is off |
| Secret | no |

The console sends real requests with real credentials from a page that also
enumerates every endpoint, which is a different thing from publishing the
specification. Set it to the methods a sandbox deployment should allow.

Note that springdoc serialises this key into its own `swagger-config` as the
string `"[]"` whether it is written as a list or bound from an environment
variable, so reading the config back does not tell you whether the console is
on. `e2e/tests/api-docs.spec.ts` in the frontend repository asserts against the
rendered page instead.

---

## Not yet implemented

These are required by the guidelines and are **not** configurable yet. Listed
so the gap is visible rather than discovered:

- Password policy beyond expiry: length, complexity, history, minimum age,
  lockout and breach checking. The expiry **ceiling** is enforced; the policy
  the ceiling applies to is not built.
- The actual Have I Been Pwned client. The mode is configured and validated;
  nothing calls it yet.
- Storage provider selection (`azure` / `s3` / `gcs` / `local`)
- Per-tenant AI kill switch. The deployment-level mode exists; the per-tenant
  override does not.
