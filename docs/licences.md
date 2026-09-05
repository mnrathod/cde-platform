# Licence register

The approved licence list, every exception granted, and the obligations this
product currently carries. CLAUDE.md §2.1 and §17 set the policy; this file is
the record of how the policy lands on the dependencies we actually have.

**Enforcement lives in `gradle/licences.gradle`**, not here. That script
generates `THIRD-PARTY-NOTICES.txt` from the resolved runtime classpath and
fails the build on a forbidden licence, an unrecognised licence, or a licence
that changed since `gradle/licence-baseline.txt`. This file explains the
decisions; the script enforces them. If the two disagree, the script is what
ships, so fix the script.

Last reviewed: 2026-08-27, against 114 resolved backend components.

---

## 1. Policy

**Allowed** — Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC, EPL-1.0,
EPL-2.0, MPL-2.0, CDDL-1.0/1.1, PostgreSQL, Unlicense, CC0-1.0,
GPL-2.0-with-classpath-exception.

**Forbidden** — AGPL (any version), SSPL, BSL/Business Source Licence,
Commons Clause, Elastic Licence, any "source available" non-OSI licence, and
anything carrying a field-of-use restriction.

**Case by case** — LGPL, permitted only for a dynamically linked, unmodified
library. Every instance needs an entry in §3 below *and* in
`approvedLicenceExceptions` in `gradle/licences.gradle`. The build refuses an
LGPL component that is not in both.

### How multiple licences on one component are read

A POM listing several licences means the consumer may choose among them —
this is Maven's own documented interpretation. The gate therefore passes a
component if **any** declared licence is on the allow-list, and the
attribution file joins them with `OR`. Logback offering "EPL-1.0 or
LGPL-2.1" is not an LGPL obligation: we take EPL-1.0 and that discharges it.

Reading them conjunctively would have been the stricter-looking choice and
the wrong one — it would have forced spurious exceptions for components that
carry no copyleft obligation at all, and spurious exceptions train people to
approve real ones.

---

## 2. Current position

### Backend (Gradle, 114 runtime components)

| Licence | Components | Note |
|---|---:|---|
| Apache-2.0 | 89 | Spring, Jackson, Tika, PDFBox, Micrometer, Flyway, JJWT |
| BSD-3-Clause | 8 | Includes the Eclipse Distribution Licence, which is the BSD-3 text |
| MIT | 6 | SLF4J, Bouncy Castle, checker-qual |
| EPL-2.0 OR LGPL-2.1 | 2 | Logback — **we take EPL-2.0** (was EPL-1.0 before Spring Boot 4) |
| EPL-2.0 OR GPL-2.0-w-CPE | 2 | Jakarta annotation and transaction APIs — **we take EPL-2.0** |
| EPL-2.0 | 1 | AspectJ weaver |
| EPL-2.0 OR BSD-3-Clause | 1 | Jakarta Persistence API |
| Apache-2.0 | — | Hibernate, counted in the Apache-2.0 row above since its 7.x re-licence |
| BSD-2-Clause | 1 | PostgreSQL JDBC driver |
| CC0-1.0 | 1 | HdrHistogram |
| CC0-1.0 OR BSD-2-Clause | 1 | LatencyUtils |

No AGPL, SSPL, BSL, Commons Clause, or Elastic Licence component appears
anywhere in the graph, transitively included. None of the §2.2 rejected
components (Redis, Grafana, Elasticsearch, MongoDB, Couchbase, Terraform
≥1.6, Vault ≥1.15) is present.

### Frontend (npm, production dependencies)

MIT 23, Apache-2.0 4, BSD-2-Clause 1, 0BSD 1. All on the allow-list.

The one package reported as `UNLICENSED` is `cde-angular` itself — this
product's own code, correctly marked as not open source. It is not a
third-party dependency.

**Resolved during this review:** the frontend was loading three.js and
pdf.js from public CDNs at runtime, so two MIT libraries were shipping to
users with no attribution, at versions nobody had recorded, from hosts
outside our control. Both now come from the bundle, and
`scripts/check-no-remote-code.mjs` fails the build if the pattern returns.
That closes the attribution hole as a side effect of closing the
supply-chain one.

