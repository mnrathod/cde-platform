"""
Tests for the converter's PDF pipeline.

Weighted towards the failure modes that actually occurred: silent
partial success (pages skipped but reported as done), and rasterisation
side effects (page size drift, text layers destroyed on untouched pages).
Those are invisible to a caller checking only `success`, so they are
asserted on the written file rather than the return value.
"""
import pypdf
import pytest

from conftest import (
    acroform_field_names, page_annotation_count, page_sizes, page_text_lengths
)


# ── Field-flag interpretation ────────────────────────────────────────
class TestFormFieldKind:
    @pytest.mark.parametrize("field_type,flags,expected", [
        ("/Tx", 0,                     "text"),
        ("/Tx", 1 << 12,               "textarea"),
        ("/Tx", 1 << 13,               "password"),
        ("/Btn", 0,                    "checkbox"),
        ("/Btn", 1 << 15,              "radio"),
        ("/Btn", 1 << 16,              "button"),
        ("/Ch", 0,                     "listbox"),
        ("/Ch", 1 << 17,               "dropdown"),
        ("/Sig", 0,                    "signature"),
    ])
    def test_maps_type_and_flags_to_kind(self, converter_app, field_type, flags, expected):
        assert converter_app._form_field_kind(field_type, flags) == expected

    def test_pushbutton_wins_over_radio(self, converter_app):
        # Both bits set is legal; Pushbutton is the more specific control.
        both = (1 << 15) | (1 << 16)
        assert converter_app._form_field_kind("/Btn", both) == "button"

    def test_read_only_and_required_are_independent_of_kind(self, converter_app):
        flags = converter_app.FF_READ_ONLY | converter_app.FF_REQUIRED | (1 << 12)
        assert converter_app._form_field_kind("/Tx", flags) == "textarea"


class TestTruthyCoercion:
    @pytest.mark.parametrize("value", [True, "true", "True", "yes", "on", "1", "/Yes", "checked"])
    def test_truthy(self, converter_app, value):
        assert converter_app._is_truthy(value) is True

    @pytest.mark.parametrize("value", [False, "false", "no", "off", "0", "/Off", "", "anything"])
    def test_falsy(self, converter_app, value):
        assert converter_app._is_truthy(value) is False


class TestChoiceOptions:
    def test_pairs_become_value_and_label(self, converter_app):
        field = {"/Opt": [["L", "Low"], ["H", "High"]]}
        assert converter_app._choice_options(field) == [
            {"value": "L", "label": "Low"},
            {"value": "H", "label": "High"},
        ]

    def test_bare_strings_use_the_value_as_label(self, converter_app):
        assert converter_app._choice_options({"/Opt": ["Low"]}) == [
            {"value": "Low", "label": "Low"}
        ]

    def test_missing_opt_yields_no_options(self, converter_app):
        assert converter_app._choice_options({}) == []


class TestCheckboxStates:
    def test_on_state_is_read_from_the_field_not_assumed(self, converter_app):
        # /On is as legitimate as /Yes; hardcoding /Yes silently mis-filled
        # any form that used a different appearance name.
        on, off = converter_app._checkbox_states({"/_States_": ["/Off", "/On"]})
        assert (on, off) == ("/On", "/Off")

    def test_falls_back_to_yes_when_states_absent(self, converter_app):
        assert converter_app._checkbox_states({}) == ("/Yes", "/Off")


class TestGroupByPage:
    def test_groups_on_zero_based_index(self, converter_app):
        grouped = converter_app._group_by_page([{"page": 1}, {"page": 3}, {"page": 1}])
        assert sorted(grouped) == [0, 2]
        assert len(grouped[0]) == 2

    def test_honours_an_alternate_key(self, converter_app):
        grouped = converter_app._group_by_page([{"pageNumber": 2}], key="pageNumber")
        assert list(grouped) == [1]


