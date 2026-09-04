# Integration guide

Driving projects, drawings, models, markup and the ISO 19650 approval workflow
from your own application — over REST, through the Android and iOS SDKs, or by
embedding the viewer. If your documents live in another system entirely, §5 is
the path built for that: you keep them, and hand us a link. Including the things
the API does not do yet, so you find out here rather than halfway through.

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
| Routes | 57 paths, 73 operations |

---

## 1. Choose an approach

| Approach | Use it when | You own |
|---|---|---|
| **Convert from your own storage** | Your documents live in SharePoint, S3, Azure Blob or Google Cloud Storage and you want them rendered or converted **without moving them into this platform first**. | Minting a short-lived link to each file. We never hold your storage credentials. See §5 — this is the path built for external systems. |
| **REST directly** | Your application has its own interface, or you are moving documents between systems — a scheduled export, a drawing-register sync, an ERP integration. | Everything above HTTP. The specification generates a typed client in whatever language you use. |
| **Mobile SDK** | You are building an Android or iOS application and want viewing, markup and offline working without implementing PDF rendering and a sync engine. | Your screens. The SDK owns transport, caching, conflict handling and markup geometry. |
| **Embed the viewer** | You want the drawing and model viewer inside an existing web application. | The surrounding page and the session. See §10 — this one has real constraints. |

**Generate a client, don't hand-write one.** The specification is regenerated
from the code on every build and compared against the committed copy, so it
cannot drift. Generating from it is both less work and the only way to be sure
you are calling something that exists.

---

## 2. Getting a token

Authentication is a username and password exchanged for a bearer token. There
are **no API keys and no OAuth client-credentials flow** — see §14 before
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

## 5. Converting a document from your own storage

**This is the path built for integrating an external system.** Your documents
stay where they are. You mint a short-lived link to one, post the link, and poll
a job until the converted PDF is ready.

Nothing about your storage platform reaches us. There is no connector to
configure, no OAuth consent to grant, no service principal to create, and **no
credential of yours is ever held by this product** — which is what lets one code
path serve SharePoint, S3, Azure Blob and Google Cloud Storage identically.

Available only where the deployment has `cde.fetch.enabled` set. Government and
Defence deployments make no outbound calls at all, so there the endpoints do not
exist — see §13.

### The shape of it

```
  your system                          this platform
  ───────────                          ─────────────
  mint a link ─────────────────────→   POST /api/conversions        202 + Location
  (15 min, read-only, one file)                │
                                               ├─ fetch it, once
                                               ├─ inspect and scan the bytes
                                               ├─ convert
                                               └─ store the result
  poll ────────────────────────────→   GET  /api/conversions/{id}   status, progress
  collect ─────────────────────────→   GET  /api/conversions/{id}/content
```

### Minting the link

Whatever your platform calls it. Each of these is a URL that carries its own
authorisation and expires on its own:

| Platform | What to mint |
|---|---|
| SharePoint / OneDrive | A Microsoft Graph `@microsoft.graph.downloadUrl`, or a scoped sharing link |
| Amazon S3 | A presigned `GET` URL |
| Azure Blob Storage | A service SAS with read permission on the single blob |
| Google Cloud Storage | A V4 signed URL for `GET` |
| Your own service | Any HTTPS URL we can fetch with no credentials of our own |

Make it **short-lived, read-only and scoped to one object**. We fetch it once and
never store it; if it expires before the job runs, the job fails and you submit
again with a fresh one.

### Submitting

```bash
curl -sS -X POST "$BASE/api/conversions" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "sourceUrl": "https://yourtenant.sharepoint.example/…/site-plan.dwg?token=…",
    "targetFormat": "PDF"
  }'
```

```http
HTTP/1.1 202 Accepted
Location: /api/conversions/3f2a71c4-9b0e-4a2d-8c11-5e7d9a1b3f40
```

```json
{
  "jobId": "3f2a71c4-9b0e-4a2d-8c11-5e7d9a1b3f40",
  "status": "PENDING",
  "progressPercent": 0,
  "sourceHost": "yourtenant.sharepoint.example",
  "sourceFileName": "",
  "targetFormat": "PDF",
  "resultSizeBytes": null,
  "failureReason": null,
  "cancellationRequested": false,
  "createdAt": "2026-09-04T09:12:33",
  "startedAt": null,
  "finishedAt": null
}
```

**The reply carries the host and never the link.** That is deliberate: your
presigned URL is a bearer credential, and echoing it back would put it in *your*
logs as well as denying us the ability to say we never stored it.

Requires the `document:convert` permission — held by Engineer and Admin, and
deliberately not by Reviewer or Viewer.

### Always send an Idempotency-Key

A `202` invites a client to retry on timeout, and retrying without a key means
paying for the same conversion twice and holding two job ids for one intent.

