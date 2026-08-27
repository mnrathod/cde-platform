# 2. Enforce tenant isolation in PostgreSQL, not in application code

- **Status:** Accepted
- **Date:** 2026-08-27 (recording a decision taken earlier)

## Context

A cross-tenant data leak is a company-ending event, and the guidelines treat
it as the highest-severity control there is. The question is not *whether*
to isolate tenants but *where the isolation lives* — because that determines
what a single forgotten line of code costs.

## Options

**Filter in the repository layer.** Every query carries
`WHERE tenant_id = ?`. Simple, obvious, no database features needed. Also
means every query is a place where isolation can be forgotten, and the
failure is silent: a missing predicate returns other tenants' rows and looks
exactly like a successful query. There is no test that proves the absence of
a forgotten filter across a codebase that keeps growing.

**Filter with a Hibernate filter or an AOP interceptor.** Centralises the
predicate so individual queries do not repeat it. Still application-level:
native queries, projections, `EntityManager` use, reporting paths, and
background jobs can all bypass it, and each bypass is silent in the same way.

**PostgreSQL Row-Level Security, enabled and forced.** Isolation moves below
the application entirely. Policies read `current_setting('app.tenant_id')`,
set once in a connection interceptor from the authenticated principal.

**Database-per-tenant or schema-per-tenant.** Strongest isolation, and the
right answer for some Defence and government contracts. Also the heaviest:
migrations multiply, connection pools multiply, and cross-tenant operational
work becomes N times the effort.

## Decision

PostgreSQL Row-Level Security, `ENABLE` **and** `FORCE`, on every tenant
table. The application role is not the table owner and does not hold
`BYPASSRLS`, so the policies apply to it without exception.

`app.tenant_id` is set from the authenticated principal in one central
interceptor and reset when the connection returns to the pool. It is never
read from a request parameter, header, or body.

Repository code does not filter tenancy by hand. RLS is not a second layer
behind application filtering — it is *the* layer, and a forgotten
`WHERE tenant_id = ?` must therefore be harmless rather than catastrophic.

Schema-per-tenant and database-per-tenant remain available as stronger tiers
for contracts that require them; the tenancy abstraction is shaped so they
do not require feature-code changes.

## Consequences

- The failure mode inverts. Forgetting the filter now returns *nothing*
  instead of returning *other tenants' data*. A test that expects rows and
  gets none fails loudly; a test that expects rows and silently gets too
  many passes.
- **Tests must run against real PostgreSQL.** H2 has no RLS, so a suite
  running on H2 would report tenant isolation working while the mechanism
  under test did not exist. See ADR 3.
- Background jobs and message consumers must establish tenant context
  explicitly before touching data. A job with no context can only reach
  system-level tables — which is the correct default.
- Every table needs `tenant_id NOT NULL`, indexed, and leading most
  composite indexes.
- A cross-tenant integration test is required for every resource type, and
  the suite is a CI gate. RLS being correct is a claim that has to be
  re-proved on every change, not assumed from the migration.
- Migrations must not forget to enable and force RLS on new tables. This is
  the one place the decision can still be undone silently, and it deserves
  a schema-level test.