# ── Form inspection ──────────────────────────────────────────────────
class TestInspectPdfForm:
    def test_describes_every_field(self, converter_app, form_pdf):
        result = converter_app.inspect_pdf_form(form_pdf)
        assert result["success"] is True
        assert result["count"] == 8
        assert result["pageCount"] == 2

    def test_resolves_kind_per_field(self, converter_app, form_pdf):
        by_name = {f["name"]: f for f in converter_app.inspect_pdf_form(form_pdf)["fields"]}
        assert by_name["inspector_name"]["kind"] == "text"
        assert by_name["observations"]["kind"] == "textarea"
        assert by_name["passed"]["kind"] == "checkbox"
        assert by_name["severity"]["kind"] == "dropdown"

    def test_reports_the_page_each_field_is_on(self, converter_app, form_pdf):
        by_name = {f["name"]: f for f in converter_app.inspect_pdf_form(form_pdf)["fields"]}
        assert by_name["inspector_name"]["page"] == 1
        assert by_name["approver_name"]["page"] == 2
        assert by_name["remedial_required"]["page"] == 2

    def test_reports_read_only(self, converter_app, form_pdf):
        by_name = {f["name"]: f for f in converter_app.inspect_pdf_form(form_pdf)["fields"]}
        assert by_name["doc_number"]["readOnly"] is True
        assert by_name["inspector_name"]["readOnly"] is False

    def test_returns_choice_options(self, converter_app, form_pdf):
        by_name = {f["name"]: f for f in converter_app.inspect_pdf_form(form_pdf)["fields"]}
        assert [o["value"] for o in by_name["severity"]["options"]] == ["Low", "Medium", "High"]

    def test_reports_max_length(self, converter_app, form_pdf):
        by_name = {f["name"]: f for f in converter_app.inspect_pdf_form(form_pdf)["fields"]}
        assert by_name["project_ref"]["maxLength"] == 12

    def test_fields_are_ordered_by_page_then_name(self, converter_app, form_pdf):
        fields = converter_app.inspect_pdf_form(form_pdf)["fields"]
        keys = [(f["page"], f["name"]) for f in fields]
        assert keys == sorted(keys)

    def test_reports_no_fields_for_a_plain_pdf(self, converter_app, text_pdf):
        result = converter_app.inspect_pdf_form(text_pdf)
        assert result["success"] is True
        assert result["fields"] == []

    def test_missing_file_is_an_error_not_a_crash(self, converter_app):
        assert converter_app.inspect_pdf_form("/nope/missing.pdf")["success"] is False


def _extracted_text(path):
    """All extractable text in a document, for searchability assertions."""
    import pypdfium2 as pdfium
    document = pdfium.PdfDocument(path)
    try:
        return " ".join(
            (document[i].get_textpage().get_text_range() or "")
            for i in range(len(document)))
    finally:
        document.close()


# ── Form filling ─────────────────────────────────────────────────────
def _written_values(path):
    return {name: str(field.get("/V"))
            for name, field in (pypdf.PdfReader(path).get_fields() or {}).items()}


