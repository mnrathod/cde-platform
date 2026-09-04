# Architecture

How the CDE platform is put together: a modular-monolith Spring Boot backend, an
Angular viewer, and native Android and iOS SDKs over one REST contract — with
tenant isolation enforced in PostgreSQL rather than in application code, and the
ISO 19650 information container as a first-class domain entity rather than a
status column.

> A formatted version of this document is published at
> <https://claude.ai/code/artifact/fffc4058-b5ce-4f13-b300-d87f2acfa9a8>.
> This file is the source of record; the two are kept in step.

| | |
|---|---|
| Backend | Spring Boot 4.1.1, Java 21 |
| Frontend | Angular 22, TypeScript strict |
| Mobile | Kotlin, Swift |
| Store | PostgreSQL, RLS enabled and forced |
| Endpoints | 73 operations across 57 paths |
| Migrations | V1 → V8 |
| Tests | 691 backend, 254 frontend |

---

## 1. Topology

Four processes, one contract. The Angular application, the Android SDK and the
iOS SDK are three clients of the same endpoints, described by a committed
OpenAPI 3.1 document that the build regenerates and compares on every run — so a
client cannot call an endpoint the specification does not describe, and the
specification cannot drift from the code.

```
Angular SPA   ─┐
Android SDK   ─┤
iOS SDK       ─┼─→  Apache  ─→  Spring Boot  ─┬─→  PostgreSQL   (RLS forced)
Your client   ─┘   TLS, brotli   modular       ├─→  Object store (local; S3/Azure/GCS by interface)
                   static assets  monolith     ├─→  Converter    (Python: DWG, Office, OCR)
                                               ├─→  ClamAV       (optional, out of process)
                                               ├╌→  Your storage (fetch of an integrator-minted link)
                                               └╌→  Model provider (commercial tier only)
```

The two dashed edges are the only ones that leave the deployment, and **both are
closed on government and Defence tiers** — the model provider by tier check at
startup and again on every call, the storage fetch by `cde.fetch.enabled`
defaulting to false. When fetching is off there is no outbound HTTP client, no
queue and no endpoint: absent rather than disabled, so there is no code path to
reach.

---

## 2. Backend structure

One deployable, divided by what things *are* rather than by what layer they sit
in. Splitting into services is a decision made when there is a scaling,
deployment-cadence or ownership reason for it, recorded in an ADR — not by
default.

| Package | Holds |
|---|---|
| `cde` | The ISO 19650 core: information containers, revisions, the state machine and its gated transitions, suitability codes. Built first, because everything that touches a document assumes it. |
| `tenancy` | Tenant context binding, the entity listener that stamps `tenant_id`, provisioning, and the login/invitation tenant resolvers. |
| `audit` | The append-only hash-chained trail, its recorder, and the read surface. |
| `security` | JWT issue and verify, the PBKDF2/Argon2 encoders, the role → permission map, and authentication throttling. |
| `mfa` | TOTP enrolment and verification, recovery codes, and AES-256-GCM field encryption for the secret. |
| `storage` | The provider interface, tenant-prefixed keys, and the local filesystem adapter with signed time-limited object tokens. |
| `upload` | Quarantine staging, content inspection, the malware-scanner port and its ClamAV adapter. |
| `fetch` | The SSRF destination policy and the bounded remote content fetcher — everything about reaching an address a caller chose. |
| `conversion` | The asynchronous conversion pipeline: job lifecycle, the fair work queue, the workers, and startup recovery. |
| `ai` | The outbound sanitiser, the provider adapter, and the assistance endpoints. |
| `invitation` | Registration outcomes and invitation issue, redemption and revocation. |
| `deployment` | The regulatory tier and what it permits — the ceilings a tenant administrator cannot raise. |
| `controller`, `service`, `repository`, `dto` | Documents, projects, annotations, versions, signatures, page manipulation, viewers and comparison. |
| `openapi`, `observability`, `health`, `exception` | The specification, request correlation, probes, and the RFC 9457 error envelope. |

**Where rules live.** Permission checks sit on the service, not the controller. A
rule enforced at the edge holds only for callers who come through that edge — a
scheduled job, a message consumer or next year's second controller each have to
remember it, and the cost of one that forgets is an unauthorised signature on the
contractual record.

---

## 3. Tenant isolation

