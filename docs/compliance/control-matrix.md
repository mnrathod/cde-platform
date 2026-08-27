# Control matrix

One control set, mapped to every framework that asks for it, so a control is
implemented once and evidenced many times (§5.1).

**No certification is held.** ISO 27001, SOC 2, IRAP, Cyber Essentials and
DISP appear here as frameworks designed against, never as certifications
obtained. §17.4 makes claiming an uncertified compliance status a
misrepresentation with regulatory consequences, so the distinction is kept
sharp throughout.

Last reviewed: 2026-08-27, against the code as it stands.

## Status vocabulary

| Status | Meaning |
|---|---|
| **Implemented** | Built, and a test in CI proves it |
| **Partial** | Built, with a named gap |
| **Designed** | Decided and documented; no code |
| **Absent** | Not built |

"Implemented" requires a test. A control that exists only in code someone
read is "Partial" at best — the point of the evidence-as-by-product
principle in §5.1 is that the pipeline, not a person's memory, says whether
a control works.

---

## Access control

| # | Control | Status | Evidence | ISO 27001 A. | SOC 2 | ISM |
|---|---|---|---|---|---|---|
| AC-1 | Deny by default on every endpoint | Implemented | `CdeEndpointGuardCoverageTest` fails if an endpoint lacks a permission annotation | 5.15 | CC6.1 | 0405 |
| AC-2 | Object-level authorisation, not just endpoint-level | Implemented | `ContainerPermissionEnforcementTest` matrix, per role per operation | 5.15 | CC6.1 | 0407 |
| AC-3 | Tenant isolation by forced Row-Level Security | Implemented | `TenantIsolationIntegrationTest`, `CdeCrossTenantIsolationTest`; ADR 2 | 5.15 | CC6.1 | 1546 |
| AC-4 | Tenant context set centrally, never from a request parameter | Implemented | `TenantBoundAfterFirstQueryTest` | 8.3 | CC6.1 | — |
| AC-5 | Role escalation refused at registration | Implemented | `RegistrationRoleEscalationTest` | 5.16 | CC6.2 | — |
| AC-6 | Quarterly access reviews, exportable per tenant | Absent | — | 5.18 | CC6.2 | 1533 |
| AC-7 | Just-in-time privilege elevation, time-boxed | Absent | — | 8.2 | CC6.3 | 1507 |

## Authentication

| # | Control | Status | Evidence | ISO 27001 A. | SOC 2 | ISM |
|---|---|---|---|---|---|---|
| AU-1 | PBKDF2-HMAC-SHA-256, ≥600k iterations | Implemented | `Pbkdf2Sha256PasswordEncoderTest`; ADR 4 | 5.17 | CC6.1 | 0421 |
| AU-2 | CI refuses a bare digest over a password | Implemented | `passwordHashingPolicyCheck` Gradle task | 8.28 | CC8.1 | — |
| AU-3 | TOTP second factor, RFC 6238 | Implemented | 81 tests, RFC 6238/4226/4648 vectors | 5.17 | CC6.1 | 0974 |
| AU-4 | TOTP replay protection by burnt time step | Implemented | `MfaEnrolmentIntegrationTest`, persisted across a round trip | 5.17 | CC6.1 | 1401 |
| AU-5 | TOTP secrets encrypted at rest, AES-256-GCM | Partial | Tested. **One key for all tenants**; §5.2 wants KMS envelope encryption with per-tenant keys | 8.24 | CC6.1 | 0459 |
| AU-6 | Single-use recovery codes, stored hashed | Implemented | `MfaEnrolmentIntegrationTest` | 5.17 | CC6.1 | — |
| AU-7 | Progressive throttling per account and per source | Implemented | `AuthenticationThrottleTest`, including credential-stuffing and distributed shapes | 8.5 | CC6.1 | 1403 |
| AU-8 | No user enumeration on failed sign-in | Implemented | Generic message, constant-ish timing | 8.5 | CC6.1 | — |
| AU-9 | Password policy: length, complexity, history, expiry | Designed | Deployment-tier ceiling exists; the policy it constrains does not | 5.17 | CC6.1 | 0421 |
| AU-10 | Breached-password checking (HIBP k-anonymity) | Designed | Mode configured and validated; no client | 5.17 | CC6.1 | 0417 |
| AU-11 | WebAuthn / FIDO2 passkeys | Absent | Needs Spring Security 6.4+; this is on 6.3 | 5.17 | CC6.1 | 1384 |
| AU-12 | SAML 2.0 and OIDC per-tenant SSO | Absent | — | 5.16 | CC6.1 | — |
| AU-13 | SCIM 2.0 provisioning | Absent | — | 5.16 | CC6.2 | — |

