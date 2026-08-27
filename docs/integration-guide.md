# Integration guide

Driving projects, drawings, models, markup and the ISO 19650 approval workflow
from your own application — over REST, through the Android and iOS SDKs, or by
embedding the viewer. Including the things the API does not do yet, so you find
out here rather than halfway through.

> A formatted version of this document is published at
> <https://claude.ai/code/artifact/1b845ca9-1b8d-4c06-afe2-c537fda12910>.
> This file is the source of record; the two are kept in step.

| | |
|---|---|
| Base path | `/api` |
| Specification | `/api/openapi.yaml` (OpenAPI 3.1) |
| Interactive docs | `/api/docs` |
| Authentication | Bearer token |
| Errors | RFC 9457 problem documents |
| Routes | 54 |

---

## 1. Choose an approach

| Approach | Use it when | You own |
|---|---|---|
| **REST directly** | Your application has its own interface, or you are moving documents between systems — a scheduled export, a drawing-register sync, an ERP integration. | Everything above HTTP. The specification generates a typed client in whatever language you use. |
| **Mobile SDK** | You are building an Android or iOS application and want viewing, markup and offline working without implementing PDF rendering and a sync engine. | Your screens. The SDK owns transport, caching, conflict handling and markup geometry. |
| **Embed the viewer** | You want the drawing and model viewer inside an existing web application. | The surrounding page and the session. See §9 — this one has real constraints. |

**Generate a client, don't hand-write one.** The specification is regenerated
from the code on every build and compared against the committed copy, so it
cannot drift. Generating from it is both less work and the only way to be sure
you are calling something that exists.

---

## 2. Getting a token

Authentication is a username and password exchanged for a bearer token. There
are **no API keys and no OAuth client-credentials flow** — see §13 before
designing around it.

Registration takes one of two shapes, and which one you use decides *where the
account lands*. There is no third option: the API will not accept an
organisation identifier from an unauthenticated caller, because anything such an
endpoint accepts is something a stranger can assert about themselves.

### Found a new organisation

```bash
curl -X POST https://cde.example.com/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
    "username": "integration.service",
    "email": "integration@yourcompany.example",
    "password": "a-password-of-at-least-12-characters",
    "organisationName": "Your Company"
  }'

# 201 Created
# { "token": "eyJhbGciOi...", "username": "integration.service", "role": "ADMIN" }
```

The account gets an organisation of its own, containing nothing, and administers
it.

### Join an existing organisation

```bash
# Step 1 — an administrator issues an invitation. Requires tenant.user:manage.
curl -X POST https://cde.example.com/api/invitations \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{ "email": "integration@yourcompany.example", "role": "ENGINEER" }'

# Step 2 — redeem it. The email must match the one it was issued to, so a
# forwarded invitation does not admit whoever received it.
curl -X POST https://cde.example.com/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
    "username": "integration.service",
    "email": "integration@yourcompany.example",
    "password": "a-password-of-at-least-12-characters",
    "invitationToken": "cdeinv_..."
  }'
```

Both replies carry a token. Afterwards, `POST /api/auth/login` exchanges the
username and password for a new one.

**The invitation token is shown once.** It is stored as a SHA-256 hash and never
returned again — the same treatment an API key gets, and for the same reason: a
readable invitation table is a set of credentials for every pending account. A
lost invitation is reissued, not looked up.

---

## 3. The request contract

### The token carries the organisation

Send it as `Authorization: Bearer <token>`. **No endpoint takes an organisation
as a parameter** — the token carries it, and the database enforces the boundary
underneath. Asking for another organisation's object id returns `404`, not
`403`: a 403 would confirm the id exists somewhere.

Tokens last 24 hours by default. There is no refresh token — when one expires,
sign in again.

### Errors are problem documents

```json
{
  "type":      "https://cde.example.com/problems/invitation-not-usable",
  "title":     "Invitation cannot be used",
  "status":    422,
  "detail":    "That invitation is not valid for this email address, or it has expired or already been used. Ask for a new one.",
  "instance":  "/api/auth/register",
  "traceId":   "4f2c1ab99e0d4e7a8f3b6c1d2e5a7b90",
  "timestamp": "2026-08-27T09:15:04.123456Z",
  "invalidFields": [
    { "field": "password", "message": "must be at least 12 characters" }
  ]
}
```

