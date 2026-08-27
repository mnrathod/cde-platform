# Accessibility Conformance Report (VPAT 2.5 INT)

**Status: incomplete. Every row below reads "Not Evaluated" or "Does Not
Support", and that is the honest state.**

§1A.6 requires that every claim in an ACR be traceable to a test result. No
accessibility test has been run against this product — no axe, no Lighthouse
budget, no screen reader, no manual keyboard pass. So there are no results to
trace to, and every criterion that would need one is marked Not Evaluated.

A buyer reading this will conclude the product is not ready for a
procurement that requires demonstrable conformance. That conclusion is
correct.

- **Product:** CDE Platform
- **Report date:** 2026-08-27
- **Evaluation method:** source inspection only
- **Standards in scope:** WCAG 2.2 Level A and AA · EN 301 549 · Section 508

---

## How to read the conformance levels

| Level | Meaning |
|---|---|
| **Supports** | Meets the criterion without known defects |
| **Partially Supports** | Some functionality meets it; some does not |
| **Does Not Support** | The majority does not meet it |
| **Not Evaluated** | No test has been performed |

"Not Evaluated" is not a soft "probably fine". It means nobody has looked.

---

## WCAG 2.2 Level A

| Criterion | Level | Conformance | Remarks |
|---|---|---|---|
| 1.1.1 Non-text Content | A | **Partially Supports** | All 4 `<img>` elements carry alt text. The 8 `<canvas>` elements in the 2D and 3D viewers have no text alternative and no equivalent accessible route to the same information. |
| 1.2.x Time-based Media | A | Not Applicable | No audio or video. |
| 1.3.1 Info and Relationships | A | **Does Not Support** | No `<h1>` anywhere; heading levels start at `<h2>`, so every page outline begins mid-hierarchy. Form controls use `<label>` (26 across 34 inputs) but the association has not been verified element by element. |
| 1.3.2 Meaningful Sequence | A | Not Evaluated | DOM order not reviewed against visual order. |
| 1.3.3 Sensory Characteristics | A | Not Evaluated | |
| 1.4.1 Use of Color | A | Not Evaluated | Status and validation cues not audited for a second, non-colour carrier. |
| 1.4.2 Audio Control | A | Not Applicable | |
| 2.1.1 Keyboard | A | **Partially Supports** | Seven `<div>` click handlers were converted to buttons or given a keyboard-reachable button. Two remain on cards containing their own buttons, where the keyboard path runs through a title button. The viewer's canvas interactions — drawing markup, panning, zooming — have no keyboard equivalent. |
| 2.1.2 No Keyboard Trap | A | Not Evaluated | `FocusTrapDirective` exists for modals; no traversal test has been run. |
| 2.1.4 Character Key Shortcuts | A | Not Evaluated | The viewer has single-key shortcuts; no remap or disable mechanism. |
| 2.2.1 Timing Adjustable | A | **Does Not Support** | Session expiry is not announced, not extendable, and gives no warning. |
| 2.2.2 Pause, Stop, Hide | A | **Partially Supports** | `prefers-reduced-motion` collapses animation. Looping indicators have no explicit pause control. |
| 2.3.1 Three Flashes | A | Supports | Nothing flashes. |
| 2.4.1 Bypass Blocks | A | **Partially Supports** | `SkipLinkDirective` exists; not verified as applied on every route. |
| 2.4.2 Page Titled | A | Not Evaluated | Route-level document titles not audited. |
| 2.4.3 Focus Order | A | Not Evaluated | |
| 2.4.4 Link Purpose | A | Not Evaluated | |
| 2.5.1 Pointer Gestures | A | **Does Not Support** | Viewer pinch-zoom and pan are multipoint and path-based, with no single-pointer alternative. |
| 2.5.2 Pointer Cancellation | A | Not Evaluated | |
| 2.5.3 Label in Name | A | Not Evaluated | 22 `aria-label` attributes; none checked against visible text. |
| 2.5.4 Motion Actuation | A | Not Applicable | |
| 3.1.1 Language of Page | A | Not Evaluated | |
| 3.2.1 On Focus | A | Not Evaluated | |
| 3.2.2 On Input | A | Not Evaluated | |
| 3.3.1 Error Identification | A | Not Evaluated | |
| 3.3.2 Labels or Instructions | A | Not Evaluated | |
| 4.1.2 Name, Role, Value | A | Not Evaluated | Converting divs to buttons improves this by construction; unverified with assistive technology. |

