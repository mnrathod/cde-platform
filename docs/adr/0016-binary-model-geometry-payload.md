# 16. A binary payload for model geometry

Date: 2026-09-16

## Status

Accepted.

## Context

Opening an IFC model sent its geometry from the conversion service to the
browser as JSON with four base64-encoded arrays in it: vertex positions,
vertex normals, triangle indices, and a per-vertex colour.

Two things about that were expensive, and they compound.

**The colour array encoded a property of the element type, once per vertex.**
`np.tile(colour[:3], (len(verts), 1))` wrote a `float32x3` copy of the
element's type colour onto every one of its vertices — twelve bytes each, to
say one of sixteen things. A million-vertex model carried 12 MB of tiling.

**base64 costs four bytes of transfer for every three of payload.** The arrays
were already bytes; encoding them as text to put them inside a JSON document
added a third again on top of everything above.

Together, on a model with a million vertices and two million triangles:

| | Per vertex | Per triangle |
|---|---|---|
| positions | 12 B | |
| normals | 12 B | |
| **colours** | **12 B** | |
| indices | | 12 B |
| base64 | **× 1.33** | **× 1.33** |

That is roughly **80 MB in a single JSON response** for an ordinary building.

It was worse than a transfer cost. The converter built the whole string in
memory, and the Spring proxy then read the reply with
`HttpResponse.BodyHandlers.ofString` — materialising all of it as a Java
`String`, which is UTF-16, so twice the bytes on the wire — and
`mapper.readTree` copied it again into a node tree. Hundreds of megabytes of
heap to forward data the service does not even look at, which §7.7 forbids
outright.

The baked-in colour also made the type visibility controls impossible. Every
element was fused into one mesh with one material, so `onVisibilityChanged`
could only log; there was nothing to toggle.

## Decision

**Group the geometry by element type, and send it as bytes.**

The extraction accumulates per IFC type so that each type is one contiguous
run of the index buffer, and emits a group table naming each run with its
colour and opacity. Colour travels once per type in a header, not once per
vertex. The buffers travel as themselves.

The container is deliberately dull — magic, version, a JSON header, then the
three buffers, little-endian, aligned to four bytes:

```
magic        4   "CDEG"
version      4   uint32
headerLength 4   uint32
header       n   UTF-8 JSON: counts, schema, bounds, groups
                 (each group: type, start, count — index units —
                  color, opacity, elementCount)
padding      0-3 zero bytes, to a 4-byte boundary
positions        float32 x 3 x vertexCount
normals          float32 x 3 x vertexCount
indices          uint32  x 3 x triangleCount
```

The padding is load-bearing: the reader takes typed-array views straight over
the received bytes, and `Float32Array` and `Uint32Array` both throw on a
byteOffset that is not a multiple of four.

**It is served from a new sub-resource, `GET /api/viewer3d/{id}/geometry`,**
parallel to the existing `/tree`. The media type of an endpoint is part of the
published contract even where §3.5 documents the body inside it as opaque, so
changing the existing route's content type would be a breaking change under
§3.4. Adding a sibling is additive. Spring streams the converter's reply
through with `BodyHandlers.ofInputStream` and an `InputStreamResource`, so the
payload is never materialised in the proxy.

## Consequences

Vertex data drops from ~48 B/vertex on the wire to 24 B — **about half** —
before any compression, and the proxy stops holding the whole model in heap.

Groups make per-type materials possible, which is what the dead visibility
toggles in `viewer3d.component` were always missing. This ADR does not wire
them up; it removes the reason they could not work.

The type colour table always carried an alpha channel that `colour[:3]`
discarded, so glazing rendered as solid as a wall. Per-type materials can
honour it, and now do.

Each group also carries `elementCount` — how many elements of that type the
extractor read, which is not derivable from the geometry: two walls welded
into one bucket look exactly like one long wall. It was added after this
format shipped, so a reader must treat its absence as unknown rather than as
zero elements, and `decodeGeometryContainer` normalises it for that reason.

This turned out to matter more than a statistic. The viewer's model tree
fabricated its contents whenever no hierarchy endpoint answered — ten fixed
IFC types, each given `Math.floor(Math.random() * 20) + 1` as a quantity — and
§1A.4 makes that tree the accessible equivalent of a canvas some readers
cannot see at all. Carrying a counted number is what allowed the invention to
be deleted rather than merely flagged. The derived tree is flat, with no
storeys in it; one level of real types beats three levels of invented ones.

Two encoders exist in the conversion service: `ifc_geometry_json` for the
older route, and `ifc_geometry_binary` for this one. They share one
extraction, so the duplication is a serialisation call, not the logic. The
older route remains a candidate for deprecation under §3.4's notice period
once no client calls it.

**This is not compression.** Draco or meshopt would compress the remaining
buffers by a further order of magnitude, and both are narrow Wasm decode
kernels of exactly the shape §12.4 describes — operating on geometry we
produce ourselves, so no untrusted parsing moves into the browser. That
decision needs the profiling evidence §12.1 requires, which this change makes
measurable for the first time. It is deliberately not taken here.

## Verification

The two implementations are checked against each other rather than against a
shared assumption: the browser reader's tests decode a container that the
Python encoder actually produced, whose header length happens to be 319 bytes
so the one-byte padding case is exercised rather than skipped.

Each guard was confirmed by breaking the code it guards — dropping the
padding, emitting group offsets in vertex units, omitting the inter-type
vertex shift, reintroducing a colour buffer, discarding the alpha, and
swapping the buffer order — and checking that a test failed each time. One
opacity assertion passed through a synthetic fixture and therefore caught
nothing; it was rewritten to run the real assembly against the real table.
