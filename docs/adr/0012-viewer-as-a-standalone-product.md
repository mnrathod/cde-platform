# 12. Ship the viewer as its own product, fed by integrator-minted URLs

- **Status:** Accepted
- **Date:** 2026-09-04
- **Related:** ADR 1 (Angular), ADR 8 (out-of-process toolchain), ADR 11 (storage abstraction)

## Context

The document viewer — PDF and CAD rendering, the markup layer, measurement,
redaction, signatures — was built as a feature of this platform. It is now to
be sold and deployed as a separate product that any common data environment
can embed, not only ours.

That changes one thing fundamentally: **the files it opens no longer belong to
us.** Today the viewer reads documents this platform stored, through
`StorageProvider`, under keys this platform generated (ADR 11). Embedded in
someone else's CDE, the bytes live wherever that customer keeps them —
SharePoint, Amazon S3, Azure Blob Storage, Google Cloud Storage, or a
filesystem on their own server.

So the product needs an answer to "how do the customer's bytes reach the
viewer", and that answer is a security boundary rather than a convenience.
Three properties make it hard:

**Conversion is server-side and cannot move.** Office documents go through
LibreOffice, DWG through LibreDWG, OCR through Tesseract — out of process, in
the converter image, deliberately (ADR 8). The bytes must therefore reach our
infrastructure. A design where the browser fetches directly from the
customer's storage and we never see the file cannot render a `.docx` at all.

**Anything that fetches a caller-supplied location is an SSRF engine.**
§5.12 A10 exists for this. A URL parameter that our server dereferences will
be pointed at `169.254.169.254`, at `10.0.0.0/8`, and at `.internal` names, by
someone, on purpose.

**Holding a customer's cloud credentials makes us a custodian of them.**
Storing a SharePoint refresh token or an S3 access key for every tenant means
encrypted-at-rest credential storage (§5.2), rotation, revocation, per-platform
admin consent, and a breach blast radius that includes the customer's entire
document store rather than the documents they asked us to render.

## Options

**A. The integrator mints a short-lived URL and passes it in.** The host CDE
already has credentials for its own storage. It generates a time-limited,
single-object link — a Microsoft Graph download URL, an S3 presigned GET, an
Azure blob SAS, a GCS signed URL — and hands that to the viewer, which fetches
it once and converts.

**B. The integrator POSTs the bytes.** No outbound fetch at all, so no SSRF
surface whatsoever. But the host has to stream every byte through itself, and
§6.7.4 expects 2 GB models.

**C. Connector model — we hold per-tenant credentials.** OAuth to SharePoint,
keys for the three clouds. Enables browsing, listing and save-back, which is a
materially better product.

**D. Browser fetches directly.** Best for residency: bytes never touch us.
Cannot render Office or CAD, per above.

## Decision

**Option A, with Option B retained as a supported fallback.**

The viewer accepts a short-lived URL that the integrating application
generated using its own credentials, fetches the object once, converts, and
discards it. It never holds a customer credential, and it never learns which
storage platform the URL points at — SharePoint, S3, Azure and GCS all reduce
to the same code path, which is the property that makes "integrates with any
CDE" true rather than aspirational.

Option B stays because some integrators cannot mint a URL — an air-gapped
deployment, or a store with no signing mechanism — and because a direct POST
is the simplest thing that can possibly work for a small file.

Option C is rejected for now, not forever. It is the better product and a
worse liability, and it is a decision to take deliberately with a credential
architecture designed for it, not to arrive at because it was convenient.

**The product moves to its own repository.** It ships on its own cadence, with
its own `LICENSE`, `NOTICE` and `THIRD-PARTY-NOTICES.txt`. A distributed
product sharing a repository with the platform means one attribution file
covering two different licence positions, which is exactly what procurement
audits and exactly what §17.2 requires us to get right.

## Consequences

**An SSRF allow-list is mandatory, not advisory.** Option A still dereferences
a URL a caller supplied. Every §5.12 A10 control applies: resolve the DNS name
and validate the resolved address, not the string; block loopback,
link-local — `169.254.169.254` specifically — and RFC 1918 ranges; refuse
redirects or re-validate every hop against the same rules; route egress
through a filtered proxy. A hostname allow-list per tenant is the stronger
form and should be offered.

**The fetch is bounded.** A URL is an unbounded input: size limit enforced
during the stream and not after (§7.7), a connect and read timeout, a cap on
redirects, and the content type decided by magic bytes rather than by
anything the response header claims (§5.13.3).

**Conversion stays asynchronous.** Unchanged by this decision — fetching adds
latency on top of a conversion that already exceeds the one-second budget, so
§7.1's `202` plus a job resource is the only shape that fits.

**LibreDWG blocks distribution today.** `docs/licences.md` §4.1 records an
open obligation: the converter image contains a `dwg2dxf` binary built from
GPL-3.0 source, and GPL-3.0 §6 requires a corresponding-source offer to
anyone receiving it. Inside a service we operate this is unresolved but
contained. **A product that customers deploy is distribution in the plainest
sense, so this must be closed before the first customer install** — by
publishing the source alongside the image, by a written offer, or by dropping
LibreDWG and requiring the ODA converter.

**Trademarks.** "Works with Microsoft SharePoint" is nominative fair use;
"Microsoft-approved", their logo, or any implication of partnership is not
(§17.4). The same applies to Amazon, Google and Autodesk. The integration
documentation is the place this will most easily go wrong.

**Extraction is not free.** The viewer currently reaches seven of this
platform's endpoints — annotations, document metadata, pages, and the page
manipulation operations — and reads auth and tenancy state from services it
does not own. Each becomes either a host-supplied callback or viewer-owned
state, and that inventory is the first task of the extraction.

**We keep two consumers of one viewer.** This platform becomes a customer of
the product on the same integration contract as anyone else. That is the
useful discipline: an API that is awkward for us will be awkward for them,
and we will find out first.