**Gap:** the npm licence scan is not yet wired into CI, and no attribution
file is generated for the frontend bundle. The backend gate covers Gradle
only. See §6.

### Converter service (Python, plus system packages in its image)

**Reviewed against the resolved closure, 2026-08-29.** The earlier review read
the environment it happened to be running in; `converter/requirements.txt` is
now a generated, fully pinned closure (`converter/lock-requirements.sh`), so
every package below is one the image actually installs, and the list is
complete rather than partial.

| Package | Licence | Notes |
|---|---|---|
| cffi | MIT-0 | |
| charset-normalizer | MIT | |
| cryptography | Apache-2.0 OR BSD-3-Clause | Take Apache-2.0 (§17.3 prefers the patent grant) |
| ezdxf | MIT | Declared **without extras** — see below |
| fonttools | MIT | |
| **ifcopenshell** | **LGPL-3.0-or-later** | **Weak copyleft — see §3.2** |
| isodate | BSD-3-Clause | |
| lark | MIT | |
| numpy | BSD-3-Clause AND 0BSD AND MIT AND Zlib AND CC0-1.0 | |
| packaging | Apache-2.0 OR BSD-2-Clause | |
| pdfminer.six | MIT | |
| pdfplumber | MIT | Previously unverified — now confirmed permissive |
| pillow | MIT-CMU | |
| pycparser | BSD-3-Clause | |
| pyparsing | MIT | |
| pypdf | BSD-3-Clause | |
| pypdfium2 | BSD-3-Clause, Apache-2.0 | |
| pytesseract | Apache-2.0 | Binding only; Tesseract itself is a subprocess (§4) |
| python-dateutil | Apache-2.0 OR BSD-3-Clause | |
| shapely | BSD-3-Clause | |
| six | MIT | |
| typing_extensions | PSF-2.0 | |

Test-only, in `converter/requirements-dev.txt`: pytest MIT, reportlab
BSD-3-Clause, iniconfig MIT, pluggy MIT, Pygments BSD-2-Clause. Not shipped
in the runtime image.

#### Two forbidden components were being installed and are now gone

`converter/requirements.txt` previously declared **`ezdxf[draw]`**. That extra
exists for ezdxf's matplotlib and Qt rendering backends, and it pulled in:

| Package | Licence | Verdict |
|---|---|---|
| **PyMuPDF** | **AGPL-3.0** or Artifex commercial | **Forbidden outright by §2.1** — AGPL, any version |
| **PySide6** (+ Addons, Essentials, shiboken6) | LGPL-3.0-only OR GPL-2.0-only OR GPL-3.0-only | Weak copyleft at best, never approved |

`converter/app.py` imports neither. It renders through
`ezdxf.addons.drawing.svg.SVGBackend`, which is pure Python and needs no
matplotlib, no Qt and no PyMuPDF — verified by installing plain `ezdxf` and
rendering a DXF to SVG with the exact import list `app.py` uses.

They were invisible to the previous review for the same reason the review
called itself partial: nothing had ever resolved the full transitive graph.
An AGPL component was being built into a distributed image on the strength of
one square bracket.

Dropping the extra removes both, plus matplotlib and its dependency tree —
roughly 290 MB of wheels — and changes no behaviour. `scripts/check-pinning.sh`
fails the build if either the extra or PyMuPDF reappears.

---

## 3. Exceptions granted

### 3.1 Hibernate ORM — LGPL-2.1 — **RETIRED 2026-08-28**

No exception is in force. This entry is kept as the record of one that was.

