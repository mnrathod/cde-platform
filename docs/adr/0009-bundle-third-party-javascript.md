# 9. Bundle third-party JavaScript; never load executable code from a CDN

- **Status:** Accepted
- **Date:** 2026-08-27

## Context

The PDF viewer and the 3D viewer each injected a `<script>` tag pointing at a
public CDN — cdnjs for pdf.js and three.js, jsDelivr for OrbitControls.

Both libraries were already declared in `package.json` and already bundled.
The CDN copies were not additions; they were *duplicates at older versions*.
pdf.js 3.11 was being fetched over a bundled 5.4. three.js r128, released in
2021, over a bundled 0.185.

The PDF case was worse than redundant: the fetched script was never used at
all. Rendering already went through the bundled `PdfEngineService`. The
network request bought nothing whatsoever.

## Options

**Keep the CDN loads.** Zero work. Keeps four unacceptable properties.

**Keep the CDN, add Subresource Integrity.** Fixes the tampering vector by
pinning a hash. Does not fix version drift, the CSP conflict, or air-gapped
deployment — and pins us to a hash that upstream can withdraw, at which point
the viewer breaks with no diagnostic.

**Self-host the files as static assets.** Fixes the origin problem. Leaves a
second copy of each library that upgrades independently of `package.json`,
which is how the drift started.

**Import from the bundle.** One version, declared in one place.

## Decision

Import both libraries from `package.json`. `PdfEngineService` already did;
the dead pdf.js fetch is deleted outright. The 3D viewer uses a dynamic
`import('three')` so the renderer stays out of the initial bundle and is
fetched only when a model is actually opened.

`scripts/check-no-remote-code.mjs` fails the build on any reference to a
known code-serving CDN, or on any `createElement('script')` in a file that
also contains an absolute URL.

## Consequences

Four problems close at once, which is why this was worth doing properly
rather than patching the obvious one:

- **Supply chain.** A remote script runs with full privileges on our origin,
  with the user's session. Neither call site used SRI, so a compromised or
  hijacked CDN owned every session, and nothing bounded the damage.
- **Version drift.** Fetching an older copy over a newer bundled one
  reintroduces bugs that were already fixed. Nothing in the build could see
  this, because the CDN version appeared in no manifest.
- **CSP.** `default-src 'self'` refuses both requests, so both viewers were
  broken in any correctly configured deployment. That neither had been
  reported suggests the CSP was not being applied where it should be.
- **Air gap.** Neither viewer could work without internet access, and
  air-gapped deployment is required for most of the sovereign and Defence
  scope.

Costs and follow-ons:

- three.js moves into the bundle as lazy chunks totalling roughly 250 KB
  compressed, fetched on first use of the 3D viewer. The initial bundle is
  unchanged at 103.67 KB against a 250 KB budget.
- Those lazy chunks exceed the 100 KB route-chunk guidance. three.js cannot
  be made smaller than three.js, and the alternative was the security hole
  above. Recorded as a deliberate, bounded exception rather than an
  oversight.
- Attribution is fixed as a side effect: two MIT libraries were shipping to
  users with no entry in any attribution file, from hosts outside our
  control, at versions nobody had recorded.
- The check scans source text, which is blunt. It would not catch a URL
  assembled at runtime from fragments. It catches the pattern that actually
  occurred, twice, and costs milliseconds.
