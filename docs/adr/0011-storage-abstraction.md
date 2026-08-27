# 11. Put object storage behind one interface, with the local provider built first

- **Status:** Accepted
- **Date:** 2026-08-27
- **Related:** ADR 2 (tenant isolation), ADR 8 (out-of-process toolchain)

## Context

Files were written straight to the filesystem at
`{uploadDir}/{projectId}/{name}`, with `Paths.get(uploadDir, ...)` scattered
across fifteen classes.

Three problems, in increasing order of severity.

**The layout is keyed by project, not by tenant.** Project identifiers are
unique across the whole installation, so this does not currently leak — but
it means storage has no tenant boundary in it at all. Every rule about
tenant-scoped prefixes has nothing to attach to, and per-tenant offboarding,
quota accounting and crypto-shredding have no prefix to operate on.

**No abstraction.** §9.3 requires one artifact that deploys unchanged to
Azure, AWS, GCP and an air-gapped rack. Fifteen classes calling
`java.nio.file` directly is the opposite of that.

**Nothing verifies the storage works.** A read-only mount, a full volume and
a path owned by another user all start cleanly and fail on the first upload,
in front of a user, looking like a bug.

## Options

**Leave it and add cloud support later.** Cheapest now. "Later" means editing
fifteen call sites under time pressure, during a migration, when the cost of
a mistake is highest.

**Adopt a library abstraction** (Spring Content, Apache jclouds). Someone
else's interface, someone else's release cadence, and it would still need
wrapping to carry a tenant in the key — which is the part that matters most.

**Own interface, providers behind it.** More code to write and maintain, and
the interface has to be right the first time because every provider
implements it.

## Decision

`StorageProvider`, with the local filesystem provider implemented and the
three cloud providers deliberately absent.

Two design choices carry most of the weight:

**The key is a type, not a string.** `StorageKey` cannot be constructed
without a tenant, so there is no call site where the prefix can be forgotten.
Tenant-prefixing files is exactly the kind of rule that holds until the one
caller that forgets, and a forgotten prefix in storage is a cross-tenant leak
— the same severity as a missing `WHERE tenant_id`. Making the prefix part of
the type moves the question from a reviewer, who asks it sometimes, to the
compiler, which asks it every time.

Path traversal is refused by the same mechanism. Object identifiers must
match an allow-list pattern, so `../../etc/passwd` is rejected because it is
not in the permitted shape — not because it matched a list of known-bad
sequences, which is the kind of check that a new encoding gets past.

**Everything streams.** No method takes or returns a `byte[]`. A 2 GB
federated model must never touch application heap, and the reliable way to
guarantee that is to give callers no method that would let them.

Also decided:

- `verifyReachable()` writes and reads back a real file at startup and on the
  readiness probe. Checking that a directory exists would pass on all three
  of the configurations that actually fail.
- Presigned URLs are capped at 15 minutes, scoped to one object and one
  operation. The local provider signs its own tokens with HMAC-SHA-256 over a
  length-prefixed canonical message, compared in constant time.
- One contract test suite, which every provider must pass. That is what makes
  "no feature code knows which backend is active" true rather than aspirational.

## Consequences

- Tenant isolation reaches storage. `{env}/{tenantId}/{category}/{objectId}`
  gives offboarding, quota accounting and crypto-shredding a prefix to work
  on, and gives listing a boundary it cannot be widened past.
- 26 contract tests plus 30 key and token tests, all passing against the
  local provider. The digest assertions use published SHA-256 values rather
  than values computed by the code under test, so they check the code against
  the standard rather than against itself.
- **The cloud providers are not implemented**, and selecting one fails at
  startup with a message saying so. They need the AWS, Azure and GCS SDKs,
  which are not managed by the Spring Boot BOM — pinning versions for them
  would mean inventing version numbers, which §0.1 forbids without resolving
  them through Context7 first. Failing loudly beats falling back to local
  storage, which would silently write customer data to a container filesystem
  that nothing backs up and that disappears on restart.
- **The fifteen existing call sites are not migrated yet.** The abstraction
  exists and is tested; adoption is a separate change, because moving them in
  the same commit would mix a new subsystem with a wide refactor and make
  both harder to review. Until then the old path-based code and the new
  provider coexist, which is the honest state.
- The local provider does not encrypt. Encryption at rest is the volume's job
  here, unlike the cloud providers which encrypt per object server-side. An
  operator who mounts an unencrypted volume gets unencrypted customer data
  and nothing in the application stops them, so it is stated in the
  deployment documentation rather than assumed.
- `cde.storage.signing-key` is required with no default. A built-in signing
  key is a published one, and whoever holds it can mint a download URL for
  any object in any tenant.
