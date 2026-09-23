"""
Putting the drawing's text back on top of the rendered picture, as text.

<p>ezdxf's SVG backend draws TEXT and MTEXT as paths. That looks right and
is useless to everything else: it cannot be selected, copied, searched,
found by a redaction sweep, or read by a screen reader, so a rendered
drawing is a picture of words rather than words.

<p>So the entities are read a second time and emitted as real ``<text>``
elements over the drawing. The hard part is placement: the backend picks
its own viewBox, so a drawing coordinate has to be mapped onto the rendered
one, which is what the calibration below works out by comparing the extent
ezdxf actually drew against the extent the entities occupy.
"""
import html
import re

TEXT_LAYER_CLASS = "cde-text-layer"


def mtext_string(entity) -> str:
    """
    An MTEXT's content with its formatting codes stripped.

    ezdxf renamed this method: it is `plain_text()` from 1.x, and was
    `plain_mtext()` before. Both are tried because calling the wrong one
    raises AttributeError, and every call site here sat inside a broad
    `except` — so the rename did not fail loudly, it just meant no MTEXT
    was ever read.
    """
    for name in ("plain_text", "plain_mtext"):
        method = getattr(entity, name, None)
        if callable(method):
            return str(method())
    return str(entity.dxf.get("text", "") or "")

# TEXT halign codes map onto SVG's anchors. Anything else (aligned, fit)
# positions by two points and is treated as left-aligned.
_H_ALIGN_TO_ANCHOR = {0: "start", 1: "middle", 2: "end", 4: "middle"}


def _rendered_extent(svg: str) -> tuple:
    """Absolute min/max of every point the backend drew."""
    min_x = min_y = float("inf")
    max_x = max_y = float("-inf")
    found = False
    for data in re.findall(r'<path[^>]*\sd="([^"]+)"', svg):
        x = y = 0.0
        # Only M/m and L/l appear in this backend's output; anything else is
        # skipped rather than guessed at.
        for cmd, args in re.findall(r'([MmLl])\s*([-\d.,eE\s]*)', data):
            values = [float(v) for v in re.findall(r'-?\d+\.?\d*(?:[eE]-?\d+)?', args)]
            for i in range(0, len(values) - 1, 2):
                dx, dy = values[i], values[i + 1]
                if cmd in "ML":
                    x, y = dx, dy
                else:
                    x, y = x + dx, y + dy
                min_x, min_y = min(min_x, x), min(min_y, y)
                max_x, max_y = max(max_x, x), max(max_y, y)
                found = True
    return (min_x, min_y, max_x, max_y) if found else None


def _text_entities(layout):
    """Every TEXT/MTEXT in the layout, as (string, x, y, height, anchor)."""
    out = []
    for e in layout:
        kind = e.dxftype()
        if kind not in ("TEXT", "MTEXT"):
            continue
        try:
            if kind == "TEXT":
                content = e.dxf.text
                height = float(e.dxf.height or 0)
                halign = int(getattr(e.dxf, "halign", 0) or 0)
                # With any alignment other than left, the insert point is not
                # where the text starts — align_point is.
                point = e.dxf.align_point if halign else e.dxf.insert
                if point is None:
                    point = e.dxf.insert
                anchor = _H_ALIGN_TO_ANCHOR.get(halign, "start")
            else:
                content = mtext_string(e)
                height = float(e.dxf.char_height or 0)
                point = e.dxf.insert
                anchor = "start"
            content = " ".join(str(content).split())
            if content and height > 0:
                out.append((content, float(point[0]), float(point[1]), height, anchor))
        except Exception as exc:
            # One unreadable label must not cost the whole layer — but say so.
            # Silently skipping is how a systematic failure, like a renamed
            # ezdxf method, looks identical to a drawing with no text.
            print(f"[text-layer] skipped {kind}: {exc}", flush=True)
            continue
    return out