| | |
|---|---|
| Components | `org.hibernate.orm:hibernate-core`, `org.hibernate.common:hibernate-commons-annotations` |
| Was | LGPL-2.1, permitted under §2.1 as a dynamically linked, unmodified library |
| Now | **Apache-2.0**, from Hibernate ORM 7.x, which Spring Boot 4 brings in at 7.4.5 |
| How it was confirmed | The §17.2 licence-change gate failed the build on the Spring Boot 4 upgrade, reporting `hibernate-core was LGPL-2.1, is now Apache-2.0`. That is the detector working as designed: a re-licence surfaced at the upgrade rather than at an audit. |
| Effect | Apache-2.0 is on the §2.1 allowed list and carries an express patent grant, which §17.3 prefers. The library was never modified, so no LGPL obligation was ever incurred and none survives. |
| Removed from | `approvedLicenceExceptions` in `gradle/licences.gradle` |

The previous entry noted this move was expected and should be confirmed on the
next Spring Boot major upgrade. That upgrade is this one, and it is confirmed.

### 3.2 IfcOpenShell — LGPL-3.0-or-later — **IN FORCE, granted 2026-08-29**

| | |
|---|---|
| Component | `ifcopenshell` (PyPI), in the converter image only |
| Licence | LGPL-3.0-or-later, confirmed from PyPI metadata for the pinned version |
| Why it is needed | IFC is a first-class format under §6.7.4, and `converter/app.py` uses `ifcopenshell` and `ifcopenshell.geom` for parsing and geometry iteration. There is no permissively-licensed equivalent with comparable IFC schema coverage. |
| Why §2.1 permits it | LGPL is allowed for a **dynamically linked, unmodified** library. The wheel is installed as published and loaded at runtime as a shared object; we do not patch, vendor, or statically link it. |
| Conditions | Do not modify it. Do not vendor its sources into this repository. If a patch ever becomes necessary, §17.2 applies — contribute upstream or work around, because fork-and-patch creates a distribution obligation. |
| Obligation we carry | LGPL §4: recipients must be able to relink the work against a modified IfcOpenShell. Because it is an unmodified, separately-installed Python wheel that the user can replace in the venv, this is satisfied by the installation form itself. Record the version and source in the SBOM. |
| Review trigger | Any change to how the converter loads it, or any upstream relicence — the §17.2 licence-change gate does not yet cover PyPI, which is a gap in §6. |

---

## 4. GPL tooling — out of process

CLAUDE.md §2.1 and §17.2 permit GPL tools invoked as separate processes.
Three are in use, and all three are genuinely separate executables invoked
over a socket or command line. None is linked, embedded, or bundled into the
application artifact, so no combined work is created and their copyleft does
not reach this product's code.

| Tool | Licence | How it is invoked | Where it lives |
|---|---|---|---|
| ClamAV | GPL-2.0 | INSTREAM over a TCP socket (`ClamAvScanner`) | Its own service/container |
| LibreOffice | MPL-2.0 | `soffice --headless` subprocess | Converter image |
| LibreDWG (`dwg2dxf`) | **GPL-3.0** | subprocess | **Compiled into and copied into the converter image** |

### 4.1 Open obligation — distributing the `dwg2dxf` binary

**This is not discharged and blocks distribution of the converter image.**

`converter/Dockerfile` builds LibreDWG 0.13.3 from source and copies the
resulting `dwg2dxf` binary into the runtime image. Invoking it as a
subprocess keeps our own code clear of GPL-3.0 — that part is fine, and is
mere aggregation rather than a combined work.

But shipping the image *distributes the binary*, and GPL-3.0 §6 requires
that anyone receiving a binary is offered the corresponding source. Today
neither the source nor a written offer accompanies the image, so any
distribution of it is a breach.

Three ways to close it, in rough order of preference:

1. **Ship the source alongside the binary.** The Dockerfile already
   downloads a checksummed release tarball; retain it in the image (or in
   the published artifact set) at a documented path, and reference that path
   from `NOTICE`. Cheapest and self-contained.
2. **Publish a written offer** valid for three years, naming a URL or
   address where the exact corresponding source can be obtained. Requires
   keeping that channel alive for the full period, including the precise
   version built.
3. **Split the converter into its own image** that we do not redistribute,
   pulled by the customer directly from a public registry. Changes the
   deployment story and does not help air-gapped installations, which is
   most of the sovereign and Defence scope.