Branch on `type`, not on the wording of `detail` — `detail` is written for a
person to read and will change. Surface `traceId` in your own error handling: it
ties the failure to the server's log lines and audit records for that exact
request.

### Listings are paged envelopes, not bare arrays

```json
{
  "content": [ /* … */ ],
  "number": 0,
  "size": 50,
  "totalElements": 138,
  "totalPages": 3,
  "first": true,
  "last": false
}
```

Read the total from `totalElements`. This exact shape once caught out our own
mobile SDKs — they modelled the reply as a bare array after the API had moved to
an envelope. If you are hand-writing a client, decode a real payload in a test
rather than trusting a shape you inferred.

---

## 4. First integration

```bash
BASE="https://cde.example.com"

# 1 — Sign in.
TOKEN=$(curl -sS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"integration.service","password":"…"}' | jq -r .token)

# 2 — Create a project. Phase is CONCEPT, DESIGN, CONSTRUCTION, HANDOVER or OPERATION.
PROJECT=$(curl -sS -X POST "$BASE/api/projects" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Riverside Depot","description":"Structural package","location":"Sector 7","phase":"DESIGN"}' \
  | jq -r .id)

# 3 — Upload a drawing. Streamed server-side.
DOC=$(curl -sS -X POST "$BASE/api/documents/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "projectId=$PROJECT" \
  -F "name=GA Plan — Level 02" \
  -F "documentType=DRAWING" \
  -F "revision=P01" \
  -F "drawingNumber=RVD-XX-02-DR-A-1200" \
  -F "file=@ga-plan-level-02.pdf" | jq -r .id)

# 4 — List what is in the project. Note the envelope.
curl -sS "$BASE/api/documents/project/$PROJECT?page=0&size=50" \
  -H "Authorization: Bearer $TOKEN" | jq '.totalElements, .content[0].name'

# 5 — Fetch what the viewer needs.
curl -sS "$BASE/api/viewer/$DOC" -H "Authorization: Bearer $TOKEN" | jq -r .type
```

`GET /api/viewer/{documentId}` answers with a discriminated shape — `pdf`,
`svg`, `image`, `ifc3d`, `model3d`, or an explanatory type when the format
cannot be rendered. Branch on `type` rather than on the file extension: a DWG
arrives as rendered SVG.

---

## 5. Uploading files

Two routes. `POST /api/documents/upload` takes a whole file in one multipart
request. `POST /api/documents/upload/chunk` takes a large one in pieces and
survives an interrupted connection — use it for anything model-sized. Both
stream server-side.

Every upload is written to quarantine, examined, and only then moved into place.
Plan for refusals — they arrive as `422` with a `detail` you can show the user:

- **The bytes are not what the name says.** Type is detected from content, not
  from the file name or the `Content-Type` you send.
- **Markup carrying a script or an event handler.** Refused rather than cleaned.
- **A malware signature**, where the deployment has a scanner configured.
- **Larger than the deployment accepts**, or beyond the chunk or staging limits.

**On a strict deployment, uploads can be refused wholesale.** A deployment set to
require scanning refuses *every* upload while its scanner is unreachable,
deliberately. Treat a `422` or `503` on upload as retryable rather than as a
permanent failure of that file.

---

## 6. The approval workflow

Documents and information containers are separate surfaces. `/api/documents` is
a file with metadata. `/api/cde` is the ISO 19650 model: a container whose
identity is separate from any revision of its content, moving through four
states by gated transition.

If your integration participates in a formal approval process, use the CDE
surface. If it is moving files, use documents.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/cde/projects/{projectId}/containers` | Create a container and its first revision. Needs `container:write`. |
| `GET` | `/api/cde/containers/{containerId}/revisions` | Every revision, with state and lineage. |
| `POST` | `/api/cde/revisions/{revisionId}/transitions` | Move a revision. The only way state changes. |
| `PUT` | `/api/cde/revisions/{revisionId}/suitability-code` | Assign a project-defined suitability code. |
| `GET` | `/api/cde/projects/{projectId}/suitability-codes` | The codes this project has defined. |

```bash
# Work in progress → Shared. Needs container:share.
curl -X POST "$BASE/api/cde/revisions/$REV/transitions" \
  -H "Authorization: Bearer $ENGINEER_TOKEN" -H 'Content-Type: application/json' \
  -d '{ "targetState": "SHARED", "reason": "Issued for coordination" }'

