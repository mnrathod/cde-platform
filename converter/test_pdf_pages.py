"""
Reordering, rotating, deleting and inserting pages.

<p>Structural edits only, which is what makes the assertions about page
count and page size the load-bearing ones.
"""
import pypdf
import pytest

from conftest import (
    acroform_field_names, page_annotation_count, page_sizes, page_text_lengths
)


class TestRearrangePdfPages:
    """
    One primitive backs reorder, delete, duplicate, rotate and insert, so the
    cases below are the same call with different plans — which is the point:
    six operations cannot drift apart if they share a rebuild loop.
    """

    def _plan(self, *entries):
        return [e if isinstance(e, dict) else {"page": e} for e in entries]

    def test_reorder_moves_pages(self, converter_app, text_pdf, tmp_path):
        out = str(tmp_path / "r.pdf")
        assert converter_app.rearrange_pdf_pages(
            text_pdf, self._plan(3, 1, 2), out)["success"]

        text = [p.extract_text() for p in pypdf.PdfReader(out).pages]
        assert "CHARLIE" in text[0] and "ALPHA" in text[1] and "BRAVO" in text[2]

    def test_delete_omits_pages(self, converter_app, text_pdf, tmp_path):
        out = str(tmp_path / "d.pdf")
        result = converter_app.rearrange_pdf_pages(text_pdf, self._plan(1, 3), out)

        assert result["pageCount"] == 2
        text = [p.extract_text() for p in pypdf.PdfReader(out).pages]
        assert "BRAVO" not in " ".join(text)

    def test_duplicate_repeats_a_page(self, converter_app, text_pdf, tmp_path):
        # Regression: adding the same page object twice made the page tree
        # cyclic, and every later read of the file raised.
        out = str(tmp_path / "dup.pdf")
        assert converter_app.rearrange_pdf_pages(
            text_pdf, self._plan(1, 1, 1), out)["success"]

        pages = pypdf.PdfReader(out).pages
        assert len(pages) == 3
        assert all("ALPHA" in page.extract_text() for page in pages)

    def test_rotation_is_relative_to_the_current_angle(self, converter_app, text_pdf, tmp_path):
        once = str(tmp_path / "once.pdf")
        converter_app.rearrange_pdf_pages(
            text_pdf, [{"page": 1, "rotate": 90}], once)
        twice = str(tmp_path / "twice.pdf")
        converter_app.rearrange_pdf_pages(once, [{"page": 1, "rotate": 90}], twice)

        assert int(pypdf.PdfReader(once).pages[0].get("/Rotate", 0)) == 90
        assert int(pypdf.PdfReader(twice).pages[0].get("/Rotate", 0)) == 180

    def test_insert_pulls_pages_from_another_document(
        self, converter_app, text_pdf, scanned_pdf, tmp_path
    ):
        out = str(tmp_path / "ins.pdf")
        result = converter_app.rearrange_pdf_pages(
            text_pdf,
            [{"page": 1}, {"source": "other", "page": 1}, {"page": 2}],
            out,
            {"other": scanned_pdf})

        assert result["pageCount"] == 3
        assert page_text_lengths(out)[1] == 0, "the inserted scan has no text layer"

    def test_the_form_survives_rearrangement(self, converter_app, form_pdf, tmp_path):
        # Building into an empty writer drops /AcroForm, which would leave a
        # reordered document with no fillable fields at all.
        out = str(tmp_path / "r.pdf")
        converter_app.rearrange_pdf_pages(form_pdf, self._plan(2, 1), out)

        assert acroform_field_names(out) == acroform_field_names(form_pdf)

    def test_page_sizes_are_untouched(self, converter_app, mixed_size_pdf, tmp_path):
        out = str(tmp_path / "r.pdf")
        converter_app.rearrange_pdf_pages(mixed_size_pdf, self._plan(3, 2, 1), out)

        assert page_sizes(out) == list(reversed(page_sizes(mixed_size_pdf)))

    def test_an_empty_plan_is_rejected(self, converter_app, text_pdf, tmp_path):
        result = converter_app.rearrange_pdf_pages(text_pdf, [], str(tmp_path / "o.pdf"))
        assert result["success"] is False
        assert "empty" in result["error"].lower()

    def test_a_page_outside_the_document_is_rejected(self, converter_app, text_pdf, tmp_path):
        result = converter_app.rearrange_pdf_pages(
            text_pdf, self._plan(99), str(tmp_path / "o.pdf"))
        assert result["success"] is False
        assert "99" in result["error"]

    def test_an_unknown_source_is_rejected(self, converter_app, text_pdf, tmp_path):
        result = converter_app.rearrange_pdf_pages(
            text_pdf, [{"source": "ghost", "page": 1}], str(tmp_path / "o.pdf"))
        assert result["success"] is False

    def test_nothing_is_written_when_the_plan_is_invalid(
        self, converter_app, text_pdf, tmp_path
    ):
        # Validation runs over the whole plan first: a half-applied
        # rearrangement is worse than a rejected one.
        out = tmp_path / "o.pdf"
        converter_app.rearrange_pdf_pages(
            text_pdf, self._plan(1, 99), str(out))
        assert not out.exists()

    def test_missing_file_is_an_error_not_a_crash(self, converter_app, tmp_path):
        assert converter_app.rearrange_pdf_pages(
            "/nope/x.pdf", [{"page": 1}], str(tmp_path / "o.pdf"))["success"] is False


# ── Finding text to redact ───────────────────────────────────────────
