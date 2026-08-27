# 3. Run every test against real PostgreSQL, with no in-memory substitute

- **Status:** Accepted
- **Date:** 2026-08-27 (recording a decision taken earlier)
- **Depends on:** ADR 2

## Context

The suite originally ran against H2 with an opt-in PostgreSQL path. H2 is
fast, needs no daemon, and starts in milliseconds, which is why almost every
Spring project reaches for it.

It also does not implement Row-Level Security.

Since ADR 2 makes RLS *the* tenant isolation mechanism rather than a backstop
behind application filtering, a suite running on H2 was asserting that tenant
isolation worked while the mechanism under test did not exist in the database
it was testing against. The tests passed. They proved nothing.

## Options

**Keep H2 for speed, run PostgreSQL nightly.** Fast feedback most of the
time. But the security control we most need to verify would be unverified on
every pull request, and a nightly failure is discovered after the change has
already been reviewed and merged.

**Keep H2 with a PostgreSQL opt-in flag.** What existed. In practice the flag
is off, because the default is what runs, and a security gate that depends on
someone remembering a flag is not a gate.

**Testcontainers PostgreSQL for everything.** Requires a Docker daemon, adds
container startup to the suite, and fails hard where Docker is unavailable.

## Decision

Every test runs against a real PostgreSQL in Testcontainers, started once per
JVM. H2 is removed entirely — not deprecated, not fallback, removed.

A suite that silently degrades to a database without the security control is
worse than one that refuses to run, because the first reports success and the
second reports a missing prerequisite.

## Consequences

- RLS, dialect behaviour, locking semantics, `TIMESTAMPTZ` precision and
  `jsonb` normalisation are all exercised as they behave in production. Three
  real defects in the audit hash chain were found precisely because the test
  database was the real one.
- A Docker daemon is a hard prerequisite. The suite fails with a clear
  message rather than falling back.
- Container startup costs a few seconds per JVM, amortised across the whole
  suite by starting once per session rather than per class.
- Unit tests remain unaffected: no Spring context, no database, no network.
  This decision governs integration tests only.
- The same reasoning extends to every other substitutable dependency. When
  Valkey, Artemis, ClamAV, MinIO and Keycloak arrive they get real
  containers, not in-memory doubles, for the same reason.
