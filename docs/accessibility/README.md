# Accessibility evidence

§1A treats accessibility as a procurement gate: several target buyers require
demonstrable conformance by law, and without a current Accessibility
Conformance Report we cannot bid.

| Document | What it is | State |
|---|---|---|
| [accessibility-statement.md](accessibility-statement.md) | The published statement UK PSBAR 2018 requires | **Draft — not fit to publish** |
| [conformance-report.md](conformance-report.md) | VPAT 2.5 INT / ACR | **Incomplete — no criterion evaluated by test** |
| [screen-reader-matrix.md](screen-reader-matrix.md) | The reader/browser matrix and journeys | **Empty — no pass has been run** |

## Where this actually stands

Accessibility work has been done and it is real: seven `<div>` click handlers
converted to keyboard-reachable buttons, alt text on every image, a global
`:focus-visible` indicator, `prefers-reduced-motion` honoured, no
`outline: none` anywhere, and an authentication flow that satisfies SC 3.3.8
by construction — paste allowed everywhere, no CAPTCHA, recovery codes as
selectable text.

None of that adds up to a conformance claim, and these documents do not make
one. What is missing is not more code; it is evidence:

- **No automated scan runs anywhere.** No axe, no Lighthouse budget, no CI
  gate.
- **No assistive technology has ever been pointed at this product.**
- **Nothing has been measured** — not one contrast pair, not one target size,
  not reflow, not zoom.
- **The model viewer has no accessible equivalent**, which §1A.4 requires to
  be the primary data interface rather than an afterthought. That is a design
  change, not a remediation task, and it is the largest item outstanding.

## Reading these documents

They are deliberately unflattering. An ACR whose rows all say "Supports"
without a test behind them is worse than one that says "Not Evaluated",
because a buyer relies on it and §17.4 makes an unearned conformance claim a
misrepresentation with regulatory consequences rather than a marketing
stretch.

The honest version is usable: it tells a bid team exactly what to fix, in
what order, before this product can be offered into a procurement that
requires conformance.
