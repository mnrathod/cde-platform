# Screen reader test matrix

**No cell in this table has been filled in. Every one is outstanding.**

§1A.5 requires a screen-reader pass over this matrix before each release.
None has happened, which is why the conformance report marks most criteria
Not Evaluated rather than Supports.

## The matrix

Each reader is tested against the browser it is normally paired with. Testing
NVDA in Safari or VoiceOver in Chrome produces failures that no real user
would ever encounter, and misses the ones they would.

| Reader | Platform | Browser | Last run | Result |
|---|---|---|---|---|
| NVDA | Windows | Firefox | — | Not run |
| JAWS | Windows | Chrome | — | Not run |
| VoiceOver | macOS | Safari | — | Not run |
| VoiceOver | iOS | Safari | — | Not run |
| TalkBack | Android | Chrome | — | Not run |

## Journeys each pass must cover

Chosen because each is either a task a user cannot avoid, or a place where
this application does something unusual enough that generic component
accessibility will not save it.

1. **Sign in**, including a failed attempt and the throttling delay. The
   delay must be announced; silence reads as a hung page.
2. **Enrol a second factor.** The manual-entry secret must be readable
   character by character, and the code field must accept a pasted value.
3. **Redeem a recovery code**, which is the path a user takes when they have
   already lost their phone and are least able to cope with friction.
4. **Create a project and upload a document.** Progress and completion must
   announce; a rejected file must say why.
5. **Open a document in the viewer**, move between pages, and use search.
   Search results are now buttons; the count of results must be announced.
6. **Read a model through the IFC tree.** This is the one expected to fail
   outright — see the conformance report.
7. **Move a container through a CDE state transition**, where the
   consequences of a mis-heard control are contractual.
8. **Read and act on an error**, including the correlation ID, which a user
   has to be able to quote to support.

## What a pass records

Not a verdict. A verdict compresses away the only useful part.

- Which journeys completed, which stalled, and where.
- What was announced at each step versus what should have been.
- Anything announced that should not have been — decorative content, ARIA
  that duplicates a visible label, live regions firing on every keystroke.
- Where focus went after each route change, dialog open and dialog close.
- Reader-specific divergences, which are common and often the real finding.

## Why automated testing does not replace this

§1A.5 puts it plainly: automated checks catch a minority of WCAG issues and
passing them is not conformance. axe cannot tell you that a button is
correctly labelled "Submit" but submits something else, that a live region
announces so often it is unusable, or that a reading order is technically
valid and practically incomprehensible.

The automated gate is a floor. This matrix is one of the two real gates; the
other is the manual keyboard pass, which is part of the Definition of Done
rather than a QA hand-off.
