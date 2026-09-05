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

For a standing development account, generate the password once and keep it in
`.env` rather than regenerating per shell. `docker-compose.yml` passes both
variables through to `cde-app`, and compose reads `.env` directly; Spring Boot
does not, so the manual `bootRun` path needs them exported (the README's
`set -a; source .env; set +a` does this). The account is written on the first
start against an empty database and then left alone, so it survives restarts
for the life of the `pgdata` volume.

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

## Upload admission

Every upload is streamed to a **quarantine** directory, examined there, and
only moved into place once it passes. Writing to the final location and
checking afterwards would leave a window in which the file is referenced,
downloadable and unexamined — and that window is the vulnerability, not the
checking.

Two examinations run, in order:

1. **What the bytes are.** The content type is detected from the leading bytes,
   not from the file name or the client's `Content-Type` header — an attacker
   chooses both of those. Executables and scripts are refused whatever they are
   called; anything outside the permitted set is refused. Markup carrying a
   script or an event handler is **refused rather than sanitised**: a small SVG
   is stored inline and rendered as markup on the application's own origin, so a
   scripted one is stored cross-site scripting, and a CAD export has no scripts
   in it.
2. **Whether a scanner objects.** See `cde.upload.scanning`.

A refused file is deleted rather than retained. Keeping malware means storing
it, backing it up and replicating it; the signature in the audit record is what
an investigation needs.

### `cde.upload.scanning`

| | |
|---|---|
| Type | enum: `required` \| `best-effort` \| `disabled` |
| Default | `best-effort` |
| Required | No |
| Secret | No |
| Environment variable | `CDE_UPLOAD_SCANNING` |

What an unreachable scanner means. Three values rather than a boolean, because
"no scanner configured" and "the scanner is down right now" need opposite
treatment — the first is a deployment that chose not to scan, the second is one
that chose to scan and currently cannot.

- **`required`** — uploads are refused unless a scanner confirms them clean. A
  scanner that is down stops uploads. **This is the correct setting for a
  shared Common Data Environment**: a file admitted unscanned is one every
  appointed party on the project will open.
- **`best-effort`** — scan when the scanner answers, admit with a logged
  warning when it does not. Availability over certainty; defensible for a
  single-tenant internal deployment, not for a shared one.
- **`disabled`** — do not scan. A deliberate choice with a documented risk
  acceptance, which is why it has to be named rather than reached by leaving
  something unset.

With `required` and no `scanner-host`, **the application refuses to start** —
that configuration accepts no uploads at all, and finding out in front of
whoever deployed it beats finding out from a user.

### `cde.upload.scanner-host` / `scanner-port` / `scanner-timeout`

| | |
|---|---|
| Type | String / Integer / Duration |
| Default | *(empty)* / `3310` / `PT30S` |
| Required | When `scanning` is `required` |
| Secret | No |
| Environment variables | `CDE_UPLOAD_SCANNER_HOST`, `CDE_UPLOAD_SCANNER_PORT`, `CDE_UPLOAD_SCANNER_TIMEOUT` |

A ClamAV daemon, reached over its INSTREAM protocol on a socket.

Out of process by design, not by accident: **ClamAV is GPLv2**, and using it as
a separate service over a socket is what keeps it out of this application's
licensing. Linking or embedding it would create a combined work.

INSTREAM rather than SCAN because SCAN takes a path, which requires the daemon
to see the same filesystem — it cannot when the two run in separate containers,
which is the arrangement this is deployed in. The file is streamed in bounded
chunks, so scanning a two-gigabyte model costs one buffer.

---

## Fetching integrator-supplied URLs

ADR 12 has the host application mint a short-lived link — a Graph download URL,
an S3 presigned GET, an Azure SAS, a GCS signed URL — and hand it to us to
fetch. That is what lets four storage platforms collapse into one code path
without this product holding anybody's storage credentials.

