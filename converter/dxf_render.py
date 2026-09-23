"""
Turning a DXF into something a browser can show, or a printer can print.

<p>Two outputs from one pipeline: an SVG for the viewer and a PDF for
export. They share the reading, the layout choice and the text overlay, and
differ only in the backend and in how the text is styled, which is why they
sit together rather than in two files that would drift.

<p>The text overlay itself is in ``dxf_text_layer.py`` — see the note there
for why a rendered drawing needs its words put back.
"""
import os
import re
from pathlib import Path
import tempfile
import traceback

from dxf_text_layer import (
    TEXT_LAYER_CLASS, build_text_layer, calibrate_dxf_to_svg,
    _text_elements, _text_entities,
)
from toolchain import make_temp_dir, safe_rmtree, libreoffice_to_pdf

# ── ezdxf ──────────────────────────────────────────────────────
try:
    import ezdxf
    from ezdxf.addons.drawing import RenderContext, Frontend
    from ezdxf.addons.drawing.svg import SVGBackend
    from ezdxf.addons.drawing.properties import LayoutProperties
    EZDXF_OK = True
    EZDXF_ERR = ""
except ImportError as e:
    EZDXF_OK = False
    EZDXF_ERR = str(e)

PRINT_PAGE_MM = (420, 297)          # A4 landscape
PRINT_BACKGROUND = "#ffffff"


def render_dxf(path: str) -> dict:
    try:
        return render_dxf_string(Path(path).read_text(encoding="utf-8", errors="replace"))
    except Exception as e:
        return {"success": False, "error": f"Cannot read DXF: {e}"}


def render_dxf_string(dxf_content: str) -> dict:
    if not EZDXF_OK:
        return {"success": False, "error": f"ezdxf not installed: {EZDXF_ERR}"}
    # Write to temp file — avoids variable-shadowing with ezdxf module name
    tmp_dxf = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".dxf",
                                         encoding="utf-8", delete=False) as f:
            f.write(dxf_content)
            tmp_dxf = f.name
        return _render_dxf_file(tmp_dxf)
    finally:
        if tmp_dxf and os.path.exists(tmp_dxf):
            try: os.unlink(tmp_dxf)
            except: pass


def read_dxf_document(dxf_path: str):
    """
    Open a DXF, falling back to ezdxf's recovery reader.

    Real drawings arrive malformed often enough that the plain reader alone
    would refuse files every CAD package opens without complaint, so a refusal
    here means both readers said no.

    @return (doc, None) or (None, reason) — the reason is written for whoever
            submitted the file, since it is what reaches them.
    """
    import ezdxf as _ezdxf
    import ezdxf.recover as _recover

    try:
        doc = _ezdxf.readfile(dxf_path)
        print("[ezdxf] Normal read OK", flush=True)
        return doc, None
    except Exception as first_error:
        print(f"[ezdxf] Normal read failed: {first_error} — trying recover", flush=True)

    try:
        doc, auditor = _recover.read(dxf_path)
        if auditor.has_errors:
            print(f"[ezdxf] Recover fixed {len(auditor.errors)} errors", flush=True)
        print("[ezdxf] Recover read OK", flush=True)
        return doc, None
    except Exception as recover_error:
        print(f"[ezdxf] Recover also failed: {recover_error}", flush=True)
        return None, f"ezdxf cannot parse DXF: {recover_error}"


def first_populated_layout(doc):
    """
    The layout worth drawing, and how many entities it holds.

    Modelspace normally, but a drawing whose modelspace is empty keeps its
    content in a paper space layout, and rendering the empty one produces a
    blank page rather than an error — the worst of both.

    Both the viewer render and the PDF render choose through here, so the two
    cannot disagree about which sheet a drawing is.
    """
    msp = doc.modelspace()
    entity_count = len(list(msp))
    print(f"[ezdxf] modelspace entities: {entity_count}", flush=True)
    if entity_count:
        return msp, entity_count

    layouts = [l for l in doc.layouts if l.name != "Model"]
    print(f"[ezdxf] Modelspace empty — checking {len(layouts)} paper layouts", flush=True)
    for layout in layouts:
        count = len(list(layout))
        print(f"[ezdxf]   layout '{layout.name}': {count} entities", flush=True)
        if count:
            return layout, count
    return msp, 0