# Shared → Published. Needs container:publish — a DIFFERENT role.
curl -X POST "$BASE/api/cde/revisions/$REV/transitions" \
  -H "Authorization: Bearer $REVIEWER_TOKEN" -H 'Content-Type: application/json' \
  -d '{ "targetState": "PUBLISHED", "reason": "Authorised for construction" }'
```

A published revision is frozen. To change it, issue a new revision that
supersedes it — the previous one is archived and stays retrievable. There is no
edit and no delete.

**Suitability codes are yours to define.** The product ships none: organisations
customise them, and the standard's own code tables are copyrighted, so
reproducing them would be a licensing problem. Populate the list per project
before your integration starts assigning codes.

---

## 7. Markup, comments and signatures

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/annotations/document/{documentId}` | Every markup on a document. |
| `POST` | `/api/annotations` | Add one: type, page, shape geometry, comment. |
| `POST` | `/api/annotations/{id}/resolve` | Close a comment thread. |
| `POST` | `/api/annotations/{annotationId}/replies` | Reply, with `@mention` support. |
| `GET` | `/api/annotations/document/{documentId}/xfdf` | Export as XFDF for desktop PDF tools. |
| `POST` | `/api/signatures/document/{documentId}/sign` | Embed a PAdES signature. |
| `GET` | `/api/signatures/{signatureId}/verify` | Verify one. |

Markup geometry is in **document coordinates, not screen coordinates**. That is
what lets a markup drawn on a phone land in the same place in a browser at a
different zoom — and it means your client must apply the same viewport transform
rather than storing pixel positions.

**Live updates** arrive over STOMP on a WebSocket at `/ws`. The handshake carries
no `Authorization` header, so authenticate on the STOMP `CONNECT` frame instead,
sending the same bearer token.

---

## 8. Mobile SDKs

Both SDKs are deliberately the same shape. Everything that touches the network
suspends (Kotlin) or is `async` (Swift), and answers from the offline cache when
there is no connection.

```kotlin
val sdk = CdeSdk(configuration, storageDirectory)

val auth = sdk.signIn("j.okafor", password)
val projects = sdk.projects()
val page = sdk.documents(projectId = projects.first().id, page = 0, size = 50)
// page.totalElements — not page.size, and not the list length

when (val opened = sdk.open(documentId, prefetch = true)) {
  is CdeSdk.OpenedDocument.Pdf     -> sdk.renderer(opened.file)
  is CdeSdk.OpenedDocument.Drawing -> showSvg(opened.source)
  else -> showUnsupported()
}

sdk.addAnnotation(documentId, type, shapeData, comment, pageNumber)

// Queued while offline; reconciled on reconnect. Anything the server
// refused comes back so you can tell the user which.
val rejected = sdk.synchronise()
```

```swift
let sdk = CdeSDK(configuration: configuration, storageDirectory: directory)

let auth = try await sdk.signIn(username: "j.okafor", password: password)
let projects = try await sdk.projects()
let page = try await sdk.documents(projectId: projects[0].id, page: 0, size: 50)

let opened = try await sdk.open(documentId: id, prefetch: true)

await sdk.observeSyncState { state in updateBanner(state) }
let rejected = await sdk.synchronise()
```

**Credentials** go to the Android Keystore and the iOS Keychain, handled by the
SDK. `signOut(discardPendingChanges:)` clears the token along with anything
queued — signing out on a shared site tablet should not leave one person's
unsynced markup for the next.

**Cache.** `cacheSizeBytes()` and `trimCacheTo(limit)` are yours to call. The SDK
will not silently evict something a user is relying on offline, so decide the
policy your application wants.

---

## 9. Embedding the viewer

