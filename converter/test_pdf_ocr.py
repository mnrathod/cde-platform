"""
Giving a scanned PDF text that can be found.

<p>A page that already has text is left alone: re-OCRing a born-digital
page replaces good text with a guess.
"""
import pypdf
import pytest

from conftest import (
    acroform_field_names, needs_tesseract, page_annotation_count,
    page_sizes, page_text_lengths
)


class TestOcr:
    def test_reports_missing_tesseract_rather_than_crashing(
        self, converter_app, scanned_pdf, tmp_path, monkeypatch
    ):
        monkeypatch.setattr(converter_app, "find_tesseract", lambda: None)
        result = converter_app.ocr_pdf_to_searchable(scanned_pdf, str(tmp_path / "o.pdf"))
        assert result["success"] is False
        assert result["error"] == "TESSERACT_NOT_FOUND"
        assert "install" in result["hint"].lower()

    def test_missing_file_is_an_error_not_a_crash(self, converter_app):
        assert converter_app.ocr_pdf_to_searchable("/nope/x.pdf")["success"] is False

    @needs_tesseract
    def test_adds_a_text_layer_to_a_scanned_page(self, converter_app, scanned_pdf, tmp_path):
        assert page_text_lengths(scanned_pdf) == [0]
        out = str(tmp_path / "ocr.pdf")
        result = converter_app.ocr_pdf_to_searchable(scanned_pdf, out, dpi=200)
        assert result["success"] is True
        assert page_text_lengths(out)[0] > 0

    @needs_tesseract
    def test_preserves_page_dimensions(self, converter_app, mixed_size_pdf, tmp_path):
        # Regression: Tesseract assumes 70dpi for a bitmap with no
        # resolution metadata, inflating pages to (dpi/70)x their size.
        out = str(tmp_path / "ocr.pdf")
        converter_app.ocr_pdf_to_searchable(mixed_size_pdf, out, dpi=200)
        for before, after in zip(page_sizes(mixed_size_pdf), page_sizes(out)):
            assert after[0] == pytest.approx(before[0], abs=1.0)
            assert after[1] == pytest.approx(before[1], abs=1.0)

    @needs_tesseract
    def test_pages_that_already_have_text_are_passed_through(
        self, converter_app, text_pdf, tmp_path
    ):
        out = str(tmp_path / "ocr.pdf")
        result = converter_app.ocr_pdf_to_searchable(text_pdf, out, skip_text_pages=True)
        assert result["ocrPages"] == 0
        assert result["skippedPages"] == 3
        assert page_text_lengths(out) == page_text_lengths(text_pdf)

    @needs_tesseract
    @pytest.mark.parametrize("requested,expected", [(20, 150), (9999, 600), (300, 300)])
    def test_dpi_is_clamped(self, converter_app, scanned_pdf, tmp_path, requested, expected):
        result = converter_app.ocr_pdf_to_searchable(
            scanned_pdf, str(tmp_path / "o.pdf"), dpi=requested)
        assert result["dpi"] == expected


# ── Chaining operations ──────────────────────────────────────────────
