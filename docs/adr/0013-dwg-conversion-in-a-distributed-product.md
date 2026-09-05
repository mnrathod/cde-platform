# 13. Licence the ODA File Converter for redistribution, or ship no DWG support

- **Status:** Accepted in part — the engineering position is decided and built.
  **One question is referred to counsel and has no owner** (CLAUDE.md §18,
  open item 2).
- **Date:** 2026-09-05
- **Related:** ADR 8 (out-of-process toolchain), ADR 12 (viewer as a product),
  `docs/licences.md` §4.1 and §4.2

## Context

ADR 12 turns the viewer into something customers install. That makes every
binary in the converter image a thing we **distribute**, which is a different
legal question from running it in a service we operate, and DWG is where the
difference bites.

Binary DWG cannot be read by any open-source library directly; it has to be
converted to DXF first. Two tools can do it, and **they have opposite
problems.**

| | LibreDWG `dwg2dxf` | ODA File Converter |
|---|---|---|
| Licence | GPL-3.0 | Proprietary, from the Open Design Alliance |
| Fidelity | Adequate | Better — it is the reference implementation, and the pipeline was designed around it before LibreDWG was added as a fallback |
| In the image? | **Yes** — compiled from source in `converter/Dockerfile` | **No** |
| The problem | We ship it and owe every recipient the corresponding source. We provide neither the source nor a written offer, so **any distribution of the image is a breach today** (`licences.md` §4.1) | We cannot ship it at all, so a customer who installs the product has no ODA unless they obtain one themselves |

Read together: **for a product customers install, DWG support has no clean
path today.** One converter cannot be shipped lawfully as it stands, and the
other cannot be shipped at all. Running our own SaaS conceals this — the
image never leaves us, ODA is mounted by us, and DWG works. The moment ADR 12
ships, both problems become real on every install.

ADR 12 already named this, in one line: the GPL obligation must be closed
"by publishing the source alongside the image, by a written offer, **or by
dropping LibreDWG and requiring the ODA converter**." That last clause is a
commercial decision nobody has taken, and this ADR is where it gets written
down rather than left in a subordinate clause.

### What engineering has already done

Commit `f7f1154` made a supplied ODA genuinely work rather than merely be
configured: it is discovered from a directory or a binary, launched inside a
virtual framebuffer (it is a Qt application and opens a display even
converting from the command line — without that it aborted on every
container), probed once at startup, and reported as `odaRunnable` distinctly
from `odaInstalled`. Both converters are behind one interface with automatic
fallback.

**So the code is ready for any outcome below.** Nothing here is blocked on
engineering, and no option costs a rewrite.

### What is not known, and must not be assumed

This repository records that ODA's "download is registration-gated and its
licence does not permit redistribution." That is the position we have acted
on and it is why the binary is not vendored — but **nobody here has read the
agreement.** It is an inherited assertion, not a verified term, and §17.3 is
explicit that engineering does not do this analysis. Confirming the actual
terms is the first action, not a conclusion.

## Options

**A. Discharge the GPL-3.0 obligation; LibreDWG only.** Retain the
checksummed source tarball in the image at a documented path and reference it
from `NOTICE` (`licences.md` §4.1, option 1). Cheapest by a wide margin, no
negotiation, no per-seat cost. Costs: DWG fidelity stays at LibreDWG's level,
and the shipped product contains GPL-3.0 code — which some customers'
own procurement will query even though mere aggregation is sound.

**B. Obtain redistribution rights for ODA; drop LibreDWG.** Removes the GPL
binary from the image entirely, so §4.1 closes by deletion rather than by
compliance, and DWG fidelity improves for every customer. Costs: a
negotiation, a recurring fee of unknown size, a dependency on one vendor's
commercial terms for a core format, and re-verification whenever those terms
change (§17.2 treats a licence change as a supply-chain risk).