Every tenant-scoped table has `tenant_id NOT NULL`, Row-Level Security **enabled
and forced**, and a policy reading `current_setting('app.tenant_id')`. The
application's database role is not the table owner and does not hold
`BYPASSRLS`, so the policy applies to it the same way it applies to anything
else.

`app.tenant_id` is set from the authenticated principal in one central
interceptor and reset when the connection returns to the pool. No repository
method filters tenancy by hand: a forgotten `WHERE tenant_id = ?` has to be
*harmless*, which it only is if the database is the thing enforcing the rule.

### A control can be perfect and vacuous

Registration used to put every new account into the deployment's shared default
tenant. RLS was enforcing correctly the entire time and the isolation suite was
green throughout — both true, and neither helped, because there was nothing left
to separate.

The lesson is in the test design: the cross-tenant suite now proves the boundary
by **driving two accounts** and checking each gets 404 for the other's objects,
rather than by reading `tenant_id` columns.

### Two places the boundary is broken deliberately

Signing in and redeeming an invitation both need to read a row in order to
discover which tenant to bind — a circularity. Each is resolved by a
`SECURITY DEFINER` function with a fixed `search_path` that returns exactly one
`BIGINT` and never row data. The caller learns which tenant a credential it
already holds belongs to, and nothing else.

### Background work carries its own context

A worker thread has no request to inherit a tenant from. Every conversion worker
establishes context explicitly before touching data, and a thread that does not
reads *nothing* — the policy matches no rows when `app.tenant_id` is unset. That
is the safe direction, and it is why startup recovery sweeps per tenant rather
than issuing one query across the table: a query written to see every row would
see every row.

---

## 4. The CDE state machine

```
Work in progress ──check──→ Shared ──authorise──→ Published ──supersede──→ Archived
      ↑                        │                                            (terminal)
      └────────reject──────────┘
```

State is never assigned; it is reached by a named domain operation carrying its
own permission, validation, approval record and audit event. There is no
`setState`, and there is no code path that can edit or delete a published
revision — tests assert that.

Publishing is the only state that freezes content. A change to a published
revision issues a *new* revision that supersedes it; the previous one is
archived and stays retrievable, with lineage written in both directions so it
can be walked from either end.

### Permissions are an axis, not a ladder

An engineer originates information and issues it for coordination. A reviewer
authorises it or sends it back. Neither is a superset of the other. Managing who
is *in* the organisation is a third axis again — `tenant.user:manage` — which is
why gaining `container:write` does not let anyone invite people.

`document:convert` is a fourth. It is deliberately not folded into
`container:write`: an integration credential belonging to a host application
submits links and collects PDFs, and has no business creating information
containers or moving them through the state machine. Folding it in would make
authority over the contractual record the price of converting a drawing.

| Role | read | write | share | publish | reject | archive | convert | user:manage | audit:read |
|---|---|---|---|---|---|---|---|---|---|
| Admin | ● | ● | ● | ● | ● | ● | ● | ● | ● |
| Engineer | ● | ● | ● | — | — | ● | ● | — | — |
| Reviewer | ● | — | ● | ● | ● | ● | — | — | — |
| Viewer | ● | — | — | — | — | — | — | — | — |

---

## 5. Request lifecycle

1. **Correlation** — a `traceId` is adopted from the inbound header or
   generated, put into the logging context and the response header, and carried
   into every log line, error body and audit record for the request.
2. **Headers** — HSTS (over TLS only), a `default-src 'none'` content policy for
   the API and a style-only relaxation for the docs page, `nosniff`, referrer and
   permissions policies, cross-origin isolation, and `no-store`.
3. **Cross-origin** — closed by default. No CORS configuration is registered at
   all until a deployment names an origin in full; a wildcard is refused at
   startup.
4. **Token** — the bearer token is verified and the tenant it carries is bound to
   the connection for the rest of the request, then cleared. No endpoint takes a
   tenant as a parameter.
5. **Authorisation** — deny by default. A refusal is written to the audit trail
   with the method and path before the response is composed.
6. **Errors** — RFC 9457 problem documents, including from the filter chain.

Sign-in is throttled *before* the tenant lookup and before 600,000 rounds of
PBKDF2, because that work is precisely what an attacker wants the server to do.
Counters are kept per account and per source address independently.

---

## 6. Ingest and storage