It also means **a caller chooses an address that this server then connects
to**, which is server-side request forgery in its textbook form (§5.12 A10).
What makes that dangerous is not the internet: it is that this server sits
inside a network the caller cannot otherwise reach. The cloud metadata endpoint
at `169.254.169.254` hands out instance credentials to anything asking from the
instance. The database, the converter and Actuator on the internal interface
are each one HTTP request away from a process that makes requests on request.

So the destination is judged before a socket is opened, and **the judgement is
on the resolved addresses, never on the text of the host**. A name is not a
destination: `evil.example` can resolve to `127.0.0.1`, and a string check
passes it. Every resolved address is checked, and a name resolving to several is
allowed only if all of them are — which of them the connection picks is not ours
to choose.

Refused: loopback, link-local (so the metadata endpoint), RFC 1918, IPv6
unique-local `fc00::/7`, the unspecified address, and multicast. Only `https`
and `http` schemes are permitted, by allow-list, so `file:` — which would turn
this into an arbitrary file read — is refused by omission. Redirects are not
followed, because a redirect names a second destination no check has seen.

A refusal never names the address a host resolved to. A caller who learns that
their name reached `10.0.4.17` has been handed a network map one refusal at a
time.

**The residual risk, stated rather than papered over.** The policy resolves the
host and the HTTP client resolves it again, so a name whose DNS answer changes
between the two is checked at one address and connected to at another. That
window cannot be closed in application code: the JDK's client offers no
supported way to pin a connection to an already-validated address, and
connecting to a raw IP with a spoofed `Host` header breaks certificate
validation — a narrow hole traded for a wider one. It is closed by the two
controls §5.12 A10 names alongside address validation: the host allow-list
below, and a filtered egress proxy. **A deployment that sets neither is relying
on the pre-check alone.**

### `cde.fetch.enabled`

| | |
|---|---|
| Type | Boolean |
| Default | `false` |
| Required | No |
| Secret | No |
| Environment variable | `CDE_FETCH_ENABLED` |

Whether integrator-supplied URLs may be fetched at all.

Off by default, and off is a legitimate production posture rather than a
degraded one: an air-gapped or PROTECTED deployment (§6.4–6.6) prohibits
outbound calls outright and pushes content to the upload endpoint instead.
Defaulting to on would make the sovereign case the one that has to remember.

When off, **no outbound HTTP client is created at all** — absent beats
disabled, because there is then no code path to reach.

### `cde.fetch.permitted-hosts`

| | |
|---|---|
| Type | List of host names |
| Default | *(empty — any host passing the address rules)* |
| Required | No |
| Secret | No |
| Environment variable | `CDE_FETCH_PERMITTED_HOSTS` |

Host names that may be fetched, matched case-insensitively as DNS does.

**Set this.** The address rules stop us reaching our own network; an allow-list
additionally stops us being pointed at somebody else's, and it is the only
setting that fully closes the rebinding window described above. A deployment
integrating with one CDE knows its storage hosts. Leaving it empty logs a
warning at startup.

### `cde.fetch.require-tls`

| | |
|---|---|
| Type | Boolean |
| Default | `true` |
| Required | No |
| Secret | No |
| Environment variable | `CDE_FETCH_REQUIRE_TLS` |

Refuse `http`, permitting only `https`. Worth leaving on: a presigned link
carries its own authorisation in the query string, so plain http hands that
credential to anything on the path. The setting exists for an on-premises
integration against a storage service inside the same trusted segment.

### `cde.fetch.max-content-size`

| | |
|---|---|
| Type | Data size |
| Default | `2GB` |
| Required | No |
| Secret | No |
| Environment variable | `CDE_FETCH_MAX_CONTENT_SIZE` |

The largest response body accepted.

Enforced as a **running total during the transfer**, not believed from
`Content-Length` — a declared length is a claim by the server we were pointed
at, and a server that declares ten bytes and sends ten megabytes passes every
check made before the transfer. The declared length is checked too, so the
honest oversize case is refused without opening a file and the caller is told
the actual size.

