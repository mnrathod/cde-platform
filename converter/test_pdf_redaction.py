"""
Covering text so it is gone, and burning markup in so it is permanent.

<p>Weighted towards the two failures that actually happened: silent partial
success — pages skipped and still reported as done — and rasterisation side
effects, where redacting one page destroyed the text layer on every other
one. Neither is visible to a caller checking `success`, so both are
asserted on the written file.
"""
import pypdf
import pytest

from conftest import (
    acroform_field_names, needs_tesseract, page_annotation_count,
    page_sizes, page_text_lengths
)


class TestRedactPdf:
    REGION = [{"page": 2, "x": 100, "y": 200, "width": 220, "height": 90}]

    def test_preserves_page_dimensions(self, converter_app, mixed_size_pdf, tmp_path):
        # Regression: rendering at 144dpi while declaring 150 shrank every
        # page to 96% of its size.
        out = str(tmp_path / "red.pdf")
        converter_app.redact_pdf(mixed_size_pdf, self.REGION, out, burn=True)
        for before, after in zip(page_sizes(mixed_size_pdf), page_sizes(out)):
            assert after[0] == pytest.approx(before[0], abs=1.0)
            assert after[1] == pytest.approx(before[1], abs=1.0)

    def test_only_the_redacted_page_is_rasterised(self, converter_app, text_pdf, tmp_path):
        # Regression: every page was rasterised, so redacting one region
        # destroyed the text layer of the whole document.
        out = str(tmp_path / "red.pdf")
        result = converter_app.redact_pdf(
            text_pdf, self.REGION, out, burn=True, restore_text_layer=False)
        assert result["redactedPages"] == 1

        before, after = page_text_lengths(text_pdf), page_text_lengths(out)
        # Rasterising costs the page its text; restoration is tested
        # separately, and is disabled here to isolate which pages were touched.
        assert after[1] == 0, "redacted page should be rasterised"
        assert after[0] == before[0], "untouched page must keep its text"
        assert after[2] == before[2], "untouched page must keep its text"

    def test_regions_outside_the_document_are_rejected(self, converter_app, text_pdf, tmp_path):
        result = converter_app.redact_pdf(
            text_pdf, [{"page": 99, "x": 0, "y": 0, "width": 10, "height": 10}],
            str(tmp_path / "o.pdf"), burn=True)
        assert result["success"] is False

    def test_no_regions_is_an_error(self, converter_app, text_pdf, tmp_path):
        assert converter_app.redact_pdf(text_pdf, [], str(tmp_path / "o.pdf"))["success"] is False

    def test_missing_file_is_an_error_not_a_crash(self, converter_app):
        assert converter_app.redact_pdf("/nope/x.pdf", self.REGION)["success"] is False


# ── Flatten ──────────────────────────────────────────────────────────
class TestFlattenAnnotations:
    def test_only_pages_with_shapes_are_rasterised(self, converter_app, text_pdf, tmp_path):
        out = str(tmp_path / "flat.pdf")
        shapes = [{"tool": "rect", "pageNumber": 1, "color": "#FF0000",
                   "strokeWidth": 2, "x": 40, "y": 40, "width": 120, "height": 80}]
        result = converter_app.flatten_annotations_to_pdf(text_pdf, shapes, out)
        assert result["flattenedPages"] == 1

        after = page_text_lengths(out)
        assert after[0] == 0, "annotated page is rasterised"
        assert after[1] > 0, "page without shapes keeps its text"

    def test_preserves_page_dimensions(self, converter_app, mixed_size_pdf, tmp_path):
        out = str(tmp_path / "flat.pdf")
        shapes = [{"tool": "circle", "pageNumber": 2, "color": "#00FF00",
                   "strokeWidth": 2, "cx": 100, "cy": 100, "r": 40}]
        converter_app.flatten_annotations_to_pdf(mixed_size_pdf, shapes, out)
        for before, after in zip(page_sizes(mixed_size_pdf), page_sizes(out)):
            assert after[0] == pytest.approx(before[0], abs=1.0)
            assert after[1] == pytest.approx(before[1], abs=1.0)

    @pytest.mark.parametrize("tool", [
        "rect", "circle", "ellipse", "line", "arrow", "highlight", "redact",
        "underline", "strikeout", "squiggly", "freehand", "cloud",
        "polygon", "polyline", "text", "stamp", "note",
    ])
    def test_every_annotation_tool_renders_without_error(
        self, converter_app, text_pdf, tmp_path, tool
    ):
        shape = {"tool": tool, "pageNumber": 1, "color": "#FF0000", "strokeWidth": 2,
                 "x": 40, "y": 40, "width": 120, "height": 80,
                 "x1": 10, "y1": 10, "x2": 90, "y2": 90,
                 "cx": 60, "cy": 60, "r": 25, "text": "note",
                 "points": [{"x": 10, "y": 10}, {"x": 50, "y": 40}, {"x": 90, "y": 10}]}
        result = converter_app.flatten_annotations_to_pdf(
            text_pdf, [shape], str(tmp_path / f"{tool}.pdf"))
        assert result["success"] is True, result.get("error")


