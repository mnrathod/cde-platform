"""
Rewriting a PDF's pages as images, with whatever has to be drawn on them.

<p>This is the machinery underneath both redaction and annotation
flattening. Both need the same thing: render a page, draw on the raster,
and put the result back in a way that leaves nothing of the original
underneath — which is the whole point of a redaction and the reason the
page is rasterised rather than covered with a black rectangle.

<p>Rasterising costs the page its text, so where the original had text the
rewritten page gets an OCR layer back (``_searchable_page``). Without that,
redacting one name from a contract would silently make the other ninety-nine
pages unsearchable.
"""
import io

from toolchain import find_tesseract


def _rewrite_pdf_pages(path: str, output: str, dpi: int,
                       edits_by_page: dict, draw_page,
                       drop_annots_in=None, restore_text_layer: bool = False) -> int:
    """
    Rebuild a PDF, rasterizing ONLY the pages that carry edits and leaving
    every other page untouched.

    Leaving untouched pages alone matters for correctness, not just speed:
    rasterizing a page discards its vector content and its text layer, so
    blanket-rasterizing silently makes an ENTIRE document unsearchable
    because one region on one page was redacted.

    The document is cloned and the affected pages have their content
    *replaced* in place, rather than being rebuilt page by page into an empty
    writer. Rebuilding dropped the document catalog — most visibly /AcroForm,
    which meant redacting or flattening any page destroyed the form fields on
    every page, and a later form-fill reported the file as having no form at
    all. Cloning keeps the catalog, the page objects and their annotations;
    only the page's own drawing content is swapped for the raster.

    The render DPI and the DPI declared when saving must be the same number,
    or every rasterized page is rescaled by their ratio — rendering at
    scale=2 (144dpi) while declaring resolution=150 shrank every page to
    96% of its original size.

    draw_page(draw, items, scale_x, scale_y, page_width, page_height)
    performs the actual drawing.

    drop_annots_in(items, page_width, page_height) -> list of rectangles whose
    annotations must not survive. Redaction supplies this: a widget sitting
    over redacted content would still carry its value in the file even though
    the pixels beneath it are gone.

    restore_text_layer re-runs OCR over any page that had extractable text
    before it was rasterized. Rasterizing destroys the whole page's text, so
    redacting one email address from a report made the entire page
    unsearchable — a document you searched to find something became unusable
    by removing it. The redacted areas are solid black, so nothing sensitive
    is recovered.

    Returns the count of rasterized pages.
    """
    import pypdfium2 as pdfium
    from PIL import ImageDraw
    import pypdf
    import io

    src     = pdfium.PdfDocument(path)
    writer  = pypdf.PdfWriter(clone_from=path)
    touched = 0
    orphaned = []          # annotations removed, to prune from the AcroForm

    try:
        for i, items in sorted(edits_by_page.items()):
            if not items or i >= len(src):
                continue

            page   = src[i]
            pw, ph = page.get_width(), page.get_height()
            # Checked before rasterizing: afterwards there is no text left to
            # tell whether the page ever had any.
            had_text = restore_text_layer and _page_has_text(page)
            img    = page.render(scale=dpi / 72.0).to_pil().convert("RGB")
            iw, ih = img.size

            draw_page(ImageDraw.Draw(img, "RGBA"), items, iw / pw, ih / ph, pw, ph)

            raster = _searchable_page(img, dpi) if had_text else None
            if raster is None:
                buf = io.BytesIO()
                img.save(buf, "PDF", resolution=dpi)
                buf.seek(0)
                raster = pypdf.PdfReader(buf).pages[0]

            _stamp_page_content(writer.pages[i], raster)
            target = writer.pages[i]

            if drop_annots_in:
                orphaned += _remove_annotations_within(
                    target, drop_annots_in(items, pw, ph))

            touched += 1

        _prune_acroform_fields(writer, orphaned)
    finally:
        src.close()

    with open(output, "wb") as fh:
        writer.write(fh)
    return touched


