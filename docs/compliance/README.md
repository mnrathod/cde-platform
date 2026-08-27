# Compliance pack

§5.1 asks that evidence be a by-product of engineering rather than a fire
drill. These documents are the by-product: they describe what is actually
built and tested, and name what is not.

| Document | What it is |
|---|---|
| [control-matrix.md](control-matrix.md) | 62 controls, mapped once to ISO 27001, SOC 2 and the ISM |
| [ropa.md](ropa.md) | Records of Processing Activities (GDPR Art. 30) |
| [dr-plan.md](dr-plan.md) | Disaster recovery — **records the absence of a plan** |
| [obligations-register.md](obligations-register.md) | Per-contract obligations; empty, with the structure and what each market requires |
| [../licences.md](../licences.md) | Licence register and granted exceptions |
| [../accessibility/](../accessibility/) | Accessibility statement, ACR, screen-reader matrix |

## No certification is held

ISO 27001, SOC 2, IRAP, Cyber Essentials and DISP appear throughout as
frameworks designed against. **None is held.** §17.4 makes claiming an
uncertified compliance status a misrepresentation with regulatory
consequences rather than a marketing stretch, so nothing here should be
quoted into a bid as a certification.

## The honest summary

Of 62 controls: **30 Implemented** (built, with a test in CI proving it),
**8 Partial**, **4 Designed**, **20 Absent**.

The split is not arbitrary. Almost everything implemented is what a test can
prove from inside the application — authorisation, tenant isolation,
cryptography, input handling, the audit record, the supply-chain gates.
Almost everything absent is **operational** — backups, restore testing, SIEM
shipping, alerting, vulnerability scanning, image signing, access reviews.

That distinction is the single most useful thing in this pack. A SOC 2 Type II
window measures controls *operating* over six to twelve months, and a control
with no operational half cannot operate. The application-side work is real
and tested; the operational programme has not started.

## What to fix first

1. **Backups and restore testing.** Current RPO is total loss and current RTO
   is undefined, because there is no backup to restore from. Everything else
   in this pack is a degradation; this one is data loss.
2. **SCA and SAST in CI.** Both are configuration of existing tools rather
   than engineering, and their absence means no build has ever been checked
   against a known-vulnerable dependency.
3. **Alerting on the audit trail.** The events are recorded and
   tamper-evident. Nothing watches them, so time-to-detect is however long it
   takes someone to look.
4. **Data subject rights as product features.** Access, portability and
   erasure are all absent, and an erasure request arriving today would be
   handled by hand with no procedure.
5. **Settle the classification question** (§18 open item 1). It determines
   IRAP scope, FIPS requirements, personnel clearances, and whether external
   services are permitted at all — several of which are architectural, so
   answering late means rebuilding rather than configuring.
