"""
Tests for the searchable text layer added to converted CAD drawings.

ezdxf draws TEXT and MTEXT as vector paths, so a converted drawing used to
contain no `<text>` at all and searching one returned "no matches" for words
plainly visible on screen. That is the failure worth guarding: not a crash,
not a blank drawing, just an answer that looks like an answer.

The positions matter as much as the presence. A layer in the wrong place
would render identically — the glyphs underneath are unchanged — while every
search result pointed somewhere else on the sheet, so the placement is
asserted against known coordinates rather than merely checked for existence.
"""
import re
import xml.etree.ElementTree as ET

import ezdxf
import pytest

import app


SVG_NS = "{http://www.w3.org/2000/svg}"


def convert(path):
    result = app.render_dxf(str(path))
    assert result.get("success"), result.get("error")
    return result["svg"]


def texts_in(svg):
    """(text, x, y, font-size, anchor) for every element in the layer."""
    root = ET.fromstring(svg)
    out = []
    for group in root.iter(f"{SVG_NS}g"):
        if group.get("class") != app.TEXT_LAYER_CLASS:
            continue
        for node in group.iter(f"{SVG_NS}text"):
            out.append((
                node.text,
                float(node.get("x")),
                float(node.get("y")),
                float(node.get("font-size")),
                node.get("text-anchor"),
            ))
    return out


@pytest.fixture
def square_with_label(tmp_path):
    """A 1000x600 outline with one label at a known point."""
    doc = ezdxf.new("R2010")
    msp = doc.modelspace()
    msp.add_lwpolyline([(0, 0), (1000, 0), (1000, 600), (0, 600), (0, 0)])
    msp.add_text("GRID A-1", height=40).set_placement((100, 500))
    path = tmp_path / "plan.dxf"
    doc.saveas(path)
    return path


class TestTextIsPresent:

    def test_a_labelled_drawing_carries_searchable_text(self, square_with_label):
        svg = convert(square_with_label)
        assert "<text" in svg, "no text layer — drawing search cannot work"
        assert "GRID A-1" in [t[0] for t in texts_in(svg)]

    def test_the_drawing_itself_is_unchanged(self, square_with_label):
        # The glyphs are still drawn as paths; the layer is additive. If this
        # ever stops holding, the drawing starts depending on browser fonts.
        svg = convert(square_with_label)
        assert svg.count("<path") >= 1
        layer = re.search(r'<g class="%s"[^>]*>' % app.TEXT_LAYER_CLASS, svg)
        assert layer, "layer group missing"
        assert 'fill="none"' in layer.group(0), "layer would double-draw the glyphs"

    def test_multiline_mtext_is_indexed_as_one_phrase(self, tmp_path):
        doc = ezdxf.new("R2010")
        msp = doc.modelspace()
        msp.add_lwpolyline([(0, 0), (500, 0), (500, 500), (0, 500), (0, 0)])
        mtext = msp.add_mtext("CITY BRIDGE\\PEXPANSION", dxfattribs={"char_height": 25})
        mtext.set_location((50, 400))
        doc.saveas(tmp_path / "m.dxf")

        found = [t[0] for t in texts_in(convert(tmp_path / "m.dxf"))]
        # Searching for the phrase a reader sees must work even though the
        # drawing stores it as two lines.
        assert any("CITY BRIDGE EXPANSION" == t for t in found), found


class TestPlacement:

    def test_the_label_lands_where_the_drawing_puts_it(self, square_with_label):
        svg = convert(square_with_label)
        root = ET.fromstring(svg)
        text = texts_in(svg)[0]
        _, x, y, size, _ = text

        # The outline spans 0..1000 in x and 0..600 in y, and the label sits
        # at (100, 500) — a tenth across and five sixths up. Checked as
        # fractions of the rendered extent so the assertion does not depend on
        # the page size ezdxf happens to choose.
        min_x, min_y, max_x, max_y = app._rendered_extent(svg)
        across = (x - min_x) / (max_x - min_x)
        up = (max_y - y) / (max_y - min_y)

        assert across == pytest.approx(0.1, abs=0.03), f"x fraction {across}"
        assert up == pytest.approx(500 / 600, abs=0.03), f"y fraction {up}"
        assert size > 0

    def test_font_size_tracks_the_drawing_scale(self, square_with_label):
        svg = convert(square_with_label)
        _, _, _, size, _ = texts_in(svg)[0]
        min_x, _, max_x, _ = app._rendered_extent(svg)
        # Text height is 40 of the 1000-unit width, so roughly 4% of it.
        assert size / (max_x - min_x) == pytest.approx(0.04, abs=0.012)

    def test_y_axis_is_flipped(self, tmp_path):
        # DXF counts up from the bottom, SVG down from the top. Getting this
        # backwards mirrors every label about the middle of the sheet, which
        # looks plausible on a symmetric drawing.
        doc = ezdxf.new("R2010")
        msp = doc.modelspace()
        msp.add_lwpolyline([(0, 0), (100, 0), (100, 100), (0, 100), (0, 0)])
        msp.add_text("LOW", height=5).set_placement((10, 10))
        msp.add_text("HIGH", height=5).set_placement((10, 90))
        doc.saveas(tmp_path / "flip.dxf")

        found = {t[0]: t[2] for t in texts_in(convert(tmp_path / "flip.dxf"))}
        assert found["HIGH"] < found["LOW"], "higher in the drawing must be nearer the top"


class TestDegradesQuietly:

    def test_a_drawing_with_no_text_gets_no_layer(self, tmp_path):
        doc = ezdxf.new("R2010")
        doc.modelspace().add_lwpolyline([(0, 0), (10, 0), (10, 10), (0, 10), (0, 0)])
        doc.saveas(tmp_path / "bare.dxf")

        svg = convert(tmp_path / "bare.dxf")
        assert app.TEXT_LAYER_CLASS not in svg
        assert "<path" in svg, "the drawing itself must still render"

    def test_no_layer_rather_than_a_misplaced_one(self):
        # Nothing to calibrate against: an SVG with no geometry gives no
        # extent, so there is no way to know where the text belongs. Returning
        # nothing is correct; guessing is not.
        assert app.build_text_layer("<svg></svg>", [], None) == ""

    def test_unreadable_svg_costs_the_layer_not_the_drawing(self, square_with_label):
        # build_text_layer is called inside a try in the conversion path, but
        # it should not need rescuing for ordinary bad input.
        doc = ezdxf.readfile(str(square_with_label))
        assert app.build_text_layer("not svg at all", doc.modelspace(), doc) == ""
