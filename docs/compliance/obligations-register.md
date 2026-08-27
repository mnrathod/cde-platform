# Obligations register

Per-contract obligations that exceed the product's baseline. §6.3 requires
this to be kept per customer, because government, health and financial buyers
each attach terms that no amount of general compliance work anticipates.

**The register is empty: there are no contracts yet.** What follows is the
structure and the obligations that would attach to each target market, so
that a first contract is assessed against something rather than improvised.

Last reviewed: 2026-08-27.

---

## Register

| Customer | Jurisdiction | Classification | Obligations | Assessed | Owner |
|---|---|---|---|---|---|
| _(none)_ | | | | | |

## What each market would require

### UK public sector and MOD

- **Cyber Essentials Plus**, plus ISO 27001. Neither held.
- **JSP 440, JSP 604, Def Stan 05-138** at the Cyber Risk Profile the
  contract assigns, evidenced through the DCPP questionnaire.
- **OFFICIAL / OFFICIAL-SENSITIVE handling** — marking, storage and
  transmission, and classification labels on records in-product where the
  contract requires them. **Not built.**
- **UK data residency, UK-based personnel, BPSS or higher clearance.**
- **Published accessibility statement** under PSBAR 2018. Drafted, not
  publishable — see `docs/accessibility/`.
- **Air-gapped deployment.** The local storage provider and the HIBP local
  dataset mode exist for this. The 3D viewer no longer needs a CDN (ADR 9),
  which was previously an outright blocker.

### Australian Government and Defence

- **IRAP assessment** against the ISM, with an SSP, Security Risk Management
  Plan, Incident Response Plan and Continuous Monitoring Plan. None written.
- **Essential Eight** to the required maturity. MFA (AU-3) is the only one
  addressed in this repository; the other seven are operational.
- **ASD-approved cryptography.** PBKDF2-HMAC-SHA-256 and AES-256 are on the
  approved list — the reason for ADR 4.
- **DISP membership** at the level the contract sets.
- **Australian residency and cleared Australian personnel.**
- **Defence Trade Controls Act.** Some technical data cannot go offshore or
  to foreign nationals, so access control by nationality or clearance may be
  required. **Not built.**

### EU

- **EN 301 549** and the European Accessibility Act.
- **GDPR** in full, including the data subject rights that `ropa.md` records
  as absent.
- EU-resident processing, to avoid the transfer question entirely.

### Australia, commercial

- **Australian Privacy Act and the 13 APPs**, including APP 8 accountability
  for overseas recipients — which is why region partitioning is
  architectural rather than configurational.
- **Notifiable Data Breaches scheme**: assess within 30 days, notify the OAIC
  and affected individuals. No runbook or template exists.

## The question that has to be answered first

`CLAUDE.md` §18 names it as the highest-value open item, and it is still
open: **what classification does each contract require?**

OFFICIAL-SENSITIVE and PROTECTED are not points on a scale here. They change:

- IRAP assessment scope,
- whether a FIPS-validated cryptographic module is contractually required,
- what personnel clearances apply to support staff,
- **whether external services are permitted at all** — the Pwned Passwords
  API and any LLM provider are both out at PROTECTED,
- the ISO 19650-5 sensitivity assessment for the built asset,
- whether access control by nationality or clearance must exist.

Several of those are architectural. Answering the question late means
rebuilding rather than configuring, which is the reason it is worth pressing
for an answer before the first contract rather than after.