Option 1, with the tarball and its `SHA256` retained, is the recommendation.
Whichever is chosen needs counsel's sign-off before the image ships.

Note that option 3's variant — customers building the image themselves — is
not distribution by us at all and would sidestep the obligation entirely,
but it conflicts with the air-gapped delivery requirement in §9.3.

---

## 4.2 Proprietary tooling the operator supplies — ODA File Converter

The **ODA File Converter** (Open Design Alliance) converts DWG to DXF at
higher fidelity than LibreDWG, and the converter service tries it first when
it is present. It is **not distributed with this product and must not be**:

| | |
|---|---|
| Licence | Proprietary end-user licence — not OSI, not on the §2.1 allow-list |
| Redistribution | **Not permitted.** Its download is registration-gated and its terms bind the person who accepted them |
| How it is used | A separate executable invoked as a subprocess, never linked or embedded |
| Where the binary comes from | The deployment supplies it; nothing in our image or artifact set contains it |
| Without it | DWG conversion falls back to LibreDWG and works |

**This is not the `dwg2dxf` situation in §4.1 and must not be conflated with
it.** That one is an open obligation: a GPL-3.0 binary we *do* ship without
the corresponding-source offer it requires. This one is closed by
construction — we ship nothing, so there is nothing to license onward.

What the product *does* ship is everything around it: the discovery of a
mounted install, the virtual display it needs to start on a headless host
(`xvfb`, X.Org, MIT — an ordinary allow-listed package), a startup probe that
reports whether it actually ran, and the fallback when it is absent. That
support is ours and carries no third-party obligation.

**Do not "solve" this by vendoring the binary, adding a download step to the
Dockerfile, or publishing an image with ODA inside it.** Each of those is
redistribution, and each would put a non-OSI, field-of-use-restricted
component into an artifact that §2.1 forbids it from and that enterprise and
Defence procurement will audit (§17). If bundled DWG fidelity is a commercial
requirement, the route is a redistribution agreement with the Open Design
Alliance, negotiated by whoever owns legal review (§18, open item 2) — not an
engineering change.

**That question is now written up as a decision: [ADR
13](adr/0013-dwg-conversion-in-a-distributed-product.md).** It sets this
section beside §4.1 above, because the two converters have opposite problems
and a distributed product needs one of them resolved: LibreDWG can be shipped
only with a source offer it does not have, and ODA cannot be shipped at all.
The ADR costs the options and names what counsel has to answer.

One caution belongs here rather than only in the ADR: **the sentence "its
licence does not permit redistribution" is inherited, not verified.** It is
the position this repository has acted on and the reason the binary is not
vendored, which is the safe direction to be wrong in — but nobody here has
read the agreement, and §17.3 puts that reading outside engineering. Checking
it is action one in ADR 13, not a settled premise.

---

## 5. Standards, content and assets

**Standards documents are copyrighted.** ISO 19650's suitability code and
revision code tables are not reproduced anywhere in this product. The
mechanism ships — configurable code lists with descriptions, ordering and
permitted transitions — and each customer populates it. This is a licence
constraint, recorded in the code at `SuitabilityCode` and
`SuitabilityCodeController`, not a product preference.

**Fonts and icons:** the frontend ships no third-party font or icon set.
Iconography is inline SVG and emoji; system font stacks are used
throughout, with no `@font-face` and no webfont host. Nothing to attribute
here.

**Images — unresolved.** `public/favicon.ico` and eight PWA icons
(`public/icons/icon-*.png`, 72 px to 512 px) are committed with no recorded
provenance. They are almost certainly generated placeholders, but "almost
certainly" is not a licence position and these ship in every distribution.
Confirm they were generated in-house or replace them with assets that have
a documented licence. Tracked in §6.

Both must be re-checked the moment any design asset is introduced — §17.1
covers assets as firmly as it covers code.