class TestFillPdfForm:
    def test_fills_fields_on_pages_after_the_first(self, converter_app, form_pdf, tmp_path):
        # Regression: only page 0 was written, so page-2 fields stayed blank
        # while still being reported as filled.
        out = str(tmp_path / "filled.pdf")
        converter_app.fill_pdf_form(form_pdf, {
            "inspector_name": "J. Doe",
            "approver_name": "A. Smith",
        }, out)
        values = _written_values(out)
        assert values["inspector_name"] == "J. Doe"
        assert values["approver_name"] == "A. Smith"

    def test_reported_filled_fields_match_what_was_written(self, converter_app, form_pdf, tmp_path):
        out = str(tmp_path / "filled.pdf")
        result = converter_app.fill_pdf_form(form_pdf, {"approver_name": "A. Smith"}, out)
        assert result["filledFields"] == {"approver_name": "A. Smith"}
        assert _written_values(out)["approver_name"] == "A. Smith"

    @pytest.mark.parametrize("supplied", [True, "yes", "true", "/Yes"])
    def test_checkbox_accepts_truthy_forms(self, converter_app, form_pdf, tmp_path, supplied):
        out = str(tmp_path / "cb.pdf")
        converter_app.fill_pdf_form(form_pdf, {"passed": supplied}, out)
        assert _written_values(out)["passed"] == "/Yes"

    def test_checkbox_off_for_falsy(self, converter_app, form_pdf, tmp_path):
        out = str(tmp_path / "cb.pdf")
        converter_app.fill_pdf_form(form_pdf, {"passed": False}, out)
        assert _written_values(out)["passed"] == "/Off"

    def test_read_only_field_is_not_overwritten(self, converter_app, form_pdf, tmp_path):
        out = str(tmp_path / "ro.pdf")
        result = converter_app.fill_pdf_form(form_pdf, {"doc_number": "HACKED"}, out)
        assert "doc_number" in result["skippedFields"]
        assert _written_values(out)["doc_number"] == "CBE-ST-001"

    def test_unknown_field_is_skipped_with_a_reason(self, converter_app, form_pdf, tmp_path):
        result = converter_app.fill_pdf_form(
            form_pdf, {"nope": "x"}, str(tmp_path / "u.pdf"))
        assert result["skippedFields"]["nope"]

    def test_sets_need_appearances_so_viewers_render_values(self, converter_app, form_pdf, tmp_path):
        out = str(tmp_path / "na.pdf")
        converter_app.fill_pdf_form(form_pdf, {"inspector_name": "J. Doe"}, out)
        acro = pypdf.PdfReader(out).trailer["/Root"].get("/AcroForm", {})
        # pypdf returns a BooleanObject, so compare by value not identity.
        assert bool(acro.get("/NeedAppearances")) is True

    def test_flatten_removes_interactivity(self, converter_app, form_pdf, tmp_path):
        # pypdf's flatten paints the value but leaves the field editable;
        # the result must not still open as a fillable form.
        out = str(tmp_path / "flat.pdf")
        converter_app.fill_pdf_form(form_pdf, {"inspector_name": "Flat"}, out, flatten=True)
        assert not (pypdf.PdfReader(out).get_fields() or {})

    def test_without_flatten_the_form_stays_editable(self, converter_app, form_pdf, tmp_path):
        out = str(tmp_path / "editable.pdf")
        converter_app.fill_pdf_form(form_pdf, {"inspector_name": "Keep"}, out, flatten=False)
        assert len(pypdf.PdfReader(out).get_fields() or {}) == 8

    def test_pdf_without_acroform_is_rejected(self, converter_app, text_pdf, tmp_path):
        result = converter_app.fill_pdf_form(text_pdf, {"x": "y"}, str(tmp_path / "o.pdf"))
        assert result["success"] is False
        assert "no fillable form fields" in result["error"]

    def test_missing_file_is_an_error_not_a_crash(self, converter_app):
        assert converter_app.fill_pdf_form("/nope/x.pdf", {})["success"] is False


# ── Redaction ────────────────────────────────────────────────────────
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


