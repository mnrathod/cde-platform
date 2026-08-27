# Records of Processing Activities

GDPR Article 30 requires a controller and a processor each to maintain a
record of processing activities. This is that record for the CDE Platform.

**Role.** For customer content — documents, drawings, models, markup — we are
a **processor**: the customer decides why and how it is processed, and we
process on instruction. For account and operational data — the identities
that sign in, the audit trail, telemetry — we are a **controller**, because
we decide that this data exists and why.

The distinction matters practically: the erasure obligations in §5 differ
between the two, and so does who answers a data subject.

Last reviewed: 2026-08-27.

---

## 1. Account identities

| | |
|---|---|
| **Role** | Controller |
| **Purpose** | Authenticate a person, attribute their actions, contact them about the service |
| **Lawful basis** | Contract (Art. 6(1)(b)) — an account cannot exist without it |
| **Categories** | Username, email address, password hash (PBKDF2-HMAC-SHA-256), role, tenant, timestamps |
| **Subjects** | Employees and contractors of customer organisations |
| **Recipients** | None. No third party receives account data. |
| **Transfers** | None outside the tenant's region |
| **Retention** | For the life of the account, plus the contractual notice period |
| **Security** | Row-Level Security, password hashing per ADR 4, TLS in transit |

Special categories: none. Nothing here reveals health, beliefs, ethnicity or
anything else in Article 9.

## 2. Second-factor enrolment

| | |
|---|---|
| **Role** | Controller |
| **Purpose** | Verify a second factor at sign-in |
| **Lawful basis** | Legal obligation and legitimate interest (Art. 6(1)(c), 6(1)(f)) — MFA is required by the security frameworks the product is sold against |
| **Categories** | TOTP secret (AES-256-GCM ciphertext), recovery code digests, last-used time step |
| **Retention** | Until the user disables MFA or the account is deleted; cascades on account deletion |
| **Security** | Application-layer encryption. Neither the secret nor a recovery code is ever readable from the database. |

**Gap:** one encryption key protects every tenant's secrets. Per-tenant keys
would allow crypto-shredding on offboarding; see AU-5 in the control matrix.

## 3. Customer content

| | |
|---|---|
| **Role** | **Processor** |
| **Purpose** | Store, convert, render, mark up and version the documents a customer uploads |
| **Lawful basis** | The customer's, as controller. We process on documented instruction. |
| **Categories** | Whatever the customer uploads. Construction drawings and models routinely name individuals — the engineer who signed a drawing, site contacts in a title block — so personal data must be assumed present. |
| **Subjects** | Determined by the customer |
| **Recipients** | None. Content does not leave our infrastructure. **Content is never sent to an AI provider** — the sanitiser allow-lists structured facts and refuses free content (§10.1, `AiPayloadSanitiserTest`). |
| **Transfers** | None outside the tenant's region |
| **Retention** | The customer's decision. ISO 19650 archive retention runs for the operational life of the asset — decades. |
| **Security** | RLS, tenant-prefixed storage keys, malware scanning, quarantine-first admission |

**Unresolved conflict.** ISO 19650 archive retention and GDPR Article 17
erasure pull in opposite directions, and per-contract resolution is
`CLAUDE.md` §18 open item 3. It is a legal collision, not an engineering one,
and it must be settled before the archive model is finalised. See ADR 5.

## 4. Audit trail

| | |
|---|---|
| **Role** | Controller |
| **Purpose** | Detect and investigate security incidents; evidence for ISO 27001 and SOC 2 |
| **Lawful basis** | Legal obligation and legitimate interest (Art. 6(1)(c), 6(1)(f)) |
| **Categories** | Actor identity and label, tenant, source IP, user agent, action, target, outcome, trace ID, change summary |
| **Recipients** | Tenant administrators, for their own tenant only |
| **Retention** | 12 months hot, 7 years archived |
| **Security** | Append-only, hash-chained, no UPDATE or DELETE for the application role |

**The audit trail is exempt from erasure**, and deliberately: it is append-only
by design and by grant, so a deletion request cannot be honoured against it
without destroying the integrity guarantee it exists to provide. Article
17(3)(b) permits retention where processing is necessary for compliance with
a legal obligation. This must be stated in the privacy notice rather than
discovered by a data subject.

Source IP is personal data. It is retained because an audit record without it
cannot answer "where did this come from", which is the first question in
every incident.

## 5. Data subject rights

| Right | Article | Status |
|---|---|---|
| Access | 15 | **Absent** — no export endpoint |
| Portability | 20 | **Absent** — no machine-readable export |
| Rectification | 16 | Partial — a user can change their own profile; no general mechanism |
| Erasure | 17 | **Absent** — no cascade across primary store, storage, caches, logs and search |
| Restriction | 18 | Absent |
| Objection | 21 | Not applicable — no processing on legitimate interest that a subject can object to |

§6.2 requires these as **product features, not manual operations**, fulfilled
within 30 days and self-service where possible. None is built. This is the
largest gap in this record, and an erasure request arriving today would be
handled by hand with no defined procedure.

## 6. Sub-processors

**None.** No third party processes customer data.

This is unusual and worth stating plainly, because it removes a whole class
of obligation: no Standard Contractual Clauses, no transfer impact
assessment, no sub-processor register to publish, no Article 28 flow-down.

It holds only while the AI feature stays off. An AI provider is a
sub-processor the moment one is configured, and it would need to appear here,
in the DPA and in the customer-facing Trust Centre before that happens
(§10.1).

## 7. Breach notification

72 hours to the supervisory authority under GDPR; 30 days to assess and
notify the OAIC under the Australian Notifiable Data Breaches scheme.

**Neither the tooling nor the templates exist.** §6.2 requires them ready in
advance, and preparing them during an incident is precisely when they will
not get written.

## 8. What is missing from this record

- A DPIA. §6.2 requires one for high-risk processing, and hosting an entire
  organisation's construction record qualifies.
- A privacy notice, which APP 1 and Article 13 both require.
- The data subject rights above.
- A per-contract resolution of the retention-versus-erasure conflict.