def _searchable_page(image, dpi: int):
    """
    Turn a rendered page image back into a page with an invisible text layer.

    Used after redaction so the content that was *not* removed stays
    searchable and selectable. Returns None when OCR is unavailable or fails —
    the caller falls back to the plain image, because losing searchability is
    a far better outcome than failing a redaction that has already been
    computed.
    """
    tess = find_tesseract()
    if not tess:
        return None

    try:
        import pytesseract
        import pypdf
        import io

        pytesseract.pytesseract.tesseract_cmd = tess
        page_pdf = pytesseract.image_to_pdf_or_hocr(
            image, extension="pdf", config=f"--dpi {dpi}")
        pages = pypdf.PdfReader(io.BytesIO(page_pdf)).pages
        return pages[0] if pages else None
    except Exception as e:
        print(f"[redact] could not restore the text layer: {e}", flush=True)
        return None


def _stamp_page_content(target, replacement) -> None:
    """
    Replace a page's drawing content with another page's, in place.

    Used by every operation that rebuilds a page — redaction, flattening, OCR.
    Replacing content rather than swapping the whole page keeps the page
    object, so its annotations, its form widgets and the document catalog that
    references it all survive. Rebuilding pages into a fresh writer instead
    dropped /AcroForm, which is why OCR'ing a document used to leave it with
    no fillable fields at all.

    A replacement of a different size is scaled to the target's box; a page
    produced at a different DPI than the original would otherwise be stamped
    at the wrong scale and clipped.
    """
    from pypdf import Transformation

    target.replace_contents(None)

    target_width  = float(target.mediabox.width)
    target_height = float(target.mediabox.height)
    source_width  = float(replacement.mediabox.width)
    source_height = float(replacement.mediabox.height)

    if (source_width <= 0 or source_height <= 0
            or (abs(source_width - target_width) < 0.5
                and abs(source_height - target_height) < 0.5)):
        target.merge_page(replacement)
        return

    target.merge_transformed_page(replacement, Transformation().scale(
        target_width / source_width, target_height / source_height))


def _remove_annotations_within(page, rects: list) -> list:
    """
    Delete annotations whose rectangle overlaps any of the given areas, and
    return what was removed.

    Rasterizing destroys the pixels but not the objects drawn on top of them,
    so a form field or note sitting over a redacted region would keep its
    contents in the file. Rectangles are in PDF points, origin bottom-left.
    """
    from pypdf.generic import ArrayObject, NameObject

    annots = page.get("/Annots")
    if not annots or not rects:
        return []

    def overlaps(rect) -> bool:
        try:
            ax0, ay0, ax1, ay1 = (float(v) for v in rect)
        except (TypeError, ValueError):
            return False
        lo_x, hi_x = min(ax0, ax1), max(ax0, ax1)
        lo_y, hi_y = min(ay0, ay1), max(ay0, ay1)
        return any(lo_x < rx1 and hi_x > rx0 and lo_y < ry1 and hi_y > ry0
                   for rx0, ry0, rx1, ry1 in rects)

    kept, removed = ArrayObject(), []
    for ref in annots:
        if overlaps(ref.get_object().get("/Rect", [])):
            removed.append(ref)
        else:
            kept.append(ref)

    page[NameObject("/Annots")] = kept
    return removed


def _prune_acroform_fields(writer, orphaned: list) -> None:
    """
    Drop redacted widgets from the document's AcroForm field list.

    Removing a widget from its page is not enough on its own: the field is
    also listed in the catalog's /AcroForm /Fields, and a field reachable from
    there keeps its value in the file whatever the page shows. For a
    single-widget field — what most generators emit — the widget and the field
    are one object, so the same reference appears in both places.
    """
    if not orphaned:
        return

    from pypdf.generic import ArrayObject, IndirectObject, NameObject

    acroform = writer._root_object.get("/AcroForm")
    if acroform is None:
        return
    # /AcroForm is normally an indirect reference. Assigning through the
    # reference updates the proxy, not the dictionary the catalog points at,
    # so resolve it before writing back.
    acroform = acroform.get_object()
    if "/Fields" not in acroform:
        return

    def key(ref):
        return ref.idnum if isinstance(ref, IndirectObject) else id(ref)

    removed = {key(ref) for ref in orphaned}
    acroform[NameObject("/Fields")] = ArrayObject([
        ref for ref in acroform["/Fields"] if key(ref) not in removed
    ])


