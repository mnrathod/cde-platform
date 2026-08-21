# CDE mobile SDK

Embeddable SDKs for viewing and marking up CDE documents on Android and iOS,
with an offline queue so work on site is not lost to a missing signal.

Both platforms render documents with the operating system's own PDF engine —
`PDFKit` on iOS, `android.graphics.pdf.PdfRenderer` on Android. Neither ships a
rendering engine of its own, and neither uses a WebView for PDF.

---

## Status: compiled and tested off-device, never run on a device

Both SDKs compile and both parity suites pass — 17 tests each, the same
vectors on both platforms.

| | Compiled | Tests | How |
|---|---|---|---|
| **iOS** | Everything except the UIKit layer | 17/17 | `swift build && swift test`, Swift 6.3.3, Swift 6 language mode |
| **Android** | Every source file, against the real framework | 26/26 | [`tools/jvm-verify`](tools/jvm-verify), Kotlin 2.4.10, API 37 |

The extra nine on Android cover [`ViewportTransform`](android/cde-sdk/src/main/kotlin/com/cde/sdk/ui/ViewportTransform.kt),
which holds the arithmetic relating page, view and bitmap coordinates. It was
pulled out of the view precisely so it could be tested without a device: the
page and the markup over it being drawn at different scales is the one viewer
fault that produces no error and no visual glitch, only markup that sits in the
wrong place.

**What that does not cover.** No device, no simulator, no emulator has run any
of this. Specifically still unverified:

- **`CdeViewerController`, `CdeViewer`, `MarkupOverlayView` (iOS)** are behind
  `#if canImport(UIKit)`, so on Linux they compile to nothing. The whole iOS
  view layer — PDFKit, the coordinate mapping, touch handling — is unchecked.
  This is the largest gap. Every PDFKit, UIKit and CoreGraphics symbol they use
  has been checked against Apple's own symbol tables, which is not the same as
  compiling them.
  Two things to watch on the first run, both of which would look like a working
  viewer that puts markup in the wrong place:
  **(a)** whether a shape drawn on a page lands where the browser draws it —
  the y-flip between shape data and PDF page space is the thing being tested;
  **(b)** whether strokes stay crisp through a pinch, or blur. Blurring means
  PDFKit is scaling the overlay as an image rather than resizing it, and the
  repaint on `layoutSubviews` is not being reached.
- **The Android Gradle Plugin build itself.** Sources are type-checked, but the
  AGP 9 configuration in `android/build.gradle.kts` has never been executed —
  AGP is published only to Google's Maven repository, which was unreachable
  here. AGP 9 is a breaking major release: it removed the `kotlin-android`
  plugin in favour of built-in Kotlin, and removed `kotlinOptions`. Both are
  accounted for, neither has been proven. **Expect this file, specifically, to
  need adjusting on the first real build.**
- **The Android UI and renderer** type-check against the real framework, and
  their coordinate arithmetic is tested, but nothing has drawn a frame.
  Robolectric would execute these views on the JVM and is the obvious next
  step; it depends on `androidx.test:monitor`, which lives on Google's Maven
  repository, so it could not be run from here either.
  Worth exercising first, because each fails quietly rather than loudly:
  a second finger landing mid-stroke should **abandon** the shape, not record
  it; a page larger than the memory budget should render soft rather than
  crash; and the page should still be there after a dozen zooms — a blank one
  means a cached bitmap is being recycled while a view still holds it.
- **Nothing has talked to a real server.** The contract was read from a running
  one, but no SDK code has made a request against it.
- **`PdfRenderer` and `PDFKit`** have never opened an actual PDF here.
- **`TokenStore`'s encryption.** It type-checks against the real framework, but
  no keystore has issued a key. Confirm a token survives a restart, and that a
  restore onto a new device signs the user out rather than crashing.

So: the geometry, the wire format, the measurement maths and every API
signature are checked by a compiler and a test run. Rendering, gestures and
networking are reviewed code that has not executed.

The API contract remains the other verified piece: every endpoint, payload
shape, enum value and field name in [API-CONTRACT.md](API-CONTRACT.md) was read
from a running server rather than inferred from the backend source.

