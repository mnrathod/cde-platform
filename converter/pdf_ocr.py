"""
Turning a scanned PDF into one whose words can be found.

<p>A scan is a picture of a document. Everything downstream — search,
redaction, the accessible text a screen reader needs, §1A.4's requirement
that an export be tagged — depends on there being text, so the pages that
have none get an OCR layer laid over the image, leaving the image itself
untouched as the visual record.

<p>Pages that already carry text are left alone. Re-OCRing a born-digital
page replaces good text with a guess.
"""
import io
import os

from pdf_page_rewrite import _page_has_text, _stamp_page_content
from toolchain import find_tesseract


def ocr_pdf_to_searchable(path: str, output: str = "", lang: str = "eng",
                          dpi: int = 300, skip_text_pages: bool = True) -> dict:
    """
    Produce a searchable PDF from a scanned/image PDF by adding an invisible
    text layer, leaving the visible page appearance unchanged.

    Uses Tesseract's own PDF renderer (image_to_pdf_or_hocr) rather than
    positioning invisible text by hand — Tesseract already knows each glyph's
    exact box from recognition, so its output aligns text to the image
    correctly, which hand-placed word boxes reliably get subtly wrong.

    Pages that already have a text layer are copied through untouched
    (skip_text_pages=True), so this is safe to run on a mixed document.
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}

    tess = find_tesseract()
    if not tess:
        return {"success": False, "error": "TESSERACT_NOT_FOUND",
                "hint": "Install Tesseract OCR: 'sudo apt install tesseract-ocr' "
                        "(Linux) or https://github.com/UB-Mannheim/tesseract/wiki (Windows)"}

    # Guard rails: dpi below ~150 wrecks recognition accuracy, above 600 costs
    # a lot of time/memory for no measurable gain.
    dpi = max(150, min(int(dpi or 300), 600))

    try:
        import pypdfium2 as pdfium
        import pytesseract
        import pypdf
        import io

        pytesseract.pytesseract.tesseract_cmd = tess

        if not output:
            output = os.path.splitext(path)[0] + "_searchable.pdf"

        src        = pdfium.PdfDocument(path)
        # Clone rather than assembling pages into an empty writer: an empty
        # writer starts with an empty catalog, so /AcroForm was dropped and an
        # OCR'd document reported itself as having no fillable form fields —
        # which then made form-filling impossible on anything already OCR'd.
        writer     = pypdf.PdfWriter(clone_from=path)
        total      = len(src)
        ocr_pages  = 0
        kept_pages = 0

        print(f"[OCR] {total} page(s) @ {dpi}dpi lang={lang}", flush=True)

        for i in range(total):
            page = src[i]

            if skip_text_pages and _page_has_text(page):
                kept_pages += 1
                continue

            # pypdfium2's render scale is relative to 72dpi
            bitmap = page.render(scale=dpi / 72.0)
            img    = bitmap.to_pil().convert("RGB")

            # --dpi must be passed explicitly: Tesseract otherwise assumes
            # 70dpi for a bitmap carrying no resolution metadata and sizes
            # the output page from that, which silently inflated every
            # OCR'd page to (dpi/70)x its true dimensions.
            page_pdf = pytesseract.image_to_pdf_or_hocr(
                img, lang=lang, extension="pdf", config=f"--dpi {dpi}"
            )
            recognised = pypdf.PdfReader(io.BytesIO(page_pdf)).pages
            if recognised:
                _stamp_page_content(writer.pages[i], recognised[0])
            ocr_pages += 1

        src.close()

        with open(output, "wb") as fh:
            writer.write(fh)

        print(f"[OCR] done — {ocr_pages} OCR'd, {kept_pages} already searchable", flush=True)

        return {
            "success": True,
            "outputPath": output,
            "totalPages": total,
            "ocrPages": ocr_pages,
            "skippedPages": kept_pages,
            "language": lang,
            "dpi": dpi,
        }

    except ImportError as e:
        return {"success": False,
                "error": f"Required library missing: {e}",
                "hint": "pip install pypdfium2 pytesseract pypdf Pillow"}
    except pytesseract.TesseractError as e:
        # Most common cause: requested language pack not installed
        return {"success": False, "error": f"Tesseract failed: {e}",
                "hint": f"Is the '{lang}' language pack installed? "
                        f"e.g. 'sudo apt install tesseract-ocr-{lang}'"}
    except Exception as e:
        return {"success": False, "error": str(e)}