**C. Both — ODA licensed, LibreDWG retained as fallback with its source
offer.** Best availability: a deployment that cannot run ODA still converts
DWG. Costs the negotiation *and* the GPL compliance work, and keeps two
converters to maintain — which the code already does, so the marginal
engineering cost is nil.

**D. No DWG in the distributed product.** DWG stays a feature of the SaaS we
operate, where ODA is mounted by us and the image is not distributed. Honest,
free, and removes the single most requested format in a construction CDE from
the product ADR 12 exists to sell.

**E. Status quo — every customer registers with the ODA themselves.** Works
today and is what the code supports. Costs: each customer must independently
find, register for, download and mount a third-party tool before DWG works,
and the product's first-run experience for the format its buyers care most
about is a licence agreement with someone else. It also does nothing about
§4.1, which is the breach.

## Decision

**Engineering decides, and has implemented:** the ODA binary is not vendored,
not downloaded during the build, and not baked into any published image. A
supplied ODA is fully supported and reports its own usability. `licences.md`
§4.2 records this and distinguishes it from §4.1.

**Referred to counsel, with no current owner:** which of A–E the distributed
product ships with. Engineering's recommendation is **C, falling back to A if
the ODA terms are unattractive** — because A alone closes the actual breach at
near-zero cost and can be done immediately, while B alone makes a core format
depend on one vendor's continuing goodwill.

That recommendation is a view on cost and risk, not a legal conclusion, and it
is offered so the decision has a starting point rather than a blank page.

**This must be resolved before the first customer install of the viewer
product.** Not before the next release of the SaaS — the SaaS does not
distribute the image, which is why this has been survivable so far.

### Questions for counsel, in order

1. **What do ODA's terms actually say** about redistributing the File
   Converter inside a commercial product? The premise of everything above is
   an assertion in our own repository that nobody has checked against the
   agreement.
2. **Is a redistribution or OEM arrangement available, and on what basis** —
   per-seat, per-deployment, flat, or membership? Ask the ODA directly rather
   than inferring from their public pages.
3. **Does option A's GPL-3.0 presence in a commercial product create a
   problem** for the enterprise, government and Defence buyers in §6? Mere
   aggregation is sound, but procurement questionnaires do not always ask the
   question that carefully, and the answer shapes whether A is viable alone.
4. **For an air-gapped install** (§9.3, and most of the sovereign scope),
   does either option's compliance mechanism survive having no network? A
   written offer naming a URL does not.

## Consequences

**§4.1 stays open and stays a release blocker until this lands.** It is
already listed in `licences.md` §6 as blocking distribution of the converter
image. This ADR does not close it — it names the two ways it can close and
makes the choice visible.

**Whatever is chosen, the code does not change much.** Both converters are
already behind one interface with fallback, discovery and a health probe. A
is a Dockerfile and `NOTICE` change. B is deleting the LibreDWG build stage.
C is neither. That is the point of having done `f7f1154` first: the decision
is not held hostage by an implementation.

**If B or C is chosen, the terms become a tracked dependency.** ODA joins
`licences.md` as a licensed component rather than an operator-supplied one,
the agreement's renewal date needs an owner, and §17.2's licence-change
discipline applies — a change in ODA's terms is exactly the supply-chain risk
that section exists for.

**If A or D is chosen, `docs/licences.md` §4.2 and the ODA support built in
`f7f1154` stay as they are.** Operator-supplied ODA remains supported for the
SaaS and for any customer who happens to hold a licence. Nothing is wasted.

**If nothing is chosen, D happens by default** — and badly. The product ships
either without DWG or in breach, and which one it is gets decided by whoever
writes the release checklist rather than deliberately. That is the outcome
this ADR exists to prevent.

**The named-owner gap is the real blocker.** CLAUDE.md §18 open item 2 —
"name the responsible reviewer and the point in the release process where
sign-off happens" — has been open for the whole project. This decision cannot
be taken by anyone currently named, and it is now on the critical path for
ADR 12. Two other legal questions are queued behind the same gap: the §4.1
choice above, and the retention-versus-erasure collision in §18 open item 3.
