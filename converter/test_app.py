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

from conftest import page_sizes, page_text_lengths


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
        result = converter_app.redact_pdf(text_pdf, self.REGION, out, burn=True)
        assert result["redactedPages"] == 1

        before, after = page_text_lengths(text_pdf), page_text_lengths(out)
        assert after[1] == 0, "redacted page should lose its text"
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