needs_tesseract = pytest.mark.skipif(
    __import__("shutil").which("tesseract") is None,
    reason="Tesseract not installed",
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
class TestDescribePdfPages:
    def test_reports_every_page_with_its_size(self, converter_app, mixed_size_pdf):
        result = converter_app.describe_pdf_pages(mixed_size_pdf)
        assert result["success"] and result["pageCount"] == 3
        assert [p["page"] for p in result["pages"]] == [1, 2, 3]
        # The fixture is portrait, landscape, square — page info must not
        # flatten that, since the organiser lays pages out from it.
        assert result["pages"][0]["height"] > result["pages"][0]["width"]
        assert result["pages"][1]["width"] > result["pages"][1]["height"]

    def test_missing_file_is_an_error_not_a_crash(self, converter_app):
        assert converter_app.describe_pdf_pages("/nope/x.pdf")["success"] is False


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
class TestFindTextMatches:
    """
    Redaction cannot be undone from inside the file, so a pattern that
    over-matches destroys content nobody asked to remove. The negative
    assertions here matter as much as the positive ones.
    """

    def _texts(self, converter_app, path, **kwargs):
        result = converter_app.find_text_matches(path, **kwargs)
        assert result["success"], result.get("error")
        return [match["text"] for match in result["matches"]]

    def test_finds_a_literal_term(self, converter_app, text_pdf):
        assert self._texts(converter_app, text_pdf, terms=["BRAVO"]) == ["BRAVO"]

    def test_matching_ignores_case_by_default(self, converter_app, text_pdf):
        assert self._texts(converter_app, text_pdf, terms=["bravo"]) == ["BRAVO"]

    def test_case_sensitive_search_respects_case(self, converter_app, text_pdf):
        assert self._texts(converter_app, text_pdf, terms=["bravo"], match_case=True) == []

    def test_whole_word_does_not_match_a_fragment(self, converter_app, text_pdf):
        assert self._texts(converter_app, text_pdf, terms=["BRAV"], whole_word=True) == []
        assert self._texts(converter_app, text_pdf, terms=["BRAVO"], whole_word=True) == ["BRAVO"]

    def test_supports_regular_expressions(self, converter_app, pii_pdf):
        assert self._texts(converter_app, pii_pdf, regexes=[r"\b\d{5} \d{6}\b"]) == ["07700 900123"]

    def test_reports_one_rectangle_per_match(self, converter_app, pii_pdf):
        # Regression: glyphs on one line sit at different heights, and
        # splitting on baseline distance reported a single phone number three
        # times, each with its own box.
        result = converter_app.find_text_matches(pii_pdf, presets=["email"])
        assert result["matchCount"] == 1

    def test_rectangles_are_in_pdf_points_from_the_bottom_left(self, converter_app, pii_pdf):
        # Same space redact_pdf takes, so a match can be redacted without
        # any coordinate conversion.
        match = converter_app.find_text_matches(pii_pdf, presets=["email"])["matches"][0]
        assert 0 < match["x"] < match["pageHeight"]
        assert 0 < match["y"] < match["pageHeight"]
        assert match["width"] > 0 and match["height"] > 0

    @pytest.mark.parametrize("preset,expected", [
        ("email",      ["a.turing@example.co.uk"]),
        ("creditCard", ["4111 1111 1111 1111"]),
        ("niNumber",   ["AB 12 34 56 C"]),
        ("postcode",   ["SW1A 1AA"]),
        ("iban",       ["GB29NWBK60161331926819"]),
    ])
    def test_presets_match_their_own_category(self, converter_app, pii_pdf, preset, expected):
        assert self._texts(converter_app, pii_pdf, presets=[preset]) == expected

    def test_phone_finds_both_numbers(self, converter_app, pii_pdf):
        assert self._texts(converter_app, pii_pdf, presets=["phone"]) == [
            "+44 20 7946 0958", "07700 900123"]

    def test_phone_does_not_eat_card_iban_or_ni_digits(self, converter_app, pii_pdf):
        # The original pattern matched all three, which would have destroyed
        # a card number while claiming to redact a phone number.
        found = " ".join(self._texts(converter_app, pii_pdf, presets=["phone"]))
        assert "4111" not in found
        assert "NWBK" not in found and "60161331926819" not in found

    def test_no_preset_matches_dimensions(self, converter_app, pii_pdf):
        found = " ".join(self._texts(
            converter_app, pii_pdf,
            presets=["email", "phone", "creditCard", "niNumber", "postcode", "iban"]))
        for dimension in ("42mm", "250mm", "6000", "7200"):
            assert dimension not in found

    def test_reports_pages_with_no_text_layer(self, converter_app, scanned_pdf):
        # A scan yields nothing, which is not the same as "this document is
        # clean" — the caller has to be able to tell those apart.
        result = converter_app.find_text_matches(scanned_pdf, terms=["anything"])
        assert result["matchCount"] == 0
        assert result["pagesWithoutText"] == 1

    def test_an_unknown_preset_is_rejected_by_name(self, converter_app, text_pdf):
        result = converter_app.find_text_matches(text_pdf, presets=["nope"])
        assert result["success"] is False
        assert "nope" in result["error"]

    def test_an_invalid_expression_is_rejected(self, converter_app, text_pdf):
        result = converter_app.find_text_matches(text_pdf, regexes=["[unclosed"])
        assert result["success"] is False

    def test_searching_for_nothing_is_rejected(self, converter_app, text_pdf):
        assert converter_app.find_text_matches(text_pdf)["success"] is False

    def test_missing_file_is_an_error_not_a_crash(self, converter_app):
        assert converter_app.find_text_matches("/nope/x.pdf", terms=["a"])["success"] is False


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
