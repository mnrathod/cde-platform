"""
Shared pytest fixtures.

PDFs are generated rather than committed as binaries so that what each test
depends on — page sizes, which pages carry text, which fields exist — is
readable in the source instead of hidden inside a blob.
"""
import io
import sys
from pathlib import Path

import pypdf
import pytest
from PIL import Image, ImageDraw, ImageFont
from reportlab.lib.colors import black, white
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas as rl_canvas

sys.path.insert(0, str(Path(__file__).parent))

import app as converter  # noqa: E402


@pytest.fixture(scope="session")
def converter_app():
    return converter


def _font(size, bold=False):
    name = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    try:
        return ImageFont.truetype(f"/usr/share/fonts/truetype/dejavu/{name}", size)
    except Exception:
        return ImageFont.load_default()


def _image_pdf(path, specs):
    """
    Build an image-only PDF — no text layer at all — one page per spec of
    (width_px, height_px, [lines]).
    """
    writer = pypdf.PdfWriter()
    for width, height, lines in specs:
        image = Image.new("RGB", (width, height), "white")
        draw = ImageDraw.Draw(image)
        draw.rectangle([8, 8, width - 8, height - 8], outline="black", width=3)
        y = 90
        for index, line in enumerate(lines):
            draw.text((70, y), line, fill="black", font=_font(46 if index == 0 else 34, index == 0))
            y += 70
        buffer = io.BytesIO()
        image.save(buffer, "PDF", resolution=150)
        buffer.seek(0)
        writer.add_page(pypdf.PdfReader(buffer).pages[0])
    with open(path, "wb") as handle:
        writer.write(handle)
    return str(path)


@pytest.fixture
def scanned_pdf(tmp_path):
    """Single-page image-only PDF: no extractable text before OCR."""
    return _image_pdf(tmp_path / "scanned.pdf", [
        (1240, 1754, ["INSPECTION REPORT", "Marker ALPHA", "Concrete cover 42mm"]),
    ])


@pytest.fixture
def mixed_size_pdf(tmp_path):
    """
    Three image-only pages with deliberately different sizes — portrait,
    landscape and square — so page-size regressions surface.
    """
    return _image_pdf(tmp_path / "mixed.pdf", [
        (1240, 1754, ["PAGE ONE", "Marker ALPHA"]),
        (1754, 1240, ["PAGE TWO", "Marker BRAVO"]),
        (1400, 1400, ["PAGE THREE", "Marker CHARLIE"]),
    ])


@pytest.fixture
def text_pdf(tmp_path):
    """Three-page PDF that already carries a real text layer."""
    path = tmp_path / "text.pdf"
    pdf = rl_canvas.Canvas(str(path), pagesize=A4)
    for page in ("ALPHA", "BRAVO", "CHARLIE"):
        pdf.setFont("Helvetica", 18)
        pdf.drawString(70, A4[1] - 90, f"Marker {page}")
        pdf.drawString(70, A4[1] - 130, "Structural inspection report")
        pdf.showPage()
    pdf.save()
    return str(path)


@pytest.fixture
def form_pdf(tmp_path):
    """
    Two-page AcroForm covering the field kinds the UI renders, including
    fields on page 2 and a read-only field.
    """
    path = tmp_path / "form.pdf"
    pdf = rl_canvas.Canvas(str(path), pagesize=A4)
    width, height = A4
    form = pdf.acroForm
    common = dict(borderColor=black, fillColor=white, textColor=black, forceBorder=True)

    pdf.drawString(60, height - 70, "SITE INSPECTION")
    form.textfield(name="inspector_name", x=200, y=height - 135, width=250, height=20, **common)
    form.textfield(name="project_ref", x=200, y=height - 175, width=250, height=20,
                   maxlen=12, **common)
    form.textfield(name="observations", x=200, y=height - 265, width=300, height=70,
                   fieldFlags="multiline", **common)
    form.checkbox(name="passed", x=200, y=height - 305, size=16, **common)
    form.choice(name="severity", x=200, y=height - 350, width=150, height=22,
                options=[("Low", "Low"), ("Medium", "Medium"), ("High", "High")],
                value="Low", **common)
    form.textfield(name="doc_number", x=200, y=height - 405, width=200, height=20,
                   value="CBE-ST-001", fieldFlags="readOnly", **common)
    pdf.showPage()

    pdf.drawString(60, height - 70, "SIGN OFF")
    form.textfield(name="approver_name", x=200, y=height - 135, width=250, height=20, **common)
    form.checkbox(name="remedial_required", x=220, y=height - 205, size=16, **common)
    pdf.save()
    return str(path)


def page_text_lengths(path):
    """Extractable characters per page — the signal for 'is this searchable'."""
    import pypdfium2 as pdfium
    document = pdfium.PdfDocument(path)
    try:
        return [
            len((document[i].get_textpage().get_text_range() or "").strip())
            for i in range(len(document))
        ]
    finally:
        document.close()


def page_sizes(path):
    import pypdfium2 as pdfium
    document = pdfium.PdfDocument(path)
    try:
        return [(round(document[i].get_width(), 1), round(document[i].get_height(), 1))
                for i in range(len(document))]
    finally:
        document.close()


@pytest.fixture
def scanned_form_pdf(tmp_path):
    """
    A document that needs all three rewriting operations at once: page 1 is an
    image with no text layer (OCR), page 2 carries text to redact plus an
    AcroForm field to fill and a second field placed where a redaction can
    land on top of it.
    """
    path = tmp_path / "scanned_form.pdf"
    width, height = A4

    image = Image.new("RGB", (1240, 1754), "white")
    draw = ImageDraw.Draw(image)
    draw.text((70, 200), "SCANNED SHEET", fill="black", font=_font(64, True))
    draw.text((70, 300), "Marker ALPHA", fill="black", font=_font(52, True))
    buffer = io.BytesIO()
    image.save(buffer, "PDF", resolution=150)
    buffer.seek(0)

    pdf = rl_canvas.Canvas(str(path), pagesize=A4)
    pdf.drawInlineImage(image, 0, 0, width=width, height=height)
    pdf.showPage()

    form = pdf.acroForm
    common = dict(borderColor=black, fillColor=white, textColor=black, forceBorder=True)
    pdf.setFont("Helvetica", 14)
    pdf.drawString(70, height - 90,  "CONTRACT SUMMARY")
    pdf.drawString(70, height - 140, "CONFIDENTIAL RATE: 12345 GBP")
    form.textfield(name="contractor", x=200, y=height - 200, width=250, height=20, **common)
    form.textfield(name="under_redaction", x=200, y=height - 145, width=200, height=20, **common)
    pdf.showPage()
    pdf.save()
    return str(path)


def acroform_field_names(path):
    """Field names still reachable from the document catalog."""
    return sorted((pypdf.PdfReader(path).get_fields() or {}).keys())


def page_annotation_count(path, page_index):
    annots = pypdf.PdfReader(path).pages[page_index].get("/Annots")
    return len(annots) if annots else 0
