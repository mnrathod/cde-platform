# 5. Build the ISO 19650 CDE as core architecture, before the features that use it

- **Status:** Accepted
- **Date:** 2026-08-27 (recording a decision taken earlier)

## Context

ISO 19650 is in scope. Its Common Data Environment defines four states —
Work in Progress, Shared, Published, Archived — with controlled transitions
between them, immutable published revisions, and explicit supersession
lineage.

The tempting reading is that this is a compliance overlay: add a `status`
column to the document table, add a dropdown, ship it. That reading is
wrong, and the cost of discovering it is wrong later is what this ADR is
about.

## Options

**Status column on the document record.** Cheapest by far. A state becomes
an attribute anyone can write, so "published revisions are immutable" is a
convention rather than a property, and every future feature is another place
that can violate it. Visibility rules end up scattered across queries.

**Add the CDE later, once the document features exist.** Feels like sensible
sequencing. In practice it means migrating every document, every permission
check, and every audit record — because the container and its revision are
not decorations on a file, they are the thing permissions and audit attach
to.

**Build `platform-cde` first, as a first-class domain module.** Slowest
start. Everything document-touching then assumes containers and revisions
from its first line.

## Decision

Build `platform-cde` first. The information container and its revision
history are first-class domain entities, on the same footing as tenant and
user — not attributes of a file record.

The four states are a state machine, not a status field:

- Transitions are the **only** way state changes. No code path writes a
  state column directly. Each transition is a domain operation with its own
  permission, validation, approval record and audit event.
- Published revisions are immutable. A change produces a new revision that
  supersedes the previous one; the previous stays retrievable forever. There
  is no edit and no delete on a published container, and CI tests assert that
  no code path can perform either.
- Visibility composes with tenant isolation (ADR 2) and RBAC. All three must
  pass. Cross-team WIP leakage is treated at the same severity as a
  cross-tenant leak.
- Supersession is explicit in both directions, so full lineage is
  reconstructible.

## Consequences

- Document features cost more to build, because each must go through the
  transition API rather than writing state. That is the point: the constraint
  is what makes the guarantee real.
- The immutability guarantee is testable and tested, rather than asserted in
  documentation.
- Suitability codes, revision codes and container naming are **configurable
  and tenant-populated, never shipped**. The standard's own code tables are
  copyrighted, so reproducing them in the product would be a licence breach
  (see `docs/licences.md`). We ship the mechanism, the customer populates it.
- The archive state must stay immutable and retrievable for the operational
  life of the asset — decades. That implies WORM storage, scheduled integrity
  verification, and a format migration strategy, and it collides directly
  with GDPR erasure and per-tenant crypto-shredding. That collision is legal
  rather than technical and is unresolved; it is open item 3 in `CLAUDE.md`
  §18 and must be settled per contract before the archive model is finalised.
- CDE state transitions and audit events form a natural event stream with
  several downstream consumers, which is the most likely trigger for
  adopting Kafka later. ADR 6 keeps that option open without paying for it
  now.