Repeating a submission with the same key returns **the job created the first
time**, whatever the body now says. The key is checked before the link is: a
presigned URL expires, so re-checking it on a retry would refuse work that is
already running. Keys are unique per organisation, so yours cannot collide with
another customer's.

### Polling

```bash
curl -sS "$BASE/api/conversions/$JOB" -H "Authorization: Bearer $TOKEN"
```

| `status` | Means |
|---|---|
| `PENDING` | Queued, not started. |
| `RUNNING` | Being fetched, inspected or converted. `progressPercent` advances. |
| `SUCCEEDED` | Terminal. `resultSizeBytes` is set; collect the file. |
| `FAILED` | Terminal. `failureReason` says what to do. |
| `CANCELLED` | Terminal. You asked it to stop. |

The three terminal states never change again. `progressPercent` moves forward
only and reaches 100 only on success — it will sit at 99 while the last stage
runs rather than claiming completion early.

Poll at a few seconds. There are no webhooks (§13), so polling is the only
mechanism; a job whose file is small typically finishes inside a second, and a
federated model can take minutes.

### Collecting the result

```bash
curl -sS "$BASE/api/conversions/$JOB/content" \
  -H "Authorization: Bearer $TOKEN" -o converted.pdf
```

Streams the PDF as an attachment. Available only once `status` is `SUCCEEDED`;
before that it answers `409` telling you the current status.

**No storage path is exposed anywhere in this API.** The object is reached
through this endpoint, which re-checks your permission and your organisation on
every request.

### Cancelling

```bash
curl -sS -X DELETE "$BASE/api/conversions/$JOB" -H "Authorization: Bearer $TOKEN"
```

Cancellation is co-operative: the worker notices between stages, so the job may
briefly still report `RUNNING` with `cancellationRequested: true`. Safe to call
on a job that has already finished — that answers with its final state rather
than an error, because racing a job to completion is normal rather than a
mistake.

### What links are refused, and why

The URL you send is an address this server then connects to, so it is checked
before anything is opened. A refusal is `422` with a `detail` you can act on.

| Refused | Because |
|---|---|
| `http://` (unless the deployment opted out) | A presigned link carries its authorisation in the query string; plain HTTP hands it to anyone on the path. |
| `file:`, `ftp:`, `gopher:`, anything else | Schemes are an allow-list. `file:` would turn a fetch into an arbitrary file read. |
| An address inside our network — loopback, `169.254.169.254`, RFC 1918, IPv6 `fc00::/7` | This server can reach things you cannot. The metadata endpoint hands out instance credentials to anything asking from the instance. |
| A host not on the deployment's allow-list, where one is configured | Ask the operator to add your storage host. |
| A redirect | A redirect names a second destination nothing has checked. Give us the final URL. |

The address is checked, **not the text of the host** — a name that resolves to a
private address is refused when we resolve it, which happens at fetch time rather
than at submission. So a link can be accepted with `202` and still fail the job
for this reason; `failureReason` will say so.

A refusal never tells you which address a host resolved to. That is not
unhelpfulness — it would let a caller map an internal network one refusal at a
time.

### Sizes, timeouts and back-pressure

| Bound | Default | On breach |
|---|---|---|
| File size | 2 GB | `failureReason` names the limit; declared-oversize is refused before transfer |
| Connect / response | 10 s / 30 s | Job fails; the link or host was unreachable |
| Whole transfer | 30 min | Job fails — this catches a server that answers promptly then stalls |
| Conversion | 30 min | Job fails |
| Queue depth | 256 across all organisations | `429` on submission with `Retry-After` |
| Concurrent per organisation | 2 | Your jobs queue behind each other; other customers are not blocked, and neither are you by them |

A `429` means the queue is full, not that anything is broken. Wait for
`Retry-After` and submit again. Submitting is refused rather than queued
deliberately: a job accepted into a queue the system cannot reach would sit at
`PENDING` until a restart failed it, which reads as a bug rather than as
back-pressure.

### What fails, and what to do

| `failureReason` says | Do |
|---|---|
| The storage service answered 403 | The link expired. Mint a fresh one and submit again. |
| The host could not be resolved | Check the URL. Transient DNS — retry. |
| The transfer did not finish within… | The far end stalled. Retry; if it repeats, the file may be too large for the deployment's window. |
| Larger than this deployment accepts | Nothing to retry. The file is over the limit. |
| The file was refused: … | Admission rejected it — wrong bytes for the name, scripted markup, or a malware signature. |
| That file could not be converted to PDF | The converter refused the format. See below. |
| Interrupted by a restart and cannot be resumed | Submit again with a fresh link. The link is never stored, so it cannot be retried for you. |

**Formats.** PDF, Office documents, images and DWG/DXF convert. **IFC and other
3D models do not convert to PDF** — there is no defined output, and the job fails
saying so rather than producing something misleading. Use
`GET /api/viewer3d/{documentId}/tree` for models.

### One thing to decide before you build on this

