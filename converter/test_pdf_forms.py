"""
A PDF's interactive form: reading it, and filling it in.

<p>The flag arithmetic is the fiddly half — a field's kind is encoded in
bits that overlap, so a radio group and a checkbox differ by one flag and
are easy to read as each other. The filling half is asserted on the file
that comes out rather than on the return value, because "filled" and
"reported as filled" came apart here before.
"""
import pypdf
import pytest

from conftest import (
    acroform_field_names, page_annotation_count, page_sizes, page_text_lengths
)


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