Both upload routes stream to a quarantine directory that nothing serving project
files can reach. The file is inspected there and moved into place only once it
passes. Writing to the destination and checking afterwards leaves a window in
which the file is referenced, downloadable and unexamined, and that window is
the vulnerability.

- **Type comes from the bytes.** The file name and the client's `Content-Type`
  are both attacker-chosen, so neither decides admission. An allow-list governs,
  because a deny-list refuses the formats somebody thought of and accepts every
  one they did not.
- **Generic types have to be permitted.** DWG, RVT, IFC and STEP are exactly what
  detection reports as generic binary or plain text — so executables are caught
  by signature regardless of name, and the load-bearing check is whether the
  bytes are markup wearing a model's extension.
- **Scripted markup is refused, not cleaned.** A small SVG is stored inline and
  rendered as markup on the application's own origin, so a scripted one is
  stored cross-site scripting. A CAD export has no scripts in it.
- **Scanning is a port with a ClamAV adapter**, out of process over a socket — a
  licensing requirement, since ClamAV is GPLv2 and linking it would create a
  combined work.

Nothing is held whole in memory at any point.

**Storage is an interface.** `StorageProvider` takes and returns streams and
never a `byte[]`, so there is no method a caller could use to hold a two-gigabyte
model in heap. Keys are a type rather than a string, and a `StorageKey` cannot be
constructed without a tenant — tenant-prefixing is the kind of rule that holds
until the one call site that forgets, so the compiler asks at every call site
instead of a reviewer asking at some of them. Path traversal is impossible by
construction: object identifiers are server-generated and validated against an
allow-list pattern before a key exists at all.

The local filesystem adapter is the one implemented; S3, Azure Blob and GCS are
adapter work behind the same interface, which is what keeps the application
cloud-portable without feature code knowing which backend is active.

---

## 7. Fetching a link somebody else chose

ADR 12 makes the platform something a host application integrates with rather
than only something people log into. The host mints a short-lived link — a Graph
download URL, an S3 presigned GET, an Azure SAS, a GCS signed URL — and posts it;
we fetch it once. That is what collapses four storage platforms into one code
path without this product ever holding anybody's storage credentials.

It also means **a caller chooses an address this server then connects to**, which
is server-side request forgery in its textbook form. What makes that dangerous is
not the internet: it is that this server sits inside a network the caller cannot
otherwise reach. The cloud metadata endpoint at `169.254.169.254` hands out
instance credentials to anything asking from the instance. The database, the
converter and Actuator on the internal interface are each one HTTP request away
from a process that makes requests on request.

### The address is checked, never the string

A name is not a destination. `evil.example` can resolve to `127.0.0.1`, and a
check on the text of the host passes it. So every **resolved** address is
checked, and a name resolving to several is allowed only if all of them are —
which one the connection picks is not ours to choose.

Refused: loopback, link-local (so the metadata endpoint), RFC 1918, IPv6
unique-local `fc00::/7`, the unspecified address, and multicast. Schemes are an
allow-list, so `file:` — which would turn a network fetch into an arbitrary file
read — is refused by omission rather than by remembering to name it. Redirects
are not followed, because a redirect names a second destination no check has
seen.

A refusal never says which address a host resolved to. A caller who learns their
name reached `10.0.4.17` has been handed a network map one refusal at a time.

### The check is split across two moments, deliberately

| Where | What it decides | Why there |
|---|---|---|
| Submission | Scheme, host allow-list, and address rules for **literals** | Needs no DNS, so it stays inside the one-second budget. A submission naming `169.254.169.254` is refused while the caller is still on the phone. |
| Fetch | The resolved addresses of a host **name** | Needs a lookup. Doing it on the request thread would put a network round trip in a §7.1 budget and would report a momentarily unresolvable host as "not permitted" — sending an integrator to look for a fault in a link that is perfectly good. |

### The residual risk, stated rather than papered over

The policy resolves the host and the HTTP client resolves it again, so a name
whose DNS answer changes between the two is checked at one address and connected
to at another. That cannot be closed in application code: the JDK's client offers
no supported way to pin a connection to an already-validated address, and
connecting to a raw IP with a spoofed `Host` header breaks certificate
validation — a narrow hole traded for a wider one.

It is closed by the two controls OWASP A10 names alongside address validation:
the host allow-list (`cde.fetch.permitted-hosts`) and a filtered egress proxy.
**A deployment that sets neither is relying on the pre-check alone**, and should
know that.