# ── OCR ──────────────────────────────────────────────────────────────


class TestRedactionRestoresSearchability:
    """
    Rasterizing to destroy content destroys the whole page's text layer with
    it, so redacting one email address made an entire report unsearchable —
    a document you searched to find something became unusable by removing it.
    """

    def _pii_rects(self, converter_app, path, presets):
        return converter_app.find_text_matches(path, presets=presets)["matches"]

    @needs_tesseract
    def test_surviving_text_stays_searchable(self, converter_app, pii_pdf, tmp_path):
        out = str(tmp_path / "red.pdf")
        matches = self._pii_rects(converter_app, pii_pdf, ["email", "creditCard", "iban"])
        assert converter_app.redact_pdf(pii_pdf, matches, out, burn=True)["success"]

        text = _extracted_text(out)
        assert "Concrete cover" in text
        assert "Telephone" in text

    @needs_tesseract
    def test_redacted_content_is_not_recovered_by_the_restored_layer(
        self, converter_app, pii_pdf, tmp_path
    ):
        # The restored layer is OCR of the redacted image, and the redacted
        # areas are solid black — so this must not hand the content back.
        out = str(tmp_path / "red.pdf")
        matches = self._pii_rects(converter_app, pii_pdf, ["email", "creditCard", "iban"])
        converter_app.redact_pdf(pii_pdf, matches, out, burn=True)

        text = _extracted_text(out)
        assert "a.turing@example.co.uk" not in text
        assert "4111" not in text
        assert "GB29NWBK60161331926819" not in text

    def test_restoration_can_be_turned_off(self, converter_app, pii_pdf, tmp_path):
        out = str(tmp_path / "red.pdf")
        matches = self._pii_rects(converter_app, pii_pdf, ["email"])
        converter_app.redact_pdf(pii_pdf, matches, out, burn=True, restore_text_layer=False)

        assert page_text_lengths(out)[0] == 0

    @needs_tesseract
    def test_a_page_that_never_had_text_is_left_alone(
        self, converter_app, scanned_pdf, tmp_path
    ):
        # Restoring only applies to pages that lost a text layer; OCR'ing a
        # scan as a side effect of redaction would be a surprise.
        out = str(tmp_path / "red.pdf")
        converter_app.redact_pdf(
            scanned_pdf, [{"page": 1, "x": 10, "y": 10, "width": 50, "height": 20}],
            out, burn=True)

        assert page_text_lengths(out)[0] == 0


class TestRedactingSeveralPagesAtOnce:
    """
    The failure this file's header names — pages skipped, still reported as
    done — was not actually covered. Every case above redacts a single page
    and asserts ``redactedPages == 1``, so a rewrite that processed only the
    first of several pages passed all of them. Confirmed by making the page
    loop stop after one: the suite stayed green.
    """

    REGIONS = [
        {"page": 1, "x": 60, "y": 80, "width": 240, "height": 60},
        {"page": 2, "x": 60, "y": 80, "width": 240, "height": 60},
        {"page": 3, "x": 60, "y": 80, "width": 240, "height": 60},
    ]

    def test_every_named_page_is_redacted(self, converter_app, text_pdf, tmp_path):
        out = str(tmp_path / "redacted.pdf")
        result = converter_app.redact_pdf(text_pdf, self.REGIONS, out)

        assert result["success"] is True
        assert result["redactedPages"] == 3, \
            "a page named in the request was reported as done without being touched"

    def test_the_count_matches_what_the_file_shows(self, converter_app, text_pdf, tmp_path):
        # The count alone can lie. Each of the three pages carried a distinct
        # marker word under the redacted band; none should survive.
        out = str(tmp_path / "redacted.pdf")
        converter_app.redact_pdf(text_pdf, self.REGIONS, out)

        remaining = page_text_lengths(out)
        assert len(remaining) == 3, "redaction changed the page count"
        for page_number, characters in enumerate(remaining, start=1):
            assert characters < 40, \
                f"page {page_number} still carries {characters} characters of text"