def _redaction_rects(regions: list, page_w: float, page_h: float) -> list:
    """Redacted areas as (x0, y0, x1, y1) in PDF points, origin bottom-left."""
    rects = []
    for region in regions:
        x = float(region.get("x", 0))
        y = float(region.get("y", 0))
        w = float(region.get("width", 100))
        h = float(region.get("height", 20))
        rects.append((x, y, x + w, y + h))
    return rects


def _draw_redactions(draw, regions, scale_x, scale_y, page_w, page_h):
    """Burn opaque black boxes over each region (PDF points, origin bottom-left)."""
    for region in regions:
        rx = float(region.get("x", 0))
        ry = float(region.get("y", 0))
        rw = float(region.get("width", 100))
        rh = float(region.get("height", 20))
        draw.rectangle(
            [int(rx * scale_x),        int((page_h - ry - rh) * scale_y),
             int((rx + rw) * scale_x), int((page_h - ry) * scale_y)],
            fill=(0, 0, 0)
        )


def _draw_shapes(draw, shapes, scale_x, scale_y, page_w, page_h):
    """
    Paint annotation shapes onto a rasterized page. Shape coordinates are
    top-left origin (the frontend's screen space at zoom 1, which equals PDF
    points), unlike redaction regions.
    """
    from PIL.ImageColor import getrgb

    for shape in shapes:
        tool  = shape.get("tool", "rect")
        try:
            rgb = getrgb(shape.get("color", "#FF0000"))
        except Exception:
            rgb = (255, 0, 0)
        width = max(1, int(float(shape.get("strokeWidth", 2)) * scale_x * 0.5))

        if tool in ("line", "arrow", "dimension"):
            draw.line([float(shape.get("x1", 0)) * scale_x, float(shape.get("y1", 0)) * scale_y,
                       float(shape.get("x2", 0)) * scale_x, float(shape.get("y2", 0)) * scale_y],
                      fill=rgb, width=width)

        elif tool in ("rect", "highlight", "redact", "ellipse",
                      "underline", "strikeout", "squiggly"):
            x = float(shape.get("x", 0)) * scale_x
            y = float(shape.get("y", 0)) * scale_y
            w = float(shape.get("width", 100)) * scale_x
            h = float(shape.get("height", 50)) * scale_y
            if tool == "redact":
                draw.rectangle([x, y, x + w, y + h], fill=(0, 0, 0))
            elif tool == "ellipse":
                draw.ellipse([x, y, x + w, y + h], outline=rgb, width=width)
            elif tool == "underline":
                draw.line([x, y + h, x + w, y + h], fill=rgb, width=width)
            elif tool == "strikeout":
                draw.line([x, y + h / 2, x + w, y + h / 2], fill=rgb, width=width)
            elif tool == "squiggly":
                draw.line([x, y + h, x + w, y + h], fill=rgb, width=width)
            else:
                opacity = int(float(shape.get("opacity", 0.15)) * 255)
                draw.rectangle([x, y, x + w, y + h],
                               outline=rgb, width=width, fill=(*rgb, opacity))

        elif tool == "circle":
            cx = float(shape.get("cx", 0)) * scale_x
            cy = float(shape.get("cy", 0)) * scale_y
            r  = float(shape.get("r", 50)) * scale_x
            draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=rgb, width=width)

        elif tool in ("freehand", "cloud", "polygon", "polyline"):
            pts = shape.get("points", [])
            if len(pts) >= 2:
                scaled = [(p["x"] * scale_x, p["y"] * scale_y) for p in pts]
                if tool == "polygon":
                    scaled.append(scaled[0])   # close the ring
                draw.line(scaled, fill=rgb, width=width)

        elif tool in ("text", "stamp", "callout", "note"):
            text = shape.get("text", "")
            if text:
                draw.text((float(shape.get("x", 0)) * scale_x,
                           float(shape.get("y", 0)) * scale_y), text, fill=rgb)


# ══════════════════════════════════════════════════════════════════

def _page_has_text(page, min_chars: int = 10) -> bool:
    """
    True if a pypdfium2 page already carries an extractable text layer.
    Used to skip pages that don't need OCR — re-OCRing a digital page
    would rasterize it and *lose* quality and real text for no gain.
    """
    try:
        textpage = page.get_textpage()
        text     = textpage.get_text_range() or ""
        textpage.close()
        return len(text.strip()) >= min_chars
    except Exception:
        return False