### Three bounds, because they stop different things

Size is a **running total as bytes are written**, not a believed
`Content-Length` — a server that declares ten bytes and sends ten megabytes
passes every check made before the transfer. The declared length is checked too,
so the honest oversize case is refused without opening a file.

Connect and response timeouts cover a destination that never answers. A separate
**transfer deadline** covers one that answers promptly and then dribbles: a
response timeout is satisfied the moment headers arrive, so without it a
slow-loris response holds a thread and a disk allocation indefinitely.

A failed fetch leaves no partial file. A truncated download that stayed on disk
is one the next step could not tell from a document.

---

## 8. Asynchronous conversion

Converting a document is bulk work by definition — its cost scales with the file,
and a two-gigabyte federated model does not convert inside a second. So
submission returns `202` with a job to poll, and the work happens on a queue.
There is no synchronous variant and there should not be: an endpoint that
sometimes takes ten minutes is one every client eventually times out against.

```
POST /api/conversions ──→ [destination check] ──→ [PENDING row] ──→ [queue] ──→ 202 + Location
                                                                      │
                       ┌──────────────────────────────────────────────┘
                       ▼
            fetch ──→ admit ──→ convert ──→ store ──→ SUCCEEDED
            (§7)      (§6)      (sidecar)   (§6)
```

Each stage is one that already existed. Deciding what the bytes are, scanning
them and refusing active content is the same admission pipeline the upload
endpoint uses — a second implementation would only get differently wrong.

### The source URL is never stored

Every form of the link — Graph, S3 presigned, Azure SAS, GCS signed — carries its
authorisation in the query string, which makes the URL a bearer credential.
Persisting it would put that credential in the database, in every backup, in
every replica, and in front of anyone who can run a `SELECT`.

So it lives only in the executor's memory for the life of the fetch. What
survives on the job record is **the host, and never the query string**. The
in-memory carrier overrides `toString` to withhold it, because the commonest way
a secret reaches a log is an object interpolated into a message rather than a
deliberate statement about it.

The cost is that a job still in flight when the application restarts cannot be
retried. It is failed at startup with exactly that reason — which is barely a
cost, since a presigned URL typically expires within fifteen minutes and one that
waited through a restart would very likely have expired anyway. Recovery runs
*before* the workers start, because a worker started first could be part-way
through a fresh job when the sweep decided anything RUNNING was stale.

### Why the queue is in-process, and what that costs

This is a **deliberate departure** from the standing decision to route
asynchronous work through ActiveMQ Artemis, and it should be reviewed rather than
inherited.

A message carrying the source URL would put the credential in the broker's
journal on disk — the same objection that keeps it out of the database. The
alternatives were to encrypt it into a message (a key, a rotation story and a
test suite of its own, to protect something with a fifteen-minute life), or to
hold it in memory and accept that an interrupted job is failed rather than
resumed. The second is smaller and its failure mode is honest.

**What it costs:** execution is bound to the instance that accepted the
submission, which is a departure from the stateless-instance rule. Job *status*
is not — that lives in the database, so any instance serves it — and neither is
the result, which is in object storage. What is lost is resuming in-flight work
elsewhere, which the un-persisted credential rules out regardless.

### Fairness is the queue's real job

A single FIFO would let one tenant submitting a hundred models fill every worker
while everyone else waits behind them. So the queue is one deque per tenant, a
rotation over the tenants that have work, and a per-tenant concurrency cap; a
tenant at its limit is skipped rather than blocking the rotation.

Capacity is counted **across** tenants, not per tenant — otherwise the real cap is
the product of the limit and the number of organisations, raisable by registering
more of them. A full queue is refused with `429` and a `Retry-After` rather than
accepted: a job that sits at PENDING until a restart fails it reads as a bug to
whoever submitted it, not as back-pressure.

### The job is a state machine too

`PENDING → RUNNING → SUCCEEDED | FAILED | CANCELLED`, with no setter for status.
Each move has something that must be true alongside it — a job that succeeded has
a result, one that failed has a reason, one that finished has a finish time — and
a `setStatus` lets a caller write half of that, leaving a row the API then has to
paper over. Check constraints repeat the invariants in the database, which is not
redundancy: the class governs the application, the constraints govern a migration
or a fix-up script.