Two gigabytes matches the chunked-upload ceiling: a federated model is the case
this exists for, and the two intake paths should not disagree about how large a
document may be.

### `cde.fetch.connect-timeout` / `response-timeout` / `transfer-timeout`

| | |
|---|---|
| Type | Duration |
| Default | `PT10S` / `PT30S` / `PT30M` |
| Required | No |
| Secret | No |
| Environment variables | `CDE_FETCH_CONNECT_TIMEOUT`, `CDE_FETCH_RESPONSE_TIMEOUT`, `CDE_FETCH_TRANSFER_TIMEOUT` |

Three bounds rather than one, because they stop different things.

`connect-timeout` and `response-timeout` cover a destination that never
answers. `transfer-timeout` covers one that answers *promptly* and then
dribbles: a response timeout is satisfied the moment headers arrive, so without
a separate deadline a slow-loris response holds a thread and a disk allocation
for as long as it likes. It is generous because the transfer it bounds may
legitimately be a two-gigabyte model over a slow link.

---

## The conversion queue

Converting a document is bulk work by §7.1's definition — its cost scales with
the file — so submission returns `202` with a job to poll and the work happens
on a queue. There is no synchronous variant, deliberately: a two-gigabyte model
cannot be converted inside a second, and an endpoint that sometimes takes ten
minutes is one every client eventually times out against.

The queue is **in-process rather than ActiveMQ Artemis**, which §7.8 would
otherwise make the default for document processing. This is a deliberate
departure and worth understanding before deploying:

A message carrying the source URL would put a presigned bearer credential in
the broker's journal on disk, which is the same objection that keeps it out of
the database. The alternatives were to encrypt it into a message — a key, a
rotation story and a test suite of its own, to protect something with a
fifteen-minute life — or to hold it in memory and accept that a job interrupted
by a restart is failed rather than resumed. The second is smaller and its
failure mode is honest.

**The consequence:** execution is bound to the instance that accepted the
submission, which is a departure from §8.1. Job *status* is not — it lives in
the database, so any instance serves it — and neither is the result, which is
in object storage. What is lost is resuming in-flight work elsewhere, which the
un-persisted credential rules out regardless. A job interrupted by a restart is
failed at startup with a reason saying to resubmit with a fresh link.

These settings apply only where `cde.fetch.enabled` is true. Where it is not,
there is no queue, no worker and no endpoint.

### `cde.conversion.workers`

| | |
|---|---|
| Type | Integer |
| Default | `4` |
| Required | No |
| Secret | No |
| Environment variable | `CDE_CONVERSION_WORKERS` |

How many conversions run at once on this instance.

Small on purpose. Each one holds a fetched original and a converted copy on
disk and occupies a converter slot, so raising it raises disk and converter
pressure rather than throughput. Raise the converter's own capacity first.

### `cde.conversion.max-concurrent-per-tenant`

| | |
|---|---|
| Type | Integer |
| Default | `2` |
| Required | No |
| Secret | No |
| Environment variable | `CDE_CONVERSION_MAX_CONCURRENT_PER_TENANT` |

The most conversions one organisation may have running at once.

Noisy-neighbour protection (§7.8). Without it, a tenant submitting a hundred
models occupies every worker and every other tenant's jobs wait behind them —
which surfaces as "the system is slow for everyone except one customer", and is
diagnosed late because nothing is actually broken.

Keep it below `workers`, or it does nothing.

### `cde.conversion.queue-capacity`

| | |
|---|---|
| Type | Integer |
| Default | `256` |
| Required | No |
| Secret | No |
| Environment variable | `CDE_CONVERSION_QUEUE_CAPACITY` |

How many jobs may be waiting, counted across all tenants — not per tenant,
which would make the real cap the product of this and the number of
organisations, raisable by registering more of them.