## Cryptography and data protection

| # | Control | Status | Evidence | ISO 27001 A. | SOC 2 | ISM |
|---|---|---|---|---|---|---|
| CR-1 | TLS 1.2 minimum, 1.3 preferred | Designed | Apache config not in this repository | 8.24 | CC6.7 | 1139 |
| CR-2 | AES-256-GCM for application-layer field encryption | Implemented | `SecretEncryption`, fresh nonce per operation | 8.24 | CC6.1 | 0459 |
| CR-3 | Encryption at rest for database and storage | Partial | Volume-level, deployment responsibility. The local storage provider does not encrypt — stated in `docs/configuration.md` | 8.24 | CC6.1 | 1080 |
| CR-4 | KMS-held keys, envelope encryption, per-tenant data keys | Absent | Blocks per-tenant crypto-shredding on offboarding | 8.24 | CC6.1 | 0501 |
| CR-5 | Secrets never in code, images or logs | Implemented | Fail-fast config validation; JWT, storage and MFA keys all required with no default | 8.24 | CC6.1 | 0433 |

## Audit and monitoring

| # | Control | Status | Evidence | ISO 27001 A. | SOC 2 | ISM |
|---|---|---|---|---|---|---|
| AL-1 | Security-relevant events recorded | Implemented | `AuditTrailIntegrationTest`; 27 action types | 8.15 | CC7.2 | 0580 |
| AL-2 | Append-only: no UPDATE or DELETE for the application role | Implemented | Raw UPDATE/DELETE attempted in test and refused; row survives | 8.15 | CC7.2 | 0585 |
| AL-3 | SHA-256 hash chain, length-prefixed canonical encoding | Implemented | `AuditRecordHashTest`; ADR 10 | 8.15 | CC7.2 | — |
| AL-4 | Scheduled chain verification with alerting | Absent | The chain is verifiable; nothing verifies it on a schedule | 8.15 | CC7.2 | — |
| AL-5 | WORM export | Absent | — | 8.15 | CC7.2 | 0859 |
| AL-6 | No credentials or raw PII in the audit trail | Implemented | `AuditableChange` refuses field names that look like secrets | 8.15 | CC7.2 | — |
| AL-7 | Structured JSON logs with traceId and tenantId | Partial | SLF4J throughout, no stdout writes; no JSON encoder or shipping configured | 8.15 | CC7.2 | 0988 |
| AL-8 | SIEM shipping | Absent | — | 8.16 | CC7.2 | 1405 |
| AL-9 | Alerting on brute force, escalation, mass export | Absent | Events are recorded; nothing watches them | 8.16 | CC7.2 | — |

## Application security

| # | Control | Status | Evidence | ISO 27001 A. | SOC 2 | ISM |
|---|---|---|---|---|---|---|
| AS-1 | Uploads validated by magic bytes, not extension | Implemented | `UploadedContentInspectorTest` | 8.26 | CC6.8 | — |
| AS-2 | Malware scanning, quarantine-first | Partial | ClamAV client tested against a hand-written fake daemon. **Never tested against a real clamd** | 8.7 | CC6.8 | 1288 |
| AS-3 | Storage keys server-generated, traversal impossible by construction | Implemented | `StorageKeyTest`; ADR 11 | 8.26 | CC6.8 | — |
| AS-4 | Uploads and exports stream, never buffered whole | Implemented | `StorageProviderContract`; `UploadInputHandlingTest` | 8.26 | — | — |
| AS-5 | Browser hardening headers (CSP, HSTS, nosniff, COOP/CORP) | Implemented | `SecurityHeadersTest` | 8.26 | CC6.6 | 1424 |
| AS-6 | CORS closed by default, wildcard refused | Implemented | `CrossOriginPolicyTest` | 8.26 | CC6.6 | — |
| AS-7 | No remotely loaded executable code | Implemented | `scripts/check-no-remote-code.mjs`, verified to fail on reintroduction; ADR 9 | 8.28 | CC6.8 | — |
| AS-8 | Parameterised SQL only | Implemented | JPA throughout; no string-concatenated SQL | 8.28 | CC6.8 | — |
| AS-9 | XXE disabled in every XML parser | Implemented | `XfdfServiceTest` rejects a DOCTYPE | 8.28 | CC6.8 | — |
| AS-10 | Untrusted files parsed out of process | Partial | Separate process; not resource-limited or network-isolated. ADR 8 | 8.27 | CC6.8 | — |
| AS-11 | AI payloads allow-listed and pseudonymised | Implemented | `AiPayloadSanitiserTest`, adversarial corpus | 8.12 | CC6.7 | — |

