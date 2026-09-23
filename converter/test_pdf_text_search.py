"""
Finding the text in a PDF that a redaction will cover.

<p>Asserted on the rectangles, not only on the count: a match reported at
the wrong place produces a redaction that looks exactly like a correct one
and covers the wrong words.
"""
import pypdf
import pytest

from conftest import (
    acroform_field_names, page_annotation_count, page_sizes, page_text_lengths
)


class TestGroupByPage:
    def test_groups_on_zero_based_index(self, converter_app):
        grouped = converter_app._group_by_page([{"page": 1}, {"page": 3}, {"page": 1}])
        assert sorted(grouped) == [0, 2]
        assert len(grouped[0]) == 2

    def test_honours_an_alternate_key(self, converter_app):
        grouped = converter_app._group_by_page([{"pageNumber": 2}], key="pageNumber")
        assert list(grouped) == [1]


# ── Form inspection ──────────────────────────────────────────────────
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