When it is full, submission is refused with `429` and a `Retry-After` rather
than queued. Refusing is the honest answer: accepting work the system cannot
get to produces a job that sits at PENDING until a restart fails it, which
reads as a bug to whoever submitted it rather than as back-pressure.

### `cde.conversion.conversion-timeout`

| | |
|---|---|
| Type | Duration |
| Default | `PT30M` |
| Required | No |
| Secret | No |
| Environment variable | `CDE_CONVERSION_CONVERSION_TIMEOUT` |

How long one conversion may take at the converter, separate from the fetch
timeouts because they bound different things. Generous, because the file being
converted may legitimately be enormous.

---

## Assisted summaries

Three checks decide whether anything reaches a model provider, and all three
must pass:

1. **The deployment tier permits outbound calls at all.** Government and
   Defence tiers do not, and no configuration here overrides that.
2. **`cde.security.deployment.ai-features` is `online-api`.**
3. **A provider and model are configured below.**

What is sent is built on the server from an allow-list of named fields.
Personal identifiers are replaced with stable placeholders and put back locally
on the reply; content carrying a classification marking is **refused outright**
rather than redacted. Every call is recorded in the audit trail — who, when,
which model and provider, whether redaction fired — and never the payload.

### `cde.ai.api-key`

| | |
|---|---|
| Type | String |
| Default | *(empty — the feature reports itself unavailable)* |
| Required | No |
| Secret | **Yes** |
| Environment variable | `CDE_AI_API_KEY`, falling back to `ANTHROPIC_API_KEY` |

An absent key means the feature is unavailable, not that the application
refuses to start: assisted summaries are optional and nothing else depends on
them.

### `cde.ai.model`

| | |
|---|---|
| Type | String |
| Default | *(none)* |
| Required | **Yes, when `cde.ai.api-key` is set** |
| Secret | No |
| Environment variable | `CDE_AI_MODEL` |

The model to call. There is deliberately no default: model identifiers carry
dated versions that go out of support, and one written into the application
would be wrong within a year and wrong *silently* — the provider would simply
begin refusing calls. Name the model this deployment has contracted for.

This used to be chosen by the browser, in a hard-coded string in the Angular
source, which meant the client decided what the deployment spent and on what.

### `cde.ai.endpoint`

| | |
|---|---|
| Type | String (URL) |
| Default | `https://api.anthropic.com/v1/messages` |
| Required | No |
| Secret | No |
| Environment variable | `CDE_AI_ENDPOINT` |

Point this at a self-hosted or in-region endpoint for local inference, which is
what makes the sovereign-deployment option a configuration decision rather than
a fork. Redirects are never followed: a provider that redirects is one whose
destination this deployment did not approve.

### `cde.ai.max-output-tokens`

| | |
|---|---|
| Type | Integer |
| Default | `1500` |
| Range | 1–8192 |
| Required | No |
| Secret | No |
| Environment variable | `CDE_AI_MAX_OUTPUT_TOKENS` |

Caps what a single call can cost. Enforced here rather than accepted from the
caller, because the caller does not pay for it.

### `cde.ai.timeout`

| | |
|---|---|
| Type | Duration (ISO-8601) |
| Default | `PT60S` |
| Required | No |
| Secret | No |
| Environment variable | `CDE_AI_TIMEOUT` |

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
- The `azure`, `s3` and `gcs` storage providers. The abstraction and the
  `local` provider are built and tested; the three cloud adapters are not, and
  selecting one fails at startup rather than silently falling back.
- Per-tenant AI kill switch. The deployment-level mode exists; the per-tenant
  override does not.

---

## Object storage

Which backend holds uploaded files, and how objects are addressed on it.
See `docs/adr/0011-storage-abstraction.md` for why this sits behind an
interface at all.

### `cde.storage.provider`

| | |
|---|---|
| Type | enum: `local`, `s3`, `azure`, `gcs` |
| Default | `local` |
| Required | no |
| Secret | no |
| Environment | `CDE_STORAGE_PROVIDER` |

**Only `local` is implemented.** Selecting any of the other three fails at
startup with a message saying so.