Three constraints decide whether this is straightforward or a project.

| Constraint | What it means |
|---|---|
| **Framing is refused** | The content policy sets `frame-ancestors 'none'`, so a cross-origin `<iframe>` will not render. Same-origin embedding works. |
| **Cross-origin is closed by default** | No CORS configuration is registered until a deployment names an origin in full. |
| **The session is a bearer token** | No session-cookie mode and no silent SSO. Your page must obtain a token, which means your authentication has to map to a CDE account. |

**The straightforward path** is to serve the Angular build from the same origin
as your own application, behind the same web tier that proxies `/api`.
Same-origin needs no CORS entry, no framing relaxation, and no cross-origin
token handling — and it is how the product is designed to be deployed.

**If you only need viewing**, consider not embedding the application at all:
`GET /api/viewer/{documentId}` returns rendered content and
`GET /api/viewer3d/{documentId}/tree` returns the model hierarchy. Rendering
those in your own interface avoids all three constraints.

---

## 10. Errors and limits

| Status | Means | Do |
|---|---|---|
| `401` | No token, or it expired. | Sign in again. No refresh. |
| `403` | Authenticated, but the role lacks the permission. | Do not retry. Publishing and originating are different permissions. |
| `404` | No such object, *or* it belongs to another organisation. | Do not retry, and do not infer existence. |
| `409` | A conflict — identity in use, revision already superseded. | Do not retry unchanged. |
| `422` | Validation, or a domain rule refused. Check `invalidFields`. | Fix the request. On upload, may be retryable. |
| `429` | Too many attempts. | Wait for `Retry-After`. Never zero seconds. |
| `503` | A dependency is unavailable. | Retry with backoff. The request was fine. |

**Throttling.** Failed sign-ins are delayed progressively, counted per account
*and* per source address. A service integration that retries a wrong password in
a tight loop will throttle itself within a few attempts. Treat `401` on login as
terminal until a human changes something.

---

## 11. Reading the audit trail

`GET /api/audit-events` returns your organisation's security-relevant events,
newest first, filterable by action. Requires `tenant.audit:read`.

Each record carries the SHA-256 of the record before it, and sequence numbers are
contiguous — so an alteration breaks every hash after it and a removal leaves a
gap. **Both hashes are in the response deliberately**: if you are streaming into
your own SIEM you can verify the chain yourself.
`GET /api/audit-events/verification` does the same check server-side.

Records never carry a credential, a request body, or raw personal data.

---

## 12. Before you go live

- **Which deployment tier?** Government and Defence deployments make *no outbound
  calls at all*, so assisted-summary endpoints report themselves unavailable.
- **Is your origin allowed?** Cross-origin access is closed until an origin is
  named in full. The most common first-day failure.
- **Is scanning required?** If so, uploads depend on the scanner being reachable.
- **Does the account have the right role?** Permissions are an axis, not a ladder.
  An integration that originates, publishes and invites needs an administrator.
- **Are suitability codes populated?** None ship.
- **Have you generated a client from the specification?**

---

## 13. What the API does not do yet

| Missing | What to do instead |
|---|---|
| **API keys, OAuth client credentials** | A service integration signs in with a username and password and holds a 24-hour token. |
| **Refresh tokens** | Sign in again. No silent renewal. |
| **Webhooks** | No outbound callbacks. Subscribe to STOMP for live events, or poll `/api/audit-events`. |
| **SSO — SAML, OIDC, SCIM** | Accounts are created by registration or invitation; provisioning is driven through `/api/invitations`. |
| **MFA** | Not available. Factor this into your risk assessment for an integration account with administrator rights. |
| **Asynchronous job submission** | Processing runs inside the request. Set generous client timeouts on upload and processing calls. |
| **Pre-signed download URLs** | Downloads stream through an authorising endpoint with your bearer token. |
| **Per-tenant quotas and usage metering** | No storage quota per organisation, no usage API. |

**The two that most often change an integration design** are *no webhooks* and
*no API keys*. If your architecture assumed either, decide now whether to poll
the audit trail and manage a service account's password, or to wait.

---

Companion document: [Architecture](architecture.md) — how the system behind this
API is put together.
