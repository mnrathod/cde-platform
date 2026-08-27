# Accessibility statement

**Status: draft. Not yet fit to publish.**

UK PSBAR 2018 requires a published accessibility statement, and this is the
document that would satisfy it — but publishing it now would be a
misrepresentation. Three things have to happen first, and they are named in
§6 rather than hidden: no assistive technology has been used against this
product, no automated accessibility scan runs anywhere, and no disabled user
has tried it.

What follows is accurate about what has been built and tested. It makes no
conformance claim, because none can honestly be made yet.

Last reviewed: 2026-08-27.

---

## 1. What we are aiming at

**WCAG 2.2 Level AA**, which is the single technical target that satisfies
EN 301 549, the UK Public Sector Bodies Accessibility Regulations 2018, the
Australian Disability Discrimination Act and Digital Service Standard, and
US Section 508.

We are **not claiming conformance to it**. See §3.

## 2. What has been done

Verifiable in the code, and measured on 2026-08-27:

**Semantic controls.** 118 `<button>` elements, 34 `<input>`, 26 `<label>`.
Seven controls that were `<div>` elements with click handlers — unreachable
by keyboard, invisible to assistive technology — were converted to real
buttons or given a keyboard-reachable button inside them. Two `<div>` click
handlers remain, both on cards that contain their own buttons; a button
inside a button is invalid HTML, so those keep the click as a pointer
convenience and route the keyboard path through a button on the card's
title.

**Images.** All 4 `<img>` elements carry alt text. Two were missing it and
were fixed; a third was already correct and appeared missing only because
our first check looked for `alt=` and Angular writes `[alt]=`.

**Focus.** Nothing in the codebase sets `outline: none` — checked, zero
occurrences. A `:focus-visible` indicator is defined globally with a
two-tone outline so it stays visible against both the light application
surfaces and the dark viewer chrome.

**Motion.** `prefers-reduced-motion: reduce` collapses animations and
transitions to a single frame. Reduced rather than removed, so code waiting
on `animationend` still fires — removing animations outright is a
well-known way to leave a dialog that never finishes opening.

**Directives available.** `FocusTrapDirective` for modals,
`KeyboardClickDirective`, `AriaLiveDirective`, `SkipLinkDirective`.

**No cognitive-function test in authentication** (SC 3.3.8). Password fields
allow paste and work with password managers. TOTP entry is a single field
that accepts a pasted code, not split single-character inputs. There is no
CAPTCHA anywhere; rate limiting is risk-based throttling. Recovery codes are
selectable, copyable text.

## 3. What is not conformant, and what we do not know

Stated plainly, because an accessibility statement that lists no limitations
is not being read carefully by the people it is written for.

**We do not know whether this product is usable with a screen reader.** No
NVDA, JAWS, VoiceOver or TalkBack session has been run against it. That is
the single largest gap here and it is not something the code can answer.

**Known failures against WCAG 2.2 AA:**

| Criterion | Failure | Severity |
|---|---|---|
| 1.1.1, 1.3.1, 2.1.1 | The 3D/IFC viewer is a WebGL canvas. There are 8 `<canvas>` elements and no equivalent accessible route to the same information — no navigable tree or table of model hierarchy, properties and metadata. §1A.4 requires that route to be the primary data interface with the viewer as a visual layer over it. It is not built. | **Blocking** |
| 1.3.1, 2.4.6 | No `<h1>` anywhere. Heading levels start at `<h2>`, so every page's outline begins mid-hierarchy and screen-reader heading navigation has no top level to land on. | High |
| 1.4.3 | Contrast has never been measured. The palette may be fine; nobody has checked a single pair. | Unknown |
| 2.5.8 | Target sizes have never been measured against the 24×24 CSS px minimum. | Unknown |
| 1.4.10, 1.4.4 | Reflow at 320px and 200% zoom untested. The viewer is a fixed-layout surface and is the most likely to fail. | Unknown |
| 4.1.3 | Status messages are inconsistent. `aria-live` appears twice across the whole application; upload progress, save confirmations and error banners mostly do not announce. | Medium |
| — | Generated PDF exports are not tagged or PDF/UA-conformant. §1A.4 calls this the most common gap found in government audits, and it is present here. | High |

**Not measured at all:** keyboard traps, focus order, focus obscuring by
sticky headers (SC 2.4.11), text-spacing overrides, RTL layout,
`prefers-contrast`.

## 4. Feedback

Once this statement is published it must name a monitored channel with a
committed response time, and user-reported accessibility issues must be
triaged like security reports. Neither the channel nor the triage commitment
exists yet.

## 5. How this was assessed

By reading and instrumenting the code, on 2026-08-27. That is a weak form of
assessment and its limits are the point: it can find a `<div>` with a click
handler and it cannot tell you whether the application makes sense when
read aloud.

Automated tooling catches only a minority of WCAG issues even when it is
running, and here **it is not running at all** — no axe, no Lighthouse
accessibility budget, no CI gate.

## 6. What has to happen before this can be published

1. Install axe-core and wire it into the Playwright journeys, with the CI
   gate §1A.5 requires. Zero serious or critical violations on `main`.
2. A manual keyboard-only pass over every screen.
3. A screen-reader pass over the supported matrix: NVDA and JAWS on Windows,
   VoiceOver on macOS and iOS, TalkBack on Android.
4. Measure contrast, target sizes, and reflow at 320px and 200%.
5. Decide and build the accessible route to the model data (§1A.4). This is
   the largest piece of work on the list and it is architectural, not a fix.
6. Make generated PDFs tagged and PDF/UA-conformant.
7. Independent third-party audit, per §1A.5.
8. Only then complete the VPAT/ACR and publish a conformance claim.

Until 1 to 4 are done, any conformance claim made in a bid would be
unevidenced — and §17.4 is explicit that claiming an uncertified compliance
status is a misrepresentation with regulatory consequences, not a marketing
stretch.