That is deliberate rather than an oversight left to bite someone. The cloud
adapters need the AWS, Azure and GCS SDKs, which the Spring Boot BOM does not
manage, so adding them means pinning versions — and §0.1 forbids choosing a
version without resolving it through Context7 first. Until that happens, a
deployment that asks for S3 and silently gets a local disk would be writing
customer data to a container filesystem that nothing backs up and that
disappears on restart. Failing at startup is the kinder outcome.

### `cde.storage.environment`

| | |
|---|---|
| Type | string, lowercase letters, digits and hyphens |
| Default | `local` |
| Required | yes |
| Secret | no |
| Environment | `CDE_STORAGE_ENVIRONMENT` |

The first segment of every storage key: `{environment}/{tenantId}/{category}/{objectId}`.

Its job is to stop a staging deployment pointed at a shared bucket from
writing over production objects. Cheap to set here, expensive to discover
afterwards.

### `cde.storage.local-root`

| | |
|---|---|
| Type | filesystem path |
| Default | `./storage` |
| Required | when the provider is `local` |
| Secret | no |
| Environment | `CDE_STORAGE_LOCAL_ROOT` |

Where the local provider keeps objects. Verified writable at startup by
writing and reading back a real file — checking only that the directory
exists would pass on a read-only mount, a full volume, and a path owned by
another user, which are the three configurations that actually fail.

**This volume must be encrypted at rest.** The local provider does not
encrypt, unlike the cloud backends which encrypt per object server-side. An
unencrypted volume means unencrypted customer data, and nothing in the
application will stop you.

### `cde.storage.signing-key`

| | |
|---|---|
| Type | string, at least 32 bytes; base64 or raw |
| Default | **none** |
| Required | when the provider is `local` |
| Secret | **yes** |
| Environment | `CDE_STORAGE_SIGNING_KEY` |

Signs the local provider's time-limited download URLs.

There is no default, and the application refuses to start without it. A
built-in signing key is a published signing key, and anyone holding it can
mint a valid download URL for any object belonging to any tenant. Generate
one with:

```
openssl rand -base64 32
```

Rotating it invalidates every outstanding download URL, which is the intended
behaviour after a suspected compromise. Outstanding URLs live at most fifteen
minutes, so a rotation is barely noticeable in normal operation.

### `cde.storage.download-base-uri`

| | |
|---|---|
| Type | absolute URI |
| Default | `http://localhost:8080` |
| Required | yes |
| Secret | no |
| Environment | `CDE_STORAGE_DOWNLOAD_BASE_URI` |

The origin that presigned URLs point at.

**In production this should be a different host from the application** — for
example `files.example.com`. A stored HTML or SVG file is active content, and
serving it from the application's own origin lets it execute against that
origin with the user's session (§5.13.8). A separate origin makes that
impossible rather than merely unlikely.

### `cde.storage.upload-dir`

| | |
|---|---|
| Type | filesystem path |
| Default | `./uploads` |
| Required | yes |
| Secret | no |
| Environment | — |

The pre-abstraction upload path, still used by the fifteen call sites that
have not yet moved to `StorageProvider`. It will be removed once they have.
Until then both settings matter, and both paths need to be on encrypted,
backed-up volumes.

---

## Multi-factor authentication

TOTP as a second factor, per RFC 6238. Off by default — see
`cde.mfa.secret-encryption-key` for why that is the safe default rather than a
timid one.

### `cde.mfa.enabled`

| | |
|---|---|
| Type | boolean |
| Default | `false` |
| Required | no |
| Secret | no |
| Environment | `CDE_MFA_ENABLED` |

Off unless a deployment has provisioned an encryption key. With it off, the
enrolment beans are not created and the endpoints do not exist.

The alternative — shipping a default key so the feature always works — would
make every deployment's second factors forgeable by anyone who reads this
repository. Starting without the feature is better than starting with a
worthless one.

