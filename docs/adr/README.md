# Architecture decision records

One file per significant decision, in the format `NNNN-title.md`, each
recording context, the options considered, the decision, and its
consequences.

An ADR is a record, not a proposal. It says what was decided and why, so that
someone arriving in two years can tell the difference between a deliberate
constraint and an accident — and can overturn it knowingly rather than by
mistake.

ADRs are immutable once accepted. A decision that changes gets a new ADR that
supersedes the old one; the old one stays, marked superseded. Editing history
loses the reasoning, which is the only part worth keeping.

## Index

| # | Decision | Status |
|---|---|---|
| [1](0001-angular-not-react.md) | Keep Angular rather than rewriting the frontend in React | Accepted |
| [2](0002-row-level-security-for-tenant-isolation.md) | Enforce tenant isolation in PostgreSQL, not in application code | Accepted |
| [3](0003-real-postgresql-in-tests.md) | Run every test against real PostgreSQL, with no in-memory substitute | Accepted |
| [4](0004-pbkdf2-for-password-storage.md) | Store passwords with PBKDF2-HMAC-SHA-256, Argon2id as an option | Accepted |
| [5](0005-cde-as-core-domain.md) | Build the ISO 19650 CDE as core architecture, before the features that use it | Accepted |
| [6](0006-artemis-only-no-kafka.md) | Run ActiveMQ Artemis alone; adopt Kafka only on a documented trigger | Accepted |
| [7](0007-code-first-openapi.md) | Generate the OpenAPI spec from the code, commit it, and gate on drift | Accepted |
| [8](0008-out-of-process-conversion-toolchain.md) | Run the conversion and scanning toolchain out of process | Accepted |
| [9](0009-bundle-third-party-javascript.md) | Bundle third-party JavaScript; never load executable code from a CDN | Accepted |
| [10](0010-hash-chained-audit-trail.md) | Make the audit trail append-only and hash-chained in the database | Accepted |
| [11](0011-storage-abstraction.md) | Put object storage behind one interface, with the local provider built first | Accepted |

## Decisions not yet recorded

These are settled in `CLAUDE.md` but not yet implemented, so there is nothing
to record consequences for. They get an ADR when they are built, because the
consequences worth writing down are the ones discovered during
implementation:

- Valkey for caching, and what is cached versus what is not
- MFA: TOTP, recovery codes, and the WebAuthn position
- SSO: SAML 2.0 and OIDC per-tenant configuration
- The deployment-tier ceiling model for password policy

## Open questions, deliberately unanswered

`CLAUDE.md` §18 lists four questions that engineering should not settle
alone. Two of them block architecture rather than merely informing it:

- **Required classification per contract** (OFFICIAL-SENSITIVE versus
  PROTECTED) determines IRAP scope, whether a FIPS-validated crypto module is
  contractually required, and whether external services — the Pwned Passwords
  API, any LLM provider — are permitted at all. The ISO 19650-5 sensitivity
  assessment and the access-by-clearance model both depend on it.
- **Retention versus erasure.** ISO 19650 archive retention runs for the
  operational life of the asset, measured in decades. GDPR Article 17 erasure
  and per-tenant crypto-shredding pull the opposite way. This is a genuine
  legal collision, not an engineering one, and it needs a documented
  per-contract resolution before the archive model is finalised. See ADR 5.