## Supply chain and change management

| # | Control | Status | Evidence | ISO 27001 A. | SOC 2 | ISM |
|---|---|---|---|---|---|---|
| SC-1 | Licence policy enforced, transitively | Implemented | `checkLicences`; verified by breaking it deliberately | 5.20 | CC9.2 | — |
| SC-2 | Licence change on an existing dependency fails the build | Implemented | Committed baseline, verified to fail | 5.20 | CC9.2 | — |
| SC-3 | Attribution file generated and current | Implemented | `checkAttribution`; fails on untracked, staged or stale | 5.32 | CC9.2 | — |
| SC-4 | SCA and CVE scanning | Absent | No Dependency-Check, Trivy or Grype | 8.8 | CC7.1 | 1493 |
| SC-5 | SAST | Absent | No Semgrep or find-sec-bugs | 8.28 | CC8.1 | — |
| SC-6 | Secret scanning | Partial | Jenkinsfile stage; gitleaks not installed on any agent | 8.28 | CC8.1 | — |
| SC-7 | CycloneDX SBOM per release | Absent | — | 5.20 | CC9.2 | — |
| SC-8 | Container image signing and provenance | Absent | — | 8.28 | CC8.1 | — |
| SC-9 | API contract cannot drift from implementation | Implemented | `OpenApiSpecificationTest` + Spectral; ADR 7 | 8.28 | CC8.1 | — |
| SC-10 | Coverage floor that cannot regress | Implemented | JaCoCo ratchet at 66% line / 47% branch, against a 90/85 target | 8.29 | CC8.1 | — |
| SC-11 | DCO sign-off verified | Absent | Every commit is signed off; nothing checks it | 5.32 | CC8.1 | — |

## Resilience

| # | Control | Status | Evidence | ISO 27001 A. | SOC 2 | ISM |
|---|---|---|---|---|---|---|
| RE-1 | Liveness and readiness probes | Implemented | `ActuatorEndpointSecurityTest` | 8.6 | A1.2 | — |
| RE-2 | Storage reachability proved at startup, not assumed | Implemented | `verifyReachable` writes and reads back a real file | 8.6 | A1.2 | — |
| RE-3 | Zero-downtime, backwards-compatible migrations | Implemented | `FlywayMigrationIntegrationTest`, including FK index coverage | 8.32 | CC8.1 | — |
| RE-4 | Backups with PITR, RPO ≤15 min / RTO ≤4 h | Absent | No backup configuration in this repository | 8.13 | A1.2 | 1511 |
| RE-5 | Monthly automated restore verification | Absent | An untested backup is not a backup | 8.13 | A1.3 | 1548 |
| RE-6 | Documented, rehearsed DR runbooks | Absent | See `dr-plan.md`, which records the absence | 5.29 | A1.3 | — |

---

## Reading the shape of this

Sixty-two controls: 30 Implemented, 8 Partial, 4 Designed, 20 Absent.

The pattern is not random. What is implemented is what a test could prove
from inside the application — authorisation, tenancy, cryptography, input
handling, the audit record itself, the supply-chain gates. What is absent is
almost entirely **operational**: backups, restore testing, SIEM shipping,
alerting, scanning, signing, access reviews.

That is worth being explicit about with an auditor rather than letting them
discover it. A SOC 2 Type II observation window measures controls *operating*
over six to twelve months, and a control with no operational half cannot
operate. The application-side work here is real and tested; the surrounding
operational programme has not started.

The three that would most change this picture, in order:

1. **Backups and restore testing** (RE-4, RE-5). Everything else is a
   degradation; this one is data loss.
2. **SCA and SAST in CI** (SC-4, SC-5). Both are configuration of existing
   tools rather than new engineering, and their absence means no build has
   ever been checked for a known-vulnerable dependency.
3. **Alerting on the audit trail** (AL-4, AL-9). The events are recorded and
   tamper-evident; nothing is watching them, so detection time is however
   long until someone looks.