Before trusting this on a device:

1. Build both properly — `./gradlew :cde-sdk:assembleRelease` with an Android
   SDK present, and the Xcode toolchain for the UIKit layer.
2. Check `shapeData` round-trips against a real annotation created by the web
   viewer. Nothing on the wire enforces that format — see below.
3. Exercise the offline queue with the network genuinely off, not just with
   the server stopped.

### Running the checks that do work without a device

```bash
# iOS — logic layers and the parity suite. Needs any Swift 6 toolchain.
cd ios && swift test

# Android — every source file type-checked against the real framework
# classes, plus the parity suite. Needs only a JDK; no Android SDK.
cd tools/jvm-verify && ./gradlew test
```

`tools/jvm-verify` exists because the Android Gradle Plugin is published only
to Google's Maven repository, which some networks block. It
compiles the SDK's own sources — not a copy — against Robolectric's
`android-all` from Maven Central, which carries the real AOSP framework
classes. It is a checking harness, not a second way to build the library: the
shipping artefact comes from `./gradlew :cde-sdk:assembleRelease`.

---

## Architecture

Both SDKs are the same design in two languages, deliberately:

```
CdeSdk / CdeSDK            entry point — session, browsing, opening, annotations
├── net / Networking       HTTP against the verified contract; one error type
├── model / Models         documents, annotations, ShapeData, MarkupCodec
├── markup / Markup        geometry and measurement — pure, testable, no UI
├── render / Rendering     native PDF rasterisation
├── offline / Offline      file cache + outbound queue + sync engine
└── ui / UI                drop-in viewer, markup overlay
```

The layers are usable separately. A host app that already has its own viewer
can take everything below `ui`; one that only wants a viewer can take
`CdeViewerView` / `CdeViewerController` and never see the rest.

### Why the markup engine is duplicated rather than shared

`shapeData` is stored by the server as an opaque string it never parses. There
is no schema, so nothing fails when two clients disagree about what a shape
means — the markup simply reads differently depending on which client drew it.

The three implementations (TypeScript, Kotlin, Swift) are therefore kept in
step by test vectors rather than by a shared binary. The parity suites carry
identical inputs and expected outputs. A cross-platform core would be a fourth
thing to build and ship for logic that amounts to some geometry and a shoelace
formula.

### One deliberate difference from the web viewer

The web closes a click-built shape on a double-click. **The mobile SDKs do
not.** A double *tap* fights the platform's own double-tap-to-zoom and asks for
two precise touches in one spot. Instead, on touch a shape closes by:

- tapping its **first** vertex, or
- tapping the **vertex just placed**, or
- the host app calling `finishShape()` from a Done control.

Both positional gestures also exist on the web, so a shape closed on a phone is
closed the same way it would be in a browser. The divergence is asserted in
both parity suites so it reads as a decision rather than an omission.

---

## Android

```kotlin
val cde = CdeSdk(context, CdeConfiguration(baseUrl = "https://cde.example.com"))
cde.signIn("username", "password")

val documents = cde.documents(projectId = 1)
val opened = cde.open(documents.first().id)
val shapes = MarkupCodec.decodeAll(cde.annotations(documents.first().id))

viewer.show(opened, shapes)
viewer.activeTool = MarkupTool.AREA
viewer.onShapeCompleted = { shape ->
    lifecycleScope.launch { cde.addAnnotation(documentId, shape) }
}
```

`minSdk` is 23 — where the keystore can hold an AES key, which is what
`TokenStore` needs to encrypt the session token at rest. `PdfRenderer` itself
arrives at 21, but shipping an SDK that keeps a bearer token in cleartext to
reach two releases from 2014 is not a trade worth making. Raise it freely;
nothing here assumes 23.

**Build:** `cd android && ./gradlew :cde-sdk:assembleRelease`
**Test:** `./gradlew :cde-sdk:test` — the parity suite runs on the JVM, no
device needed.

### Dependencies

There are three, all on the runtime path, and no AndroidX at all:

| | | |
|---|---|---|
| `kotlinx-coroutines-android` | 1.11.0 | structured concurrency for the sync engine |
| `kotlinx-serialization-json` | 1.11.0 | the `shapeData` codec and every payload |
| `okhttp` | 5.5.0 | HTTP |

Credential storage uses the Android keystore directly rather than
`androidx.security:security-crypto`, which Google deprecated in April 2025 in
favour of exactly that. It never left alpha, and an SDK should not hand host
apps a dependency that is no longer maintained.

## iOS

```swift
let cde = CdeSDK(configuration: .init(baseURL: URL(string: "https://cde.example.com")!))
try await cde.signIn(username: "…", password: "…")

let documents = try await cde.documents(projectId: 1)
let opened = try await cde.open(documentId: documents[0].id)
let shapes = MarkupCodec.decodeAll(try await cde.annotations(documentId: documents[0].id))

viewer.show(opened, shapes: shapes)
viewer.activeTool = .area
viewer.onShapeCompleted = { shape in
    Task { try await cde.addAnnotation(documentId: documents[0].id, shape: shape) }
}
```

iOS 16+, **no third-party dependencies at all**: `PDFKit`, `URLSession`,
`CryptoKit`, the keychain and the file system are all system frameworks. An SDK
that drags in a dependency tree is an SDK that host apps fight with.

16 is where `PDFPageOverlayViewProvider` arrives. Markup is drawn into a
**per-page overlay** that PDFKit positions itself, which is what allows the
viewer to scroll continuously: each page carries its own markup, so nothing has
to work out which page a shape belongs to from the scroll position. One overlay
across the whole view cannot do that — it has to map every shape through
whichever page is currently "current", and draws the wrong page's markup during
a scroll. Supporting iOS 13 would mean shipping that second, worse viewer
alongside this one.

Each overlay measures its own scale by asking the same coordinate mapping the
shapes use, rather than reading `PDFView.scaleFactor`. PDFKit does not document
what transform it gives an overlay, and a scale taken from anywhere else can
disagree with where the shapes actually land.

The package is `swift-tools-version: 6.2`, so targets build in Swift 6 language
mode with strict concurrency checking on. It compiles clean under it — the
actor and `Sendable` boundaries are checked by the compiler, not asserted in a
comment.

**Build:** `cd ios && swift build`
**Test:** `swift test`

---

## Offline behaviour

The part most likely to be got subtly wrong, so the rules are stated:

| | |
|---|---|
| **Reads** | Server first, cache when there is no connection. Callers never branch on connectivity. |
| **Writes** | Queued locally first, then sent. `addAnnotation` succeeds with no signal. |
| **Ordering** | Replayed oldest first; a failure stops the run rather than skipping ahead. An update replayed before its create would be rejected. |
| **Rejections** | 400/403/404 are dropped and reported — replaying them can never succeed. Only transport failures and 503 are retried. |
| **Deletes** | A 404 on a delete counts as success. The intent has been achieved, and treating it as failure would wedge the queue. |
| **Conflicts** | Last write wins. The server holds no version on an annotation, so there is nothing to merge against; the SDK does not pretend otherwise. |
| **Sign-out** | Clears cached documents, **keeps** the queue unless you ask otherwise. Markup drawn on site exists nowhere else. |

Cache keys are the document URL, which already carries `?v=`. A new version is
a new key, so a stale copy can never be served — freshness is the server's
statement, not the cache's guess.

## Not included

Stated plainly so nobody discovers it mid-integration:

- **CAD markup on iOS** is drawn over a `WKWebView` showing the converted SVG.
  Coordinates line up, but there is no layer control or entity picking as there
  is on the web.
- **Drawings on Android** are returned by `CdeSdk.open` and left to the host to
  render; `CdeViewerView` handles PDF only.
- **Redaction, OCR, flatten, signatures and version history** are reachable
  through the API but have no SDK surface yet.
- **Real-time collaboration** — the web viewer's presence and live cursors are
  not implemented on mobile.
- **Text search and selection** inside PDFs. `PDFKit` supports both natively
  and it would be a small addition on iOS; Android would need more.