Cancellation is co-operative, so the request and the outcome are separate facts
rather than one column trying to hold both. Progress never goes backwards and
stops at 99 until completion — a bar sitting at 100% while work continues has
stopped meaning anything.

---

## 9. Angular frontend

TypeScript in strict mode, standalone components throughout, and route-level
code splitting. The initial bundle is well inside the 250 KB budget.

| Path | What it is |
|---|---|
| `/login` | Sign in, or register — founding an organisation or joining an invited one. |
| `/projects` | The shell: projects, their documents, and upload. |
| `/viewer/:id` | PDF and CAD viewing with markup, redaction, signatures, page organiser, versions. |
| `/viewer3d/:id` | IFC and model viewing, with a navigable element tree beside the canvas. |
| `/compare` | Revision comparison and the assisted review report. |
| `/visual-compare` | Side-by-side and overlay comparison of rendered pages. |

**The viewer.** Rendering and markup are separate layers over the same viewport
transform. Keeping the transform in one place is what lets a markup drawn on a
phone land in the same position in the browser — the mobile SDKs implement the
same transform, and a parity test on each side checks they agree.

**The API origin is configurable.** One `HttpInterceptor` rewrites relative
`/api` paths against an injected base URL, so the application can be served from
somewhere other than the backend. It is an interceptor rather than a per-service
setting for a specific reason: one seam means a service added next year cannot
forget it.

**`src/viewer-core/` is being extracted.** Six services with no server dependency
— markup geometry, measurement, outline, drawing search, viewer state and the
pdf.js wrapper — now live outside the application, with a boundary spec that
fails the build if one of them imports app code. ADR 12 ships the viewer as its
own product; the value of that boundary is entirely in it staying true, and one
`inject(AuthService)` added in a hurry would turn a copy into an untangling that
nothing else in the build would notice.

**Live collaboration.** STOMP over WebSocket. The handshake is a plain `GET` with
no `Authorization` header — a browser cannot set one — so the session is
authenticated on the STOMP `CONNECT` frame instead.

---

## 10. Mobile SDKs

Native rather than cross-platform, because the hard parts are platform rendering
and secure storage: `PdfRenderer` and the Android Keystore on one side, `PDFKit`
and the Keychain on the other. Above those, the two SDKs are deliberately the
same shape.

| Concern | Android (Kotlin) | iOS (Swift) |
|---|---|---|
| Entry point | `CdeSdk` | `CdeSDK` |
| Transport | `net/CdeApi` | `Networking/CdeAPI` |
| Wire types | `model/Models` | `Models/Models` |
| Credential storage | `auth/TokenStore` | `Auth/TokenStore` |
| Page rendering | `render/PdfPageRenderer` | `UI/CdeViewerController` |
| Viewport transform | `ui/ViewportTransform` | `UI/CdeViewer` |
| Markup and measurement | `markup/MarkupEngine` | `Markup/MarkupEngine` |
| Offline cache and sync | `offline/OfflineStore`, `SyncEngine` | `Offline/OfflineStore`, `SyncEngine` |

**The contract is pinned by tests, not documentation.** Both SDKs carry a
wire-contract test built from payloads captured against a running server, decoded
through the SDK's own types. That is what caught the last drift: the SDKs
modelled a document list as a bare array while the API had moved to a page
envelope, so the field the client read for a total did not exist.

---

## 11. Data model

Flyway, forward-only, backwards-compatible. Hibernate runs with
`ddl-auto: validate`, so a mapping that has drifted from the migrations stops the
application at startup rather than surfacing as a missing column mid-request.

| Version | Introduces |
|---|---|
| V1 | Baseline: users, projects, documents, annotations and replies, versions, signatures. |
| V2 | Tenants, `tenant_id` across every table, RLS enabled and forced, the application role, the login tenant resolver. |
| V3 | Information containers, revisions, state transitions, suitability codes. |
| V4 | Invitations, with a hashed single-use token and its own tenant resolver. |
| V5 | The audit trail: append-only, hash-chained, `SELECT` and `INSERT` only. |
| V6 | TOTP enrolment and recovery codes; the secret is AES-256-GCM ciphertext, codes are SHA-256 digests. |
| V7 | Conversion jobs — with no column that can hold the source link, and a test that asserts it against `information_schema`. |
| V8 | Idempotency keys on conversion submission, unique per tenant where present. |

