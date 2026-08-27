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
| Backend | Spring Boot 3, Java 21 |
| Frontend | Angular, TypeScript strict |
| Mobile | Kotlin, Swift |
| Store | PostgreSQL, RLS enabled and forced |
| Endpoints | 68 across 54 paths |
| Migrations | V1 → V5 |
| Tests | 429 backend, 249 frontend |

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
Your client   ─┘   TLS, brotli   modular       ├─→  Converter    (Python: DWG, Office, OCR)
                   static assets  monolith     ├─→  ClamAV       (optional, out of process)
                                               └╌→  Model provider (commercial tier only)
```

The dashed edge is the only one that leaves the deployment, and it is closed on
government and Defence tiers — checked at startup and again on every call.

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
| `upload` | Quarantine staging, content inspection, the malware-scanner port and its ClamAV adapter. |
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

| Role | read | write | share | publish | reject | archive | user:manage | audit:read |
|---|---|---|---|---|---|---|---|---|
| Admin | ● | ● | ● | ● | ● | ● | ● | ● |
| Engineer | ● | ● | ● | — | — | ● | — | — |
| Reviewer | ● | — | ● | ● | ● | ● | — | — |
| Viewer | ● | — | — | — | — | — | — | — |

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

---

## 7. Angular frontend

TypeScript in strict mode, standalone components throughout, and route-level
code splitting. The initial bundle is 103 KB compressed against a 250 KB budget.

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

**Live collaboration.** STOMP over WebSocket. The handshake is a plain `GET` with
no `Authorization` header — a browser cannot set one — so the session is
authenticated on the STOMP `CONNECT` frame instead.

---

## 8. Mobile SDKs

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

## 9. Data model

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

---

## 10. Security controls

| Control | Enforced by | State |
|---|---|---|
| Tenant isolation | PostgreSQL RLS, forced, on every tenant table | Built |
| Password storage | PBKDF2-HMAC-SHA-256 at 600k iterations; Argon2id available; transparent rehash | Built |
| Authorisation | Permission checks on services; deny by default; refusals audited | Built |
| Audit trail | Append-only table, SHA-256 chain, grant-enforced immutability | Built |
| Sign-in throttling | Per account and per source, progressive delay | Built, per instance |
| Upload admission | Quarantine, byte-level detection, scanner port | Built |
| Outbound AI | Allow-list construction, pseudonymisation, classification refusal, tier gate | Built |
| Browser hardening | CSP, HSTS, referrer, permissions, cross-origin isolation | Built |
| MFA / TOTP / WebAuthn | — | Not built |
| SAML / OIDC / SCIM | — | Not built |
| Encryption at rest | Deployment concern; no application-layer field encryption | Not built |
| Storage abstraction | Local filesystem only | Not built |

---

## 11. Deployment

A multi-stage Docker build producing a JRE-only runtime image running as a
non-root user. Kubernetes manifests cover deployment, service, ingress, config,
secrets, autoscaling, network policy and persistent volumes; Docker Compose
covers a local or small on-premises stack. The same image runs everywhere and
differs only by environment variables.

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

## 12. What this architecture does not yet have

Stated plainly because a document that only describes what exists is read as a
claim that nothing else is needed.

- **No storage abstraction.** Files go to a local volume. The four-provider
  interface that makes the platform cloud-portable is not built.
- **No message broker.** Document and model processing runs in the request rather
  than on a queue.
- **No cache.** Permission resolution, tenant configuration and rate-limit
  counters are per instance, which is what makes the throttle looser on a scaled
  deployment.
- **No MFA, SSO or SCIM.** Authentication is username and password with a bearer
  token.
- **No CI pipeline in the repository.** The gates exist and run — tests,
  specification drift, Spectral lint — but as commands rather than as a versioned
  pipeline.
- **No attribution file, licence register or ADR directory.** Procurement will ask
  for the first two.
- **Four backend files and sixteen Angular components exceed the size limits**,
  all in the viewer and document-processing surface.

---

Companion document: [Integration guide](integration-guide.md) — how to drive this
API from an external application.