def calibrate_dxf_to_svg(svg: str, measured_entities) -> tuple:
    """
    The scale that maps DXF coordinates onto the coordinates ezdxf drew in.

    Calibrated rather than assumed: the extent of {@code measured_entities} in
    DXF units is matched against the extent the backend actually drew, which
    gives the transform whatever page size or fit ezdxf chose.

    {@code measured_entities} must be the entities that were *drawn*. The
    viewer render draws text as glyph paths and passes the whole layout; the
    print render draws no text at all and passes only geometry. Passing the
    wrong set silently shifts every label, which is why it is a parameter
    rather than a default.

    @return (svg_min_x, svg_max_y, dxf_min_x, dxf_min_y, scale), or None when
            the calibration cannot be trusted. A text layer in the wrong place
            is worse than none: the drawing looks right and every search result
            points somewhere else.
    """
    drawn = _rendered_extent(svg)
    if not drawn:
        return None
    svg_min_x, svg_min_y, svg_max_x, svg_max_y = drawn

    try:
        from ezdxf import bbox
        extents = bbox.extents(measured_entities, fast=True)
        dxf_min_x, dxf_min_y = extents.extmin.x, extents.extmin.y
        dxf_max_x, dxf_max_y = extents.extmax.x, extents.extmax.y
    except Exception:
        return None

    dxf_w, dxf_h = dxf_max_x - dxf_min_x, dxf_max_y - dxf_min_y
    svg_w, svg_h = svg_max_x - svg_min_x, svg_max_y - svg_min_y
    if dxf_w <= 0 or dxf_h <= 0 or svg_w <= 0 or svg_h <= 0:
        return None

    scale_x, scale_y = svg_w / dxf_w, svg_h / dxf_h
    # ezdxf preserves aspect ratio. If these disagree, the assumption behind
    # the whole mapping is wrong and the layer is dropped rather than placed
    # somewhere plausible but incorrect.
    if not (0.9 <= scale_x / scale_y <= 1.1):
        return None
    return svg_min_x, svg_max_y, dxf_min_x, dxf_min_y, (scale_x + scale_y) / 2


def _text_elements(labels, transform, fill: str) -> str:
    """The <text> elements for one calibrated set of labels."""
    svg_min_x, svg_max_y, dxf_min_x, dxf_min_y, scale = transform
    parts = []
    for content, x, y, height, anchor in labels:
        sx = svg_min_x + (x - dxf_min_x) * scale
        sy = svg_max_y - (y - dxf_min_y) * scale
        size = max(height * scale, 1.0)
        parts.append(
            f'<text x="{sx:.2f}" y="{sy:.2f}" font-size="{size:.2f}" '
            f'text-anchor="{anchor}"{fill}>{html.escape(content)}</text>')
    return "".join(parts)


def build_text_layer(svg: str, layout, doc) -> str:
    """
    An invisible <text> element over each rendered label, for the viewer.

    The glyphs are already on screen as paths; this layer exists so the words
    are in the DOM and can be found. A y flip is included because DXF counts
    upwards from the bottom and SVG downwards from the top.
    """
    labels = _text_entities(layout)
    if not labels:
        return ""

    # The viewer render draws text as glyph paths, so the drawn extent
    # includes them and the whole layout is the right thing to measure.
    transform = calibrate_dxf_to_svg(svg, layout)
    if transform is None:
        return ""

    # fill:none keeps the layer invisible; the glyphs beneath it are already
    # drawn. pointer-events:none keeps it from swallowing clicks meant for the
    # drawing, and it stays selectable and searchable either way.
    return (f'<g class="{TEXT_LAYER_CLASS}" fill="none" stroke="none" '
            f'pointer-events="none">'
            + _text_elements(labels, transform, "") + "</g>")


# ══════════════════════════════════════════════════════════════
#  DXF -> PDF, for export rather than for the screen
# ══════════════════════════════════════════════════════════════
#
#  The viewer wants a drawing on a dark background with its text as glyph
#  paths for fidelity, and an invisible <text> layer over the top so words can
#  be found. A PDF wants the opposite of both.
#
#  Dark background: a drawing exported on near-black is wrong on paper and
#  wrong in every PDF reader's default view, so the print render swaps to a
#  white ground and swaps black/white pen colours with it — what a CAD package
#  does when it plots.
#
#  Text: LibreOffice carries visible SVG <text> into the PDF as real text and
#  drops text it cannot see, so the invisible-overlay trick that works in a
#  browser produces a PDF with no searchable text at all. The print render
#  therefore tells ezdxf not to draw text, and supplies the same labels as
#  visible <text> instead. The words end up as selectable, extractable PDF
#  text rather than vector outlines — which is what §1A.4 asks of an export,
#  and better than the viewer manages.
