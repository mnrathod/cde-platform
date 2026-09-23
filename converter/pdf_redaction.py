"""
Covering text in a PDF so it is gone, and burning markup into one so it is
permanent.

<p>Two operations, one mechanism. A redaction that only draws a black
rectangle over the words leaves the words in the file for anyone who
selects them, which is the failure this exists to prevent, so the page is
rasterised and rebuilt (see ``pdf_page_rewrite``). Flattening annotations
has the same requirement from the other direction: the markup has to stop
being an annotation a viewer can hide or delete.
"""
import os

from pdf_page_rewrite import (
    _draw_redactions, _draw_shapes, _redaction_rects, _rewrite_pdf_pages,
)
from pdf_text_search import _group_by_page

# Render DPI for pages that get rasterized. Must be passed to BOTH the
# render call and the save call — see _rewrite_pdf_pages.
REDACTION_DPI = 150
FLATTEN_DPI   = {"screen": 150, "print": 300}


def redact_pdf(path: str, regions: list, output: str = "", burn: bool = True,
               restore_text_layer: bool = True) -> dict:
    """
    Redact rectangular regions from a PDF.
    burn=True  → permanently destroy content (production)
    burn=False → add black rectangle overlay only (preview)

    regions: [{page, x, y, width, height, reason}]
    Coordinates are in PDF points (72 pt = 1 inch).
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}
    if not regions:
        return {"success": False, "error": "No redaction regions provided"}

    try:
        import pypdfium2 as pdfium

        # Drop regions pointing outside the document rather than failing the
        # whole request over one bad index.
        doc = pdfium.PdfDocument(path)
        page_count = len(doc)
        doc.close()

        by_page = {
            idx: items
            for idx, items in _group_by_page(regions).items()
            if 0 <= idx < page_count
        }
        if not by_page:
            return {"success": False,
                    "error": f"No regions fall within this document's {page_count} page(s)"}

        if not output:
            base = os.path.splitext(path)[0]
            output = base + ("_redacted.pdf" if burn else "_redact_preview.pdf")

        redacted_pages = 0
        if burn:
            redacted_pages = _rewrite_pdf_pages(
                path, output, REDACTION_DPI, by_page, _draw_redactions,
                drop_annots_in=_redaction_rects,
                restore_text_layer=restore_text_layer
            )
        # Preview mode draws the overlay client-side; nothing to write here.

        return {
            "success": True,
            "outputPath": output,
            "redactedPages": redacted_pages,
            "totalPages": page_count,
            "totalRegions": len(regions),
            "mode": "permanent" if burn else "preview"
        }

    except ImportError as e:
        return {"success": False, "error": f"Required library missing: {e}. Run: pip install pypdfium2 pypdf Pillow"}
    except Exception as e:
        return {"success": False, "error": str(e)}



def flatten_annotations_to_pdf(path: str, shapes: list, output: str = "", quality: str = "screen") -> dict:
    """
    Flatten annotation shapes onto a PDF by rendering pages to images
    and drawing the shapes using PIL.

    shapes: [{tool, pageNumber, color, strokeWidth, x1,y1,x2,y2, ...}]
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}

    try:
        if not output:
            base   = os.path.splitext(path)[0]
            output = base + "_annotated.pdf"

        dpi     = FLATTEN_DPI.get(quality, FLATTEN_DPI["screen"])
        by_page = _group_by_page(shapes, key="pageNumber")

        flattened_pages = _rewrite_pdf_pages(
            path, output, dpi, by_page, _draw_shapes
        )

        return {
            "success":        True,
            "outputPath":     output,
            "flattenedPages": flattened_pages,
            "shapes":         len(shapes),
            "dpi":            dpi
        }

    except ImportError as e:
        return {"success": False, "error": f"Missing library: {e}. Run: pip install pypdfium2 pypdf Pillow"}
    except Exception as e:
        return {"success": False, "error": str(e)}


# ══════════════════════════════════════════════════════════════════
