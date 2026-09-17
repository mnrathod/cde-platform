# 18. Translations load at runtime, not at build time

Date: 2026-09-17

## Status

Accepted.

## Context

§1.4 asks for "i18n from day one: no hardcoded user-facing strings; ICU
message format; RTL-safe layout". The application had none of it — no
`@angular/localize`, no locale configuration, no `$localize`, and every label
written directly into a template. "Day one" had passed some time ago.

Angular offers two ways to do this, and they are not variations on a theme.

**Compile-time localisation** is the documented default. `ng build` produces
one bundle per locale, with the translated text inlined, and the deployment
picks between them. It is the faster of the two at runtime — there is no
catalogue to fetch, nothing to parse, and the strings are already in the
JavaScript.

It also directly contradicts §9.2, which says "build once, promote the same
artifact through environments (never rebuild per environment)", and §9.3,
which says "the same image runs everywhere". Under compile-time localisation
there is no such thing as *the* artifact: there are *n* of them, and adding a
language means a new build, a new artifact, and a redeployment of something
that no longer matches what was tested. For a product that ships to Azure,
AWS, GCP and air-gapped on-premises installations (§9.3), every one of those
multiplies.

**Runtime localisation** keeps `$localize` calls in the bundle and resolves
them from a catalogue loaded at startup.

## Decision

**Load translations at runtime.** One bundle, one image, and the set of
languages a deployment carries is a file inside that deployment rather than a
property of the build.

`src/main.ts` calls `installTranslations` before `bootstrapApplication`. That
reads `/assets/locale/locales.json` to learn which languages are deployed,
negotiates against `navigator.languages`, fetches
`/assets/locale/messages.<tag>.json` if the answer is not the source locale,
and hands it to `loadTranslations`. It then sets `lang` and `dir` on the
document.

The order is not adjustable. `loadTranslations` has to run before the first
`$localize` tagged string is evaluated, and component code evaluates them on
the first change-detection pass, so doing this in an application initialiser
would already be too late.

Three decisions sit underneath this one.

**Message IDs are written by hand** (`@@login.signInAction`) rather than left
to Angular's content hash. A hashed ID changes when the source text changes,
which silently orphans every translation of a string whose wording was
tweaked. An explicit ID means a reworded source string keeps its
translations, and a translator sees a changed source rather than a new one.

**The source locale is `en-AU`.** It was implicitly `en-US` before, because
that is Angular's default when nothing is declared — which is why the
signature stamp's date moved from "Mar 4, 2026" to "4 Mar 2026" in this
change. That is the locale being honoured rather than a regression, and it
matches the rest of the codebase's assumptions (§6.3, Australian Privacy Act;
`Money amountAud` in §3.2).

**The guard is a script, not a test, and ships with a baseline.**
`scripts/check-i18n-markup.mjs` parses every component template with
Angular's own parser and fails on user-facing text carrying no `i18n`.
Turning that on across 34 components that were never internationalised would
fail on day one and be switched off by the second, so it carries a list of
templates known to be unmarked. The list only shrinks: a file not on it must
be clean, and a file on it must still be dirty, so it cannot decay into a
permanent exemption. This is the ratcheting floor the coverage gates already
use.

It began as a spec, reading sources through `import.meta.glob(..., '?raw')`,
and that quietly destroyed the coverage measurement it sits beside. A source
file pulled into the test bundle as raw text resolves to a one-line string
module, so every component that no test loads dropped to zero countable
lines: `shell.component.ts` went from 106 findable lines to 0, and reported
coverage rose from 43.7% to 69.7% without a single new test being written.
Reading the files from disk, in a script, outside the bundle, is what keeps
both gates honest — and is why its own unit tests run under a separate Vitest
config rather than with the application's.

## Consequences

**Startup pays for this, a little.** One small JSON request sits ahead of
bootstrap. It does not happen for the source locale, which is the common case
— that path costs one manifest fetch and nothing else — and everything in
`installTranslations` fails open, so a missing manifest, an unreachable
catalogue or a truncated file leaves the application rendering source text
rather than failing to render. A translation problem must not become a blank
screen.

**Adding a language needs no rebuild.** Drop `messages.fr.json` next to the
manifest, add a line to the manifest, and French is live. That is what §9.2
was asking for, and it is the main thing this decision buys.

**The catalogue is generated and gated.** `npm run check:i18n` regenerates and
diffs against the committed `src/locale/messages.json`, in the pipeline's
static-analysis stage. A stale catalogue is worse than no catalogue:
translators work from it, so a missing entry ships one untranslated string to
every language at once.

**Strings in expressions need `$localize` explicitly.** `{{ loading() ? "..."
: "..." }}` is an expression, not template text, so the compiler never sees it
as a message and the template guard cannot see it either. Those have to move
into the component as `$localize` tagged templates. This is a real gap in the
guard and is worth knowing about rather than discovering during a translation.

**Most of the application is still untranslated.** The infrastructure, the
guard and two components are done; twenty-two templates remain on the
baseline. The work is mechanical but it is not finished, and the baseline is
the honest record of how much is left.

**RTL is supported but not yet proven.** `dir` is set correctly and the
layout has begun moving to logical properties (`start`/`end` rather than
`left`/`right`), but no right-to-left language is deployed, so nothing has
been seen rendered in one. The claim in this document is that the mechanism
works; it is not a claim that the layout is correct in Arabic.