def _render_dxf_file(dxf_path: str) -> dict:
    """Render a DXF file to SVG using ezdxf with recover fallback."""
    doc, read_error = read_dxf_document(dxf_path)
    if doc is None:
        return {"success": False, "error": read_error}

    try:
        # ezdxf 1.4.4 API
        from ezdxf.addons.drawing import RenderContext, Frontend
        from ezdxf.addons.drawing.svg import SVGBackend
        from ezdxf.addons.drawing.layout import Page, Settings
        from ezdxf.addons.drawing.properties import LayoutProperties

        layout_to_render, entity_count = first_populated_layout(doc)

        # ezdxf 1.x: SVGBackend() takes no args; output via get_string(page)
        backend = SVGBackend()
        lp = LayoutProperties.from_layout(layout_to_render)
        lp.set_colors(bg="#1a1d27")
        Frontend(RenderContext(doc), backend).draw_layout(
            layout_to_render, finalize=True, layout_properties=lp)

        page = Page(420, 297)                   # A4 landscape mm
        settings = Settings(fit_page=True)
        svg = backend.get_string(page, settings=settings)

        print(f"[ezdxf] SVG length: {len(svg)}", flush=True)
        if not svg or "<svg" not in svg:
            return {"success": False,
                    "error": f"ezdxf rendered empty SVG. Entities: {entity_count}, "
                             f"version: {doc.dxfversion}. Layers may be frozen/off."}

        # Convert mm dimensions to pixels (96dpi) so browser renders at correct size
        # ezdxf outputs: width="420mm" height="297mm" — replace with px values
        import re
        def mm_to_px(mm): return round(float(mm) * 3.7795275591)
        def replace_dim(m):
            val = m.group(1); unit = m.group(2)
            if unit == 'mm': return f'{mm_to_px(val)}px'
            if unit == 'cm': return f'{mm_to_px(float(val)*10)}px'
            return m.group(0)
        svg = re.sub(r'([\d.]+)(mm|cm)', replace_dim, svg, count=4)
        svg = svg.replace("<svg ",
            '<svg style="max-width:100%;max-height:100%;display:block" ', 1)

        # Added last, so a failure here costs the search layer and not the
        # drawing: a viewer that renders without searchable text is a
        # degraded feature, one that renders nothing is an outage.
        try:
            layer = build_text_layer(svg, layout_to_render, doc)
            if layer:
                svg = svg.replace("</svg>", layer + "</svg>", 1)
        except Exception:
            print(f"[ezdxf] text layer skipped:\n{traceback.format_exc()}", flush=True)

        return {"success": True, "svg": svg,
                "entityCount": entity_count, "dxfVersion": doc.dxfversion}
    except Exception as e:
        print(f"[ezdxf] render error:\n{traceback.format_exc()}", flush=True)
        return {"success": False, "error": f"ezdxf render error: {e}"}



# ══════════════════════════════════════════════════════════════
#  Searchable text layer
# ══════════════════════════════════════════════════════════════
#
#  ezdxf's SVG backend draws TEXT and MTEXT as filled vector paths. That is
#  the right choice for fidelity — a CAD drawing's text is positioned to the
#  millimetre and substituting a browser font moves it — but it means the SVG
#  contains no <text> at all, so nothing downstream can find a word in a
#  drawing. Searching a converted DXF returned "no matches" for text plainly
#  visible on screen, which is a worse answer than an error.
#
#  So the glyphs stay as paths and an invisible <text> layer is laid over
#  them, the same arrangement a PDF viewer uses for its text layer: the
#  drawing renders exactly as before, and the words are in the DOM.