A conversion produces a **copy**. This platform does not know when you replace
the source in your own system, and it will not tell you — there are no webhooks.
If your workflow depends on the converted PDF matching the current source, hold
the mapping and the invalidation on your side.

---

## 6. Uploading files

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

## 7. The approval workflow

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

## 8. Markup, comments and signatures

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

## 9. Mobile SDKs

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

## 10. Embedding the viewer

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

## 11. Errors and limits

| Status | Means | Do |
|---|---|---|
| `401` | No token, or it expired. | Sign in again. No refresh. |
| `403` | Authenticated, but the role lacks the permission. | Do not retry. Publishing and originating are different permissions. |
| `404` | No such object, *or* it belongs to another organisation. | Do not retry, and do not infer existence. |
| `409` | A conflict — identity in use, revision already superseded. | Do not retry unchanged. |
| `422` | Validation, or a domain rule refused. Check `invalidFields`. | Fix the request. On upload, may be retryable. |
| `429` | Too many attempts, or the conversion queue is full. | Wait for `Retry-After`. Never zero seconds. |
| `503` | A dependency is unavailable. | Retry with backoff. The request was fine. |

On conversion specifically, `202` means the *submission* was accepted and says
nothing about the outcome. A job that fails does so with `status: "FAILED"` and a
`failureReason`, not with an HTTP error — the HTTP exchange succeeded. Poll for
the terminal state rather than treating the `202` as success.

**Throttling.** Failed sign-ins are delayed progressively, counted per account
*and* per source address. A service integration that retries a wrong password in
a tight loop will throttle itself within a few attempts. Treat `401` on login as
terminal until a human changes something.

---

## 12. Reading the audit trail

`GET /api/audit-events` returns your organisation's security-relevant events,
newest first, filterable by action. Requires `tenant.audit:read`.

Each record carries the SHA-256 of the record before it, and sequence numbers are
contiguous — so an alteration breaks every hash after it and a removal leaves a
gap. **Both hashes are in the response deliberately**: if you are streaming into
your own SIEM you can verify the chain yourself.
`GET /api/audit-events/verification` does the same check server-side.

Records never carry a credential, a request body, or raw personal data.

---

## 13. Before you go live

- **Which deployment tier?** Government and Defence deployments make *no outbound
  calls at all* — assisted-summary endpoints report themselves unavailable, and
  **the conversion endpoints do not exist**. If your integration is built on §5,
  confirm the tier before you design around it.
- **Is fetching enabled, and is your storage host allow-listed?** `/api/conversions`
  returns 404 where fetching is off, and a `422` naming your host where the
  deployment keeps an allow-list you are not on. Both are operator settings, not
  bugs.
- **Is your origin allowed?** Cross-origin access is closed until an origin is
  named in full. The most common first-day failure.
- **Is scanning required?** If so, uploads *and conversions* depend on the scanner
  being reachable.
- **Does the account have the right role?** Permissions are an axis, not a ladder.
  An integration that originates, publishes and invites needs an administrator;
  one that only converts needs `document:convert`, which Engineer already has.
- **Are you sending an `Idempotency-Key` on every conversion?** Without it a
  timed-out retry converts the file twice.
- **Are suitability codes populated?** None ship.
- **Have you generated a client from the specification?**

---

## 14. What the API does not do yet

| Missing | What to do instead |
|---|---|
| **API keys, OAuth client credentials** | A service integration signs in with a username and password and holds a 24-hour token. |
| **Refresh tokens** | Sign in again. No silent renewal. |
| **Webhooks** | No outbound callbacks — including on conversion jobs. Poll the job, subscribe to STOMP for live document events, or poll `/api/audit-events`. |
| **SSO — SAML, OIDC, SCIM** | Accounts are created by registration or invitation; provisioning is driven through `/api/invitations`. |
| **Asynchronous processing beyond conversion** | Conversion is a job (§5). Upload, page manipulation, redaction, OCR and signing all still run inside the request — set generous client timeouts on those. |
| **Storage connectors** | By design, not omission: you mint a link (§5) and we never hold your storage credentials. There is nothing to configure and nothing of yours to leak. |
| **Conversion to anything but PDF** | `targetFormat` accepts `PDF` only. IFC and other 3D models have no PDF output at all. |
| **Pre-signed download URLs** | Downloads stream through an authorising endpoint with your bearer token. |
| **Per-tenant quotas and usage metering** | No storage quota per organisation, no usage API. Conversion has a concurrency cap and a queue depth, but no billing meter. |

**Available, contrary to earlier versions of this guide:** TOTP multi-factor
authentication is built (enrolment, replay-protected verification, single-use
recovery codes), and asynchronous job submission exists for conversion. If you
planned around either being absent, re-check.

**The two that most often change an integration design** are *no webhooks* and
*no API keys*. If your architecture assumed either, decide now whether to poll
and to manage a service account's password, or to wait.

---

Companion document: [Architecture](architecture.md) — how the system behind this
API is put together.