**Trademarks:** no third-party name, logo, or mark appears in the UI. Where
documentation names a third-party product it does so nominatively ("works
with", "compatible with"), never implying endorsement, partnership, or
certification.

**No certification is claimed.** ISO 27001, SOC 2, IRAP and Cyber Essentials
are named in the compliance documentation as *targets and control frameworks
designed against*, never as certifications held. None is held. Claiming
otherwise would be a misrepresentation with regulatory consequences, not a
marketing stretch — see `docs/compliance/control-matrix.md`, which states
the same thing at the top.

---

## 6. Known gaps

| Gap | Impact | Status |
|---|---|---|
| `dwg2dxf` GPL-3.0 source offer (§4.1) | **Blocks distribution of the converter image** | Open — written up as [ADR 13](adr/0013-dwg-conversion-in-a-distributed-product.md), which costs the options; needs an owner and counsel sign-off |
| ODA redistribution terms unverified (§4.2) | Decides whether the shipped product can have DWG support at all | Open — [ADR 13](adr/0013-dwg-conversion-in-a-distributed-product.md), question 1. Not a breach: we ship nothing, so the safe direction |
| ~~`ifcopenshell`, `pdfplumber` licences unverified~~ | — | **Closed 2026-08-29** — pdfplumber is MIT; ifcopenshell is LGPL-3.0-or-later with an exception recorded at §3.2 |
| Python dependencies are not licence-gated in CI | The `ezdxf[draw]` finding (§2) was caught by hand, not by a gate. The next one would not be. | Open — needs the licence scan extended to `converter/requirements.txt`; `scripts/check-pinning.sh` only blocks the specific packages already known to be a problem |
| The §17.2 licence-change detector covers Gradle only | A PyPI or npm dependency that re-licences would not fail the build | Open |
| npm licences not gated in CI | A forbidden frontend dependency would not be caught | Open |
| No frontend attribution file | The MIT/BSD attribution clauses apply to the shipped bundle too | Open |
| Favicon and 9 PWA icons have no recorded provenance (§5) | Undocumented assets ship in every distribution | Open — confirm in-house or replace |
| Docker base image and OS package licences not scanned | §17.2 requires scanning image layers, not just app dependencies | Open — needs Syft or Trivy in the pipeline |
| No CycloneDX SBOM | §5.10 and §17.2 require one per release | Open |
| Copyright holder is a placeholder | `LICENSE` and `NOTICE` name no legal entity | Open — needs the real entity before any distribution |
| No source-file copyright headers | §17.1 requires one per file | Open — blocked on the entity name above |
| DCO sign-off not CI-enforced | §17.1 requires a CI check | Open — commits are signed off, but nothing verifies it |

---

## 7. Source file header

Apply once the copyright holder is known. Until then, adding headers naming
a placeholder entity across every file would be worse than having none —
a false copyright notice is a legal statement, and it would have to be
rewritten in a churn commit touching every file.

```java
/*
 * Copyright (c) 2026 <LEGAL ENTITY>. All rights reserved.
 *
 * This file is part of the CDE Platform and is proprietary and confidential.
 * Use is governed by the licence in LICENSE at the repository root.
 */
```

---

## 8. Procedure

**Adding a dependency.** Resolve its current stable version and API through
Context7 (§0.1) — never guess a version. State its licence, maintenance
status, last release date, and transitive footprint, and say why an existing
dependency cannot do the job (§0.3). Then run:

```
./gradlew generateAttribution writeLicenceBaseline checkLicences
```

and commit `THIRD-PARTY-NOTICES.txt` and `gradle/licence-baseline.txt`
alongside the dependency change. The build fails if either is stale.

**When `checkLicences` reports a change.** Do not update the baseline to
make the build green. A changed licence is the signal the gate exists to
raise: several major projects have re-licensed away from open source since
2021, and catching it at the upgrade rather than at a procurement audit is
the entire point. Read the new licence, decide whether it is still
acceptable, and only then regenerate the baseline.

**When `checkLicences` reports UNDECLARED.** The component's POM declares a
licence this build's mapper does not recognise, or declares none at all.
Read the POM and the project's own `LICENSE` file, then either extend
`toSpdxIdentifier` if it is a naming variant, or record the component here
if it needs a human decision. Never widen the allow-list to clear an
individual component.
