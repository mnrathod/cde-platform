# 10. Make the audit trail append-only and hash-chained in the database

- **Status:** Accepted
- **Date:** 2026-08-27

## Context

The audit trail is evidence. It is what a SOC 2 or ISO 27001 auditor reads,
what an incident investigation reconstructs events from, and what a customer
inspects after a security question. Evidence that the application could
rewrite is not evidence.

The threat is not only an external attacker. It includes a compromised
application process, and an insider with database access — both of whom would
want to edit the record of what they did.

## Options

**Ordinary table, application writes.** Cheapest. The application can `UPDATE`
and `DELETE`, so anything that owns the application owns the history.

**Ordinary table, `UPDATE`/`DELETE` revoked from the application role.**
Stops the application rewriting history. Does not stop anyone with direct
database access, and leaves no way to *detect* that it happened.

**Append-only plus a hash chain.** Each record includes the SHA-256 of its
predecessor, so altering any record invalidates every subsequent one and the
break is detectable by recomputation.

**Ship straight to an external SIEM.** Strong, but fire-and-forget delivery
loses records on failure, and it does not help a tenant admin reading their
own audit log in-product.

## Decision

Append-only table with `UPDATE` and `DELETE` revoked from the application
role, plus a SHA-256 hash chain per tenant, written in the same transaction
as the change it records. Periodic export to WORM storage, and scheduled
chain verification with an alert on any break.

Two details are load-bearing and were both established by testing against a
real database rather than by reasoning:

**The canonical encoding is length-prefixed, not delimiter-joined.** Joining
attacker-influenceable fields with a separator is forgeable: an actor can
choose a value containing the separator and produce the same canonical string
as a different record. Each field is written as `length:value;` instead.

**Timestamps are truncated to microseconds and JSON is canonicalised before
hashing.** PostgreSQL `TIMESTAMPTZ` stores microseconds, so a nanosecond-
precision Java timestamp hashes to one value on write and a different one on
read-back. `jsonb` stores a *parsed* value, reordering keys and rewriting
whitespace, so the JSON that comes back is not the JSON that went in. Both
broke the chain in testing; both would have been invisible against H2 (ADR 3).

Serialisation across concurrent writers uses `pg_advisory_xact_lock` on a key
composed from the tenant, with `FlushModeType.COMMIT` on the lock query so
that acquiring the lock does not trigger a JPA auto-flush and surface
constraint violations outside the repository proxy, where Spring's exception
translation no longer applies.

## Consequences

- Tampering is *detectable*, which is the achievable goal. Nothing prevents
  someone with sufficient database privilege from rewriting rows; the chain
  ensures they cannot do so without leaving a mathematically visible break.
- Verified by attempting the attack: raw `UPDATE` and `DELETE` against the
  table are refused, and the row survives. "The grant is revoked" is a claim
  about configuration; "the statement fails and the data is still there" is
  a test.
- Writing in the same transaction as the change means an audit failure rolls
  back the change. That is the correct trade: an unrecorded security-relevant
  action is worse than a failed one.
- The advisory lock serialises audit writes per tenant, which bounds audit
  write throughput per tenant. Acceptable at current volumes; it is the first
  thing to revisit if audit write latency appears in profiles.
- Never logged: passwords, tokens, session identifiers, MFA secrets, full
  payment data, raw PII bodies. Masked and referenced by identifier instead.
- Chain verification must actually be scheduled. A hash chain nobody
  recomputes provides detection in theory only.
