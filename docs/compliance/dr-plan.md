# Disaster recovery plan

**Status: this document records the absence of a plan, not a plan.**

§5.8 sets RPO ≤ 15 minutes and RTO ≤ 4 hours. Nothing in this repository
backs anything up, and no restore has ever been performed. Publishing an
aspirational plan would be worse than publishing this, because an
aspirational plan gets cited in a bid and then relied on during an incident.

Last reviewed: 2026-08-27.

---

## What exists

**Migrations are forward-only and backwards-compatible**, verified by
`FlywayMigrationIntegrationTest`. A rollback to the previous application
version does not require a schema rollback, which is the property that makes
a bad deploy recoverable in minutes rather than hours.

**The application is stateless** apart from the filesystem the local storage
provider writes to. Any instance can be killed; sessions are JWT-based and
survive. This has not been verified by a pod-kill drill.

**Storage reachability is proved at startup**, so an instance that cannot
write never joins the load balancer.

That is the whole list.

## What does not exist

| Requirement | §5.8 | State |
|---|---|---|
| Continuous WAL archiving and PITR | Required | **Absent** |
| Daily full snapshots | Required | **Absent** |
| Cross-region replication inside the residency boundary | Required | **Absent** |
| Object storage versioning and soft delete | Required | **Absent** — the local provider has neither |
| Immutable backups (Object Lock) | Required | **Absent** |
| Monthly automated restore verification | Required | **Absent** |
| Region failover runbook | Required | **Absent** |
| Database failover runbook | Required | **Absent** |
| Corrupted-deploy rollback runbook | Required | Partial — migrations support it; nothing is written down |
| Credential compromise runbook | Required | **Absent** |
| Annual DR game day | Required | **Never held** |
| Tenant-level restore | Required | **Absent** |

## What this means concretely

**Current RPO: total.** A database loss loses everything since the deployment
was created. There is nothing to restore from.

**Current RTO: undefined.** Not "long" — undefined, because there is no
procedure whose duration could be estimated.

These are not degraded numbers against the targets. There is no backup, so
the targets are not approached from any distance.

## Why this document exists in this form

§5.8 says an untested backup is not a backup. The corollary is that an
untested plan is not a plan, and a DR plan is exactly the document most
likely to be written once, filed, and cited in a questionnaire years later by
someone who assumes it describes reality.

A SOC 2 Type II observation window would fail on this immediately: A1.2 and
A1.3 measure backup and recovery controls *operating*, and a control that has
never run cannot be observed operating.

## Order of work

1. **WAL archiving and PITR.** Highest value per unit of effort by a wide
   margin, and it is configuration rather than engineering. Until this exists
   every other item on the list is moot.
2. **Automated monthly restore into an isolated environment**, with data
   integrity verification, results retained as evidence. A backup nobody has
   restored is a belief.
3. **Object storage versioning and soft delete.** The local provider
   implements neither; §11 requires the capability to be exposed uniformly or
   clearly reported as unavailable, and today it is neither.
4. **Immutability (Object Lock)** so backups survive ransomware and insider
   deletion. A backup an attacker can delete is a backup for accidents only.
5. **Write the four runbooks**, then rehearse them. An unrehearsed runbook
   is discovered to be wrong at the worst possible time.
6. **Tenant-level restore**, which the tenant-prefixed storage layout (ADR 11)
   now makes possible for files. The database side still needs a design.
7. **DR game day**, findings tracked to closure.

Steps 1 and 2 together move this from "no recovery capability" to "a
recovery capability that has been demonstrated once", which is the largest
single improvement available anywhere in this compliance pack.
