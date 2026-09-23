"""
Operations run one after another on the same document.

<p>Each operation was written and tested on a pristine file. In use they
compose — a redaction over a flattened markup over an OCR layer — and the
interesting failures live in that composition rather than in any one step,
which is why this is a suite of its own rather than a case inside each.
"""
import pypdf
import pytest

from conftest import (
    acroform_field_names, needs_tesseract, page_annotation_count,
    page_sizes, page_text_lengths
)


class TestOperationsCompose:
    """
    Each rewriting operation reads the document and writes a new one, so they
    are only useful together if the output of one is still valid input to the
    next.

    They were not. Redaction, flattening and OCR all rebuilt the file by
    adding pages to an empty writer, which starts with an empty catalog — so
    /AcroForm was dropped and any document that had been through one of them
    reported itself as having no fillable fields. These pin the fix: the
    document is cloned and only the affected pages' content is replaced.
    """

    RATE_REGION = [{"page": 2, "x": 60, "y": 690, "width": 260, "height": 30}]

    @needs_tesseract
    def test_ocr_keeps_the_form(self, converter_app, scanned_form_pdf, tmp_path):
        out = str(tmp_path / "ocr.pdf")
        assert converter_app.ocr_pdf_to_searchable(scanned_form_pdf, out, dpi=200)["success"]
        assert acroform_field_names(out) == acroform_field_names(scanned_form_pdf)

    def test_redaction_keeps_fields_away_from_the_region(
        self, converter_app, scanned_form_pdf, tmp_path
    ):
        out = str(tmp_path / "red.pdf")
        assert converter_app.redact_pdf(
            scanned_form_pdf, self.RATE_REGION, out, burn=True)["success"]
        assert "contractor" in acroform_field_names(out)

    def test_flatten_keeps_the_form(self, converter_app, scanned_form_pdf, tmp_path):
        out = str(tmp_path / "flat.pdf")
        shapes = [{"tool": "rect", "pageNumber": 2, "x": 60, "y": 400,
                   "width": 200, "height": 60, "color": "#FF0000"}]
        assert converter_app.flatten_annotations_to_pdf(scanned_form_pdf, shapes, out)["success"]
        assert acroform_field_names(out) == acroform_field_names(scanned_form_pdf)

    def test_a_widget_under_a_redaction_is_removed_entirely(
        self, converter_app, scanned_form_pdf, tmp_path
    ):
        # Rasterising destroys the pixels but not the objects drawn over them:
        # a field left in the catalog keeps its value in the file regardless
        # of what the page now shows.
        out = str(tmp_path / "red.pdf")
        converter_app.redact_pdf(scanned_form_pdf, self.RATE_REGION, out, burn=True)

        assert "under_redaction" not in acroform_field_names(out)
        assert page_annotation_count(out, 1) < page_annotation_count(scanned_form_pdf, 1)

    @needs_tesseract
    def test_ocr_then_redact_keeps_the_recognised_text(
        self, converter_app, scanned_form_pdf, tmp_path
    ):
        ocred = str(tmp_path / "1_ocr.pdf")
        converter_app.ocr_pdf_to_searchable(scanned_form_pdf, ocred, dpi=200)
        assert page_text_lengths(ocred)[0] > 0, "OCR should make page 1 searchable"

        redacted = str(tmp_path / "2_red.pdf")
        converter_app.redact_pdf(ocred, self.RATE_REGION, redacted, burn=True)

        # Page 1 was not redacted, so the text OCR added must survive.
        assert page_text_lengths(redacted)[0] > 0

    @needs_tesseract
    def test_the_full_chain_lands_in_one_document(
        self, converter_app, scanned_form_pdf, tmp_path
    ):
        ocred = str(tmp_path / "1_ocr.pdf")
        converter_app.ocr_pdf_to_searchable(scanned_form_pdf, ocred, dpi=200)

        redacted = str(tmp_path / "2_red.pdf")
        converter_app.redact_pdf(ocred, self.RATE_REGION, redacted, burn=True)

        filled = str(tmp_path / "3_filled.pdf")
        result = converter_app.fill_pdf_form(
            redacted, {"contractor": "Acme Construction Ltd"}, filled)
        assert result["success"], result.get("error")

        reader = pypdf.PdfReader(filled)
        assert page_text_lengths(filled)[0] > 0, "OCR text layer lost"
        assert "12345" not in reader.pages[1].extract_text(), "redaction lost"
        assert str(reader.get_fields()["contractor"].get("/V")) == "Acme Construction Ltd"


# ── Page manipulation ────────────────────────────────────────────────