## WCAG 2.2 Level AA

| Criterion | Level | Conformance | Remarks |
|---|---|---|---|
| 1.3.4 Orientation | AA | Supports | Orientation is not locked. |
| 1.3.5 Identify Input Purpose | AA | Not Evaluated | `autocomplete` attributes not audited. |
| 1.4.3 Contrast (Minimum) | AA | Not Evaluated | Not one colour pair has been measured. |
| 1.4.4 Resize Text | AA | Not Evaluated | 200% zoom untested. |
| 1.4.5 Images of Text | AA | Supports | No text rendered as an image. |
| 1.4.10 Reflow | AA | Not Evaluated | 320px reflow untested. The viewer is fixed-layout and most likely to fail. |
| 1.4.11 Non-text Contrast | AA | Not Evaluated | |
| 1.4.12 Text Spacing | AA | Not Evaluated | |
| 1.4.13 Content on Hover or Focus | AA | Not Evaluated | |
| 2.4.5 Multiple Ways | AA | **Partially Supports** | Search and navigation exist; no sitemap. |
| 2.4.6 Headings and Labels | AA | **Does Not Support** | No `<h1>`; heading hierarchy is incomplete. |
| 2.4.7 Focus Visible | AA | **Supports** | Global `:focus-visible` indicator; no `outline: none` anywhere in the codebase (verified, zero occurrences). |
| 2.4.11 Focus Not Obscured (Min) | AA | Not Evaluated | The viewer has sticky toolbars — the likeliest place to fail. |
| 2.5.7 Dragging Movements | AA | **Partially Supports** | The upload drop zone is now a button, so file selection has a single-pointer path. Markup drawing and page reordering are drag-only. |
| 2.5.8 Target Size (Minimum) | AA | Not Evaluated | Not measured against 24×24 CSS px. |
| 3.1.2 Language of Parts | AA | Not Applicable | Single language. |
| 3.2.3 Consistent Navigation | AA | Supports | One shell, consistent across routes. |
| 3.2.4 Consistent Identification | AA | Not Evaluated | |
| 3.2.6 Consistent Help | AA | **Does Not Support** | No help mechanism in a consistent location. |
| 3.3.3 Error Suggestion | AA | Not Evaluated | |
| 3.3.4 Error Prevention | AA | Not Evaluated | |
| 3.3.7 Redundant Entry | AA | Not Evaluated | |
| 3.3.8 Accessible Authentication (Min) | AA | **Supports** | No cognitive-function test. Password and TOTP fields accept paste and work with password managers; TOTP is a single field, not split character inputs. No CAPTCHA — throttling is risk-based. Recovery codes are selectable text, not an image. |
| 4.1.3 Status Messages | AA | **Does Not Support** | Two `aria-live` regions across the whole application. Upload progress, save confirmations and error banners do not announce. |

---

## The item that matters most

**The model viewer has no accessible equivalent, and that is architectural.**

§1A.4 is explicit: a WebGL canvas cannot be made conformant on its own, and
the requirement is an equivalent accessible route to the same information —
a structured, navigable tree and table of model hierarchy, properties and
metadata, supporting search, selection and the same actions as the viewer.
It says to build that as the primary data interface with the viewer as a
visual layer over it, **not as an afterthought bolted on later**.

It has been built the other way round. There is an `IfcTreeComponent`, which
is a start, but it is a panel beside the viewer rather than an equal route to
the data, and it does not carry the viewer's actions.

Closing this is a design change, not a remediation task, and it is the single
largest piece of work in this report.

---

## Before this report can carry a conformance claim

1. axe-core in CI across the critical journeys, failing on serious or critical.
2. Manual keyboard-only pass, every screen.
3. Screen-reader pass: NVDA, JAWS, VoiceOver on macOS and iOS, TalkBack.
4. Measure contrast, target sizes, reflow at 320px, zoom to 200%.
5. Build the accessible route to the model data.
6. Tagged, PDF/UA-conformant exports.
7. Independent third-party audit (§1A.5).
8. Usability testing with disabled users — an interface can satisfy every
   criterion above and still be unusable, and only real users surface that.