V8 is separate from V7 rather than folded into it because V7 had already shipped.
Migrations are forward-only: editing one that has run is how a checksum mismatch
stops every environment that already applied it.

---

## 12. Security controls

| Control | Enforced by | State |
|---|---|---|
| Tenant isolation | PostgreSQL RLS, forced, on every tenant table | Built |
| Password storage | PBKDF2-HMAC-SHA-256 at 600k iterations; Argon2id available; transparent rehash | Built |
| Authorisation | Permission checks on services; deny by default; refusals audited | Built |
| Audit trail | Append-only table, SHA-256 chain, grant-enforced immutability | Built |
| Sign-in throttling | Per account and per source, progressive delay | Built, per instance |
| Upload admission | Quarantine, byte-level detection, scanner port | Built |
| MFA / TOTP | Enrolment, replay-protected verification, hashed recovery codes | Built |
| Outbound fetch | Scheme and host allow-lists, resolved-address rules, no redirects, bounded transfer | Built |
| Storage keys | Tenant carried by the key type; server-generated object ids | Built |
| Outbound AI | Allow-list construction, pseudonymisation, classification refusal, tier gate | Built |
| Browser hardening | CSP, HSTS, referrer, permissions, cross-origin isolation | Built |
| WebAuthn / passkeys | — | Not built |
| SAML / OIDC / SCIM | — | Not built |
| Field encryption beyond TOTP secrets | — | Not built |
| KMS-held keys, envelope encryption, crypto-shredding | Key comes from configuration | Not built |
| S3 / Azure / GCS storage adapters | Interface exists; local adapter only | Not built |

---

## 13. Deployment

A multi-stage Docker build producing a JRE-only runtime image running as a
non-root user. Kubernetes manifests cover deployment, service, ingress, config,
secrets, autoscaling, network policy and persistent volumes; Docker Compose
covers a local or small on-premises stack. The same image runs everywhere and
differs only by environment variables. A declarative `Jenkinsfile` holds the
gates as pipeline-as-code.

| Tier | Password expiry | Admin may set | Outbound calls |
|---|---|---|---|
| Commercial | 90 days | 30–365 | Permitted |
| Government (IRAP-scoped) | 90 days | 30–90 | **Prohibited** |
| Defence (UK MOD / Australian Defence) | By contract | Not at all | **Prohibited** |

A government or Defence deployment configured to call a third party *fails to
start*. Checked at boot rather than at first use, so the misconfiguration
surfaces in front of whoever deployed it.

**Health.** Liveness and readiness are separate probes answering different
questions. Readiness fails for a required dependency and deliberately not for an
optional one, because otherwise a degraded converter takes the whole fleet out of
the load balancer.

---

## 14. What this architecture does not yet have

Stated plainly because a document that only describes what exists is read as a
claim that nothing else is needed.

- **One storage adapter, not four.** The interface is built and the local
  filesystem implementation works; S3, Azure Blob and GCS are not written, so the
  cloud portability the interface exists to provide is not yet demonstrated.
- **No message broker.** Conversion runs on an in-process queue (§8, with the
  reasoning); every *other* kind of processing — thumbnails, exports, bulk
  permission changes, notification dispatch — still runs inside the request.
- **No cache.** Permission resolution, tenant configuration and rate-limit
  counters are per instance, which is what makes the throttle looser on a scaled
  deployment.
- **No SSO or SCIM.** Authentication is username and password with a bearer
  token, plus TOTP as a second factor. No SAML, no OIDC, no WebAuthn.
- **No API keys and no OAuth client credentials.** A machine integration signs in
  with a username and password and holds a 24-hour token.
- **No webhooks.** Nothing calls out to a host application; integrations poll.
- **Key management is configuration, not a KMS.** One key protects every tenant's
  encrypted fields, and rotating it means re-encrypting all of them. There is no
  per-tenant crypto-shredding path.
- **No 3D/IFC accessible equivalent.** A WebGL canvas cannot be made conformant
  on its own, and the navigable tree beside it was not built to be the equivalent
  route the accessibility target requires. This is a procurement gate, not a
  backlog item.
- **`dwg2dxf` is GPL-3.0 with no corresponding-source offer.** Running the
  converter is fine; shipping the image to a customer is distribution, and that
  is a breach today. It decides whether DWG support can exist in a distributed
  artifact at all.

---

Companion document: [Integration guide](integration-guide.md) — how to drive this
API from an external application.
