# CDE mobile API contract

The surface both SDKs bind to. Every endpoint and payload below was read from
a running server rather than from the source, so the field names are what the
wire actually carries.

Captured against `cde-platform` at `8bc8d2b`. Regenerate with
`mobile-sdk/tools/capture-contract.sh` after changing any DTO.

## Authentication

JWT bearer. The token is obtained once and sent on every later request as
`Authorization: Bearer <token>`.

```
POST /api/auth/login
     { "username": "...", "password": "..." }
  -> { "token": "...", "username": "...", "role": "ADMIN" }

POST /api/auth/register
     { "username": "...", "email": "...", "password": "..." }
```

`role` is one of `ADMIN`, `MANAGER`, `REVIEWER`, `VIEWER`. The server decides
permissions; the SDK uses the role only to hide controls that would be
rejected, never to grant anything.

## Projects and documents

```
GET  /api/projects                     -> ProjectResponse[]
GET  /api/documents/project/{id}       -> DocumentResponse[]
GET  /api/documents/{id}               -> DocumentResponse
POST /api/documents/upload             (multipart: file, name, projectId)
```

`ProjectResponse`

| field | type | note |
|---|---|---|
| `id` | number | |
| `name` | string | |
| `description` | string? | |
| `location` | string? | |
| `phase` | string? | `CONCEPT`, `DESIGN`, `CONSTRUCTION`, `HANDOVER`, `OPERATION` |
| `ownerUsername` | string? | the username, not the id |
| `documentCount` | number | computed by the server, not a stored column |

`DocumentResponse`

| field | type | note |
|---|---|---|
| `id` | number | |
| `name` | string | |
| `description` | string? | |
| `fileName` | string | |
| `fileType` | string | MIME |
| `fileSize` | number? | |
| `documentType` | enum | `DRAWING` `SPECIFICATION` `REPORT` `SCHEDULE` `BIM_MODEL` `POINT_CLOUD` `OTHER` |
| `status` | enum | `DRAFT` `IN_REVIEW` `APPROVED` `SUPERSEDED` `VOID` |
| `revision` | string? | |
| `drawingNumber` | string? | |
| `sheetNumber` | string? | |
| `projectId` | number | |
| `uploadedBy` | string | |
| `createdAt` / `updatedAt` | ISO-8601 local date-time, **no zone** | see below |

### Dates carry no time zone

The server serialises `LocalDateTime`, so `2026-08-19T00:58:55.793394585`
arrives with no offset and sub-second precision beyond what either platform
parses by default. Both SDKs parse these as *server local time* and convert
using the device zone only for display — treating them as UTC would shift
every timestamp by the viewer's offset.

## Opening a document

One call decides how a document is rendered. The response is polymorphic on
`type`, which is why the SDKs model it as a sealed type rather than a struct
with optional fields.

```
GET /api/viewer/{documentId}
```

For a PDF:

```json
{ "type": "pdf", "name": "spec", "fileName": "spec.pdf",
  "version": 1, "pdfUrl": "/api/viewer/2/pdf?v=1",
  "revision": "", "drawingNumber": "" }
```

For a converted CAD drawing:

```json
{ "type": "svg", "name": "Structural Plan Level 1",
  "content": "<svg xmlns=... viewBox=\"0 0 800 600\">...",
  "revision": "A", "drawingNumber": "CBE-ST-001" }
```

`type` is `pdf`, `svg`, `image`, or another value for a format with no viewer.

- **`pdf`** — fetch `pdfUrl` for the bytes and render natively
  (`PDFKit` on iOS, `android.graphics.pdf.PdfRenderer` on Android).
  `pdfUrl` is server-relative and carries `?v=` — the current version number,
  so a cached copy is invalidated by the URL changing rather than by a
  freshness check.
- **`svg`** — `content` is the whole drawing inline. There is no second
  request.
- anything else — the SDK reports the format as unviewable rather than
  showing an empty page.

```
GET /api/viewer/{documentId}/pdf     -> application/pdf bytes
```

## Annotations

```
GET    /api/annotations/document/{documentId}      -> AnnotationResponse[]
POST   /api/annotations                            -> AnnotationResponse
PUT    /api/annotations/{id}                       -> AnnotationResponse
PATCH  /api/annotations/{id}/resolve               -> AnnotationResponse
DELETE /api/annotations/{id}
GET    /api/annotations/document/{id}/xfdf         -> XFDF
POST   /api/annotations/document/{id}/xfdf         (multipart import)
```

`AnnotationRequest`

```json
{ "documentId": 2, "type": "MARKUP", "shapeData": "{...}",
  "comment": "…", "pageNumber": 1 }
```

`type` is `COMMENT` `MARKUP` `DIMENSION` `CLOUD` `ARROW` `STAMP` `HIGHLIGHT`
`UNDERLINE` `STRIKEOUT` `SQUIGGLY`.

`AnnotationResponse` — what comes back, which is not the same shape as what
goes out: the server adds an id, an author and a status the request never
carries.

| field | type | note |
|---|---|---|
| `id` | number | server-assigned; `shapeData.id` is the stable client-side one |
| `documentId` | number | |
| `author` | string? | username of whoever created it, not an id |
| `type` | string | as above |
| `shapeData` | string | JSON, stored verbatim and never parsed by the server |
| `comment` | string? | |
| `status` | string | `OPEN`, `RESOLVED`, `CLOSED` |
| `pageNumber` | number? | absent for markup on a drawing |
| `createdAt` | string | zone-less `LocalDateTime` — see above |
`status` is `OPEN` `RESOLVED` `CLOSED`.

### shapeData is opaque to the server

`shapeData` is a JSON **string**, stored and returned verbatim. The server
never parses it, so the mobile SDKs and the web viewer have to agree on its
shape by convention — there is no schema to fail against. Both SDKs use the
same field names the web viewer writes, so markup drawn on a phone opens in
the browser and the reverse:

```json
{ "id": "s-1712…-a1b2", "tool": "area", "pageNumber": 1,
  "color": "#FF0000", "strokeWidth": 2, "opacity": 0.15,
  "points": [{"x":100,"y":100},{"x":200,"y":100},{"x":200,"y":200}],
  "measurement": "15300 px²", "measurementDetail": "1051.5 px" }
```

Geometry is in the coordinate space of the page or drawing as rendered by
whoever created it, which is why `ShapeData` also carries the zoom-independent
values wherever a measurement is involved.

## Document processing

Available to mobile but not part of the drop-in viewer's controls; these
rewrite the document server-side and commit a new version.

```
POST /api/documents/{id}/ocr
POST /api/documents/{id}/redact
POST /api/viewer/{id}/flatten
GET  /api/documents/{id}/versions
POST /api/documents/{id}/versions/{n}/restore
```

## Errors

Handled uniformly by the server's exception handler. The SDKs map them to one
`CdeError` type so a caller does not switch on status codes:

| status | meaning |
|---|---|
| 400 | request rejected — body carries `message` |
| 401 | token missing, expired or invalid — SDK clears it and reports `unauthenticated` |
| 403 | authenticated but not permitted |
| 404 | no such document |
| 405 | wrong method |
| 503 | converter service unavailable — retryable |

A body of `{ "message": "…" }` is written by the server for display. Anything
else is reported generically; the SDKs never surface a raw status code or a
stack trace to a user.