### `cde.mfa.secret-encryption-key`

| | |
|---|---|
| Type | base64, decoding to exactly 32 bytes |
| Default | **none** |
| Required | when `cde.mfa.enabled` is true |
| Secret | **yes** |
| Environment | `CDE_MFA_SECRET_ENCRYPTION_KEY` |

AES-256-GCM key protecting TOTP secrets at rest.

A TOTP secret is a bearer credential with nothing to crack — unlike a password
hash, the plaintext *is* the credential — so a database dump holding them in
the clear is a dump of live second factors. Generate with:

```
openssl rand -base64 32
```

**Known gap.** §5.2 wants this key from a KMS, with envelope encryption and
per-tenant data keys so a tenant can be crypto-shredded on offboarding. That
is not built. Today one key protects every tenant's secrets, and rotating it
means re-encrypting all of them; there is no per-tenant shredding path.

### `cde.mfa.issuer`

| | |
|---|---|
| Type | string |
| Default | `CDE Platform` |
| Required | yes |
| Secret | no |
| Environment | `CDE_MFA_ISSUER` |

The name shown beside the account in the authenticator app, and the `issuer`
parameter of the `otpauth://` URI. Users commonly hold several accounts across
several products; without a distinct issuer their app shows a column of
identical usernames.

### `cde.mfa.algorithm`

| | |
|---|---|
| Type | enum: `SHA1`, `SHA256`, `SHA512` |
| Default | `SHA1` |
| Required | yes |
| Secret | no |
| Environment | `CDE_MFA_ALGORITHM` |

The HMAC algorithm behind the codes.

`SHA1` is the RFC 6238 default and the only value every authenticator app
supports — several ignore the `algorithm` parameter entirely and assume SHA-1,
which means enrolment appears to succeed and then every code is rejected.

This is not in tension with the SHA-1 ban in §4.1. That ban is on SHA-1 as a
collision-resistant hash; HMAC's security does not rest on collision
resistance, and HMAC-SHA-1 remains unbroken.

---

## The converter sidecar

The converter is a separate process reached over HTTP, so its settings are
environment variables on that container rather than Spring properties.

### `CONVERTER_PORT`

| | |
|---|---|
| Type | integer |
| Default | `5001` |
| Required | no |
| Secret | no |
| Environment | `CONVERTER_PORT` |

The port the converter listens on. `cde.converter.url` on the application side
must agree with it.

### `ODA_PATH`

| | |
|---|---|
| Type | filesystem path — a directory or the binary itself |
| Default | unset; `/opt/oda`, `/usr/bin` and `/usr/local/bin` are searched |
| Required | no |
| Secret | no |
| Environment | `ODA_PATH` |

Where the **ODA File Converter** is mounted. It converts DWG to DXF at higher
fidelity than the bundled LibreDWG and is tried first when present; unset, DWG
still works and falls back to LibreDWG.

**The binary is not in the image and cannot be**: its download is
registration-gated and its licence forbids redistribution, so it is supplied by
whoever deploys the service (see `/docs/licences.md`). Everything else it needs
*is* in the image — including the virtual display it opens even in console mode,
which the converter wraps it in automatically.

Point this at either the extracted directory or the executable; both resolve. A
mount at `/opt/oda` needs no variable at all.

**Check `odaRunnable`, not `odaInstalled`.** `/health` reports both because they
answer different questions, and the gap between them is where this goes wrong:

```json
{
  "odaInstalled": true,
  "odaPath": "/opt/oda/ODAFileConverter",
  "odaRunnable": false,
  "odaDetail": "present but cannot start: qt.qpa.xcb: could not connect to display"
}
```

A mount missing its shared libraries, or one that lost the execute bit, is
present and useless — and the symptom is not an error but DWG conversion quietly
staying at LibreDWG fidelity. The converter therefore runs ODA once at startup
against an empty directory and reports what happened, so a bad mount is caught
at deploy rather than months later in a drawing nobody can explain.