def _print_svg(doc, layout) -> str:
    """The drawing as SVG for export: white ground, no text glyphs."""
    from ezdxf.addons.drawing import RenderContext, Frontend
    from ezdxf.addons.drawing.svg import SVGBackend
    from ezdxf.addons.drawing.layout import Page, Settings
    from ezdxf.addons.drawing.properties import LayoutProperties
    from ezdxf.addons.drawing.config import Configuration, TextPolicy

    config = Configuration(text_policy=TextPolicy.IGNORE)
    backend = SVGBackend()
    layout_properties = LayoutProperties.from_layout(layout)
    # The background is set the same way the viewer sets its dark one, and for
    # the same reason: ezdxf resolves the "use the background's opposite"
    # colour that most CAD entities carry against whatever ground it is told
    # about, so saying white here is what makes the lines black.
    #
    # Not ColorPolicy.COLOR_SWAP_BW, which was the first attempt and produced
    # a page that passed every check and was blank: it swapped the resolved
    # black to white, on white. Every automated assertion held — it was a
    # valid A4 PDF, on a white ground, with correctly placed searchable text —
    # because none of them asked whether the drawing was visible. Hence
    # `_ink_coverage` below, and the test that uses it.
    layout_properties.set_colors(bg=PRINT_BACKGROUND)
    Frontend(RenderContext(doc), backend, config=config).draw_layout(
        layout, finalize=True, layout_properties=layout_properties)
    return backend.get_string(Page(*PRINT_PAGE_MM), settings=Settings(fit_page=True))


def _geometry_of(layout) -> list:
    """Everything in the layout the print render actually draws."""
    return [e for e in layout if e.dxftype() not in ("TEXT", "MTEXT")]


def dxf_file_to_pdf(dxf_path: str) -> dict:
    """
    Render a DXF to a PDF whose text is real text.

    @return {"success": True, "type": "pdf", "pdfBytes": ...} or a refusal
            carrying a reason written for whoever submitted the file.
    """
    if not EZDXF_OK:
        return {"success": False, "error": f"ezdxf not installed: {EZDXF_ERR}"}

    doc, read_error = read_dxf_document(dxf_path)
    if doc is None:
        return {"success": False, "error": read_error}

    try:
        layout, entity_count = first_populated_layout(doc)
        svg = _print_svg(doc, layout)
        if not svg or "<svg" not in svg:
            return {"success": False,
                    "error": f"The drawing rendered empty. Entities: {entity_count}, "
                             f"version: {doc.dxfversion}. Layers may be frozen or off."}

        # Added last and never fatal: a drawing that prints without searchable
        # text is degraded, one that does not print at all is a failure.
        try:
            labels = _text_entities(layout)
            transform = calibrate_dxf_to_svg(svg, _geometry_of(layout)) if labels else None
            if transform is not None:
                layer = (f'<g class="{TEXT_LAYER_CLASS}">'
                         + _text_elements(labels, transform, ' fill="#000000"')
                         + "</g>")
                svg = svg.replace("</svg>", layer + "</svg>", 1)
        except Exception:
            print(f"[dxf-pdf] text layer skipped:\n{traceback.format_exc()}", flush=True)
    except Exception as e:
        print(f"[dxf-pdf] render error:\n{traceback.format_exc()}", flush=True)
        return {"success": False, "error": f"The drawing could not be rendered: {e}"}

    work_dir = make_temp_dir()
    try:
        svg_path = os.path.join(work_dir, "drawing.svg")
        Path(svg_path).write_text(svg, encoding="utf-8")
        result = libreoffice_to_pdf(svg_path)
        if not result.get("success"):
            return result
        return {"success": True, "type": "pdf", "pdfBytes": result["pdfBytes"],
                "dxfVersion": doc.dxfversion, "entityCount": entity_count}
    finally:
        safe_rmtree(work_dir)


def dxf_string_to_pdf(dxf_content: str) -> dict:
    """As above, for a DXF that only exists as text — the DWG converters' output."""
    tmp_dxf = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".dxf",
                                         encoding="utf-8", delete=False) as f:
            f.write(dxf_content)
            tmp_dxf = f.name
        return dxf_file_to_pdf(tmp_dxf)
    finally:
        if tmp_dxf and os.path.exists(tmp_dxf):
            try: os.unlink(tmp_dxf)
            except OSError: pass


# ══════════════════════════════════════════════════════════════
#  DWG magic byte detection
# ══════════════════════════════════════════════════════════════
