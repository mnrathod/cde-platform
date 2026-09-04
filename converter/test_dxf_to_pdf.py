"""
Tests for converting a CAD drawing to PDF.

The gap these cover: a DXF or DWG submitted for conversion produced an SVG,
because SVG is what the viewer wants, and the caller asking for a PDF got a
JSON reply it could not read followed by "the converter could not convert that
file" — which was untrue. The drawing had converted perfectly well, into the
wrong thing.

Three properties decide whether the PDF is any good, and each is asserted
rather than eyeballed:

  * it is a PDF, of the page size the export claims;
  * its text is real text, so it can be searched, selected and read aloud —
    a drawing whose labels are vector outlines is an inaccessible export
    (§1A.4), and the viewer's invisible-overlay trick does not survive the
    conversion because LibreOffice drops text it cannot see;
  * it is on white. The viewer renders on near-black, which is right on a
    screen and wrong on paper and in every PDF reader's default view.
"""
import ezdxf
import pypdf
import pytest

import app


pytestmark = pytest.mark.skipif(
    app.find_libreoffice() is None,
    reason="LibreOffice converts the print render to PDF and is not installed here")


@pytest.fixture
def titled_drawing(tmp_path):
    """A bordered plan with three labels at known points."""
    doc = ezdxf.new("R2010", setup=True)
    msp = doc.modelspace()
    msp.add_lwpolyline([(0, 0), (200, 0), (200, 120), (0, 120), (0, 0)])
    msp.add_line((0, 90), (200, 90))
    msp.add_circle((100, 45), radius=25)
    msp.add_text("RIVERSIDE DEPOT", height=8).set_placement((10, 100))
    msp.add_text("GA PLAN LEVEL 02", height=5).set_placement((10, 94))
    msp.add_text("SCALE 1:100", height=4).set_placement((150, 94))
    path = tmp_path / "plan.dxf"
    doc.saveas(path)
    return path


def pdf_of(result):
    assert result.get("success"), result.get("error")
    assert result.get("type") == "pdf", result
    return pypdf.PdfReader(__import__("io").BytesIO(result["pdfBytes"]))


class TestADrawingBecomesAPdf:

    def test_a_dxf_converts_to_pdf_rather_than_svg(self, titled_drawing):
        # The whole defect in one assertion: asking for PDF used to answer
        # with an SVG payload the caller could not use.
        result = app.convert(str(titled_drawing), "application/dxf", "PDF")
        assert result.get("success"), result.get("error")
        assert result.get("type") == "pdf"
        assert result["pdfBytes"].startswith(b"%PDF-")
        assert "svg" not in result

    def test_the_page_is_a4_landscape(self, titled_drawing):
        page = pdf_of(app.dxf_file_to_pdf(str(titled_drawing))).pages[0]
        width, height = float(page.mediabox.width), float(page.mediabox.height)
        # 420x297mm in points, within a rounding tolerance.
        assert width == pytest.approx(1190.55, abs=2), width
        assert height == pytest.approx(841.89, abs=2), height
        assert width > height, "a landscape sheet came out portrait"


class TestTheTextSurvivesAsText:

    def test_every_label_is_extractable_from_the_pdf(self, titled_drawing):
        text = pdf_of(app.dxf_file_to_pdf(str(titled_drawing))).pages[0].extract_text()
        for label in ("RIVERSIDE DEPOT", "GA PLAN LEVEL 02", "SCALE 1:100"):
            assert label in text, f"{label!r} is not readable text in {text!r}"

    def test_the_labels_land_where_the_drawing_puts_them(self, titled_drawing):
        # Placement is the failure that hides: a PDF with its text layer
        # transposed looks identical and sends every search to the wrong
        # place. The drawing is 200x120 with the title top-left and the
        # scale note top-right, so their relative order is known.
        page = pdf_of(app.dxf_file_to_pdf(str(titled_drawing))).pages[0]
        found = {}
        page.extract_text(visitor_text=lambda t, cm, tm, fd, fs: found.setdefault(
            t.strip(), (tm[4], tm[5])) if t.strip() else None)

        title = found["RIVERSIDE DEPOT"]
        subtitle = found["GA PLAN LEVEL 02"]
        scale = found["SCALE 1:100"]

        assert title[1] > subtitle[1], "the title should sit above the subtitle"
        assert scale[0] > title[0], "the scale note should sit right of the title"
        # Both on the same DXF row, so they share a baseline.
        assert scale[1] == pytest.approx(subtitle[1], abs=2)

    def test_an_unlabelled_drawing_still_converts(self, tmp_path):
        # The text layer is additive and never fatal: a drawing with no text
        # must still produce a page rather than an error.
        doc = ezdxf.new("R2010")
        doc.modelspace().add_circle((0, 0), radius=50)
        path = tmp_path / "blank.dxf"
        doc.saveas(path)

        result = app.dxf_file_to_pdf(str(path))
        assert result.get("success"), result.get("error")
        assert len(pdf_of(result).pages) == 1


class TestTheGlyphsAreNotDrawnTwice:

    def test_text_contributes_no_paths_to_the_print_render(self, tmp_path):
        # The export supplies text as real <text>, so ezdxf must not also draw
        # it as outlines. If it does, the PDF carries both — glyph paths and
        # text over them, at slightly different metrics — which extracts
        # correctly and looks doubled and blurred. Stated as a property so it
        # holds for any drawing: adding labels adds no geometry.
        def paths_in(build):
            doc = ezdxf.new("R2010")
            msp = doc.modelspace()
            msp.add_lwpolyline([(0, 0), (200, 0), (200, 120), (0, 120), (0, 0)])
            build(msp)
            return app._print_svg(doc, doc.modelspace()).count("<path")

        bare = paths_in(lambda msp: None)
        lettered = paths_in(lambda msp: [
            msp.add_text("PLENTY OF WORDS HERE", height=8).set_placement((10, 100)),
            msp.add_text("AND SOME MORE OF THEM", height=8).set_placement((10, 80)),
        ])
        assert lettered == bare, (
            f"text added {lettered - bare} paths — the glyphs are being drawn "
            f"as outlines as well as set as text, so the PDF is doubled")


class TestPlacementIsCalibratedOnWhatWasDrawn:

    def test_a_label_outside_the_drawing_does_not_shift_the_others(self, tmp_path):
        # The print render draws no text, so the mapping from drawing units to
        # page must be measured against geometry alone. Measuring it against
        # everything — including a stray note far outside the frame, which
        # real drawings carry — squeezes the whole sheet and moves every label
        # that is on it. The drawing still looks right; only the text is wrong.
        doc = ezdxf.new("R2010")
        msp = doc.modelspace()
        msp.add_lwpolyline([(0, 0), (100, 0), (100, 60), (0, 60), (0, 0)])
        msp.add_text("EDGE", height=4).set_placement((95, 30))
        msp.add_text("STRAY NOTE WELL OUTSIDE THE FRAME", height=4).set_placement((400, 30))
        path = tmp_path / "stray.dxf"
        doc.saveas(path)

        page = pdf_of(app.dxf_file_to_pdf(str(path))).pages[0]
        found = {}
        page.extract_text(visitor_text=lambda t, cm, tm, fd, fs: found.setdefault(
            t.strip(), (tm[4], tm[5])) if t.strip() else None)

        width = float(page.mediabox.width)
        edge_x = found["EDGE"][0]
        # The frame fills the page, so a label at 95% of its width belongs in
        # the last quarter. Calibrated against the stray note instead, the
        # frame occupies a fifth of the sheet and this lands in the first.
        assert edge_x > width * 0.75, (
            f"'EDGE' at {edge_x:.0f} of {width:.0f}pt — the mapping was "
            f"calibrated against something that was never drawn")


def ink_coverage(pdf_bytes):
    """The fraction of the page that is not background."""
    pdfium = pytest.importorskip("pypdfium2")
    image = pdfium.PdfDocument(pdf_bytes)[0].render(scale=0.5).to_pil().convert("L")
    levels = image.histogram()
    return sum(levels[:200]) / (image.size[0] * image.size[1])


class TestTheDrawingIsActuallyVisible:
    """
    The check every other test in this file passed while the page was blank.

    The first working version rendered the geometry white on a white ground.
    It was a valid A4 PDF with correctly placed, extractable text and a white
    background — so the page-size test, the text tests and the background test
    all held, and the file was useless. Nothing asked whether the drawing had
    been drawn.
    """

    def test_the_geometry_reaches_the_page(self, titled_drawing):
        result = app.dxf_file_to_pdf(str(titled_drawing))
        assert result.get("success"), result.get("error")
        coverage = ink_coverage(result["pdfBytes"])
        assert coverage > 0.002, (
            f"only {coverage:.4%} of the page has ink on it — the drawing is "
            f"missing or is being rendered in the background colour")

    def test_geometry_outweighs_the_labels(self, titled_drawing):
        # Distinguishes "the drawing rendered" from "only the text rendered",
        # which is what a blank drawing with a working text layer looks like.
        drawn = ink_coverage(app.dxf_file_to_pdf(str(titled_drawing))["pdfBytes"])

        doc = ezdxf.new("R2010", setup=True)
        msp = doc.modelspace()
        msp.add_lwpolyline([(0, 0), (200, 0), (200, 120), (0, 120), (0, 0)])
        msp.add_text("RIVERSIDE DEPOT", height=8).set_placement((10, 100))
        msp.add_text("GA PLAN LEVEL 02", height=5).set_placement((10, 94))
        msp.add_text("SCALE 1:100", height=4).set_placement((150, 94))
        path = titled_drawing.parent / "frame_only.dxf"
        doc.saveas(path)
        without_interior = ink_coverage(app.dxf_file_to_pdf(str(path))["pdfBytes"])

        # The fixture adds a dividing line and a 25-radius circle on top of the
        # same frame and labels, so it must carry measurably more ink.
        assert drawn > without_interior * 1.05, (
            f"{drawn:.4%} with the interior geometry vs {without_interior:.4%} "
            f"without it — the lines and circle are not being drawn")

    def test_the_lines_are_dark_enough_to_read(self, titled_drawing):
        pdfium = pytest.importorskip("pypdfium2")
        result = app.dxf_file_to_pdf(str(titled_drawing))
        image = pdfium.PdfDocument(result["pdfBytes"])[0].render(scale=0.5).to_pil().convert("L")
        darkest = next(level for level, count in enumerate(image.histogram()) if count)
        assert darkest < 80, (
            f"nothing on the page is darker than {darkest}/255; lines this "
            f"faint do not print")


class TestItIsFitToPrint:

    def test_the_background_is_white_not_the_viewer_dark(self, titled_drawing):
        pdfium = pytest.importorskip("pypdfium2")
        result = app.dxf_file_to_pdf(str(titled_drawing))
        page = pdfium.PdfDocument(result["pdfBytes"])[0]
        image = page.render(scale=0.3).to_pil().convert("RGB")

        # Sample the corners rather than the middle, which the drawing covers.
        width, height = image.size
        corners = [image.getpixel(p) for p in
                   [(2, 2), (width - 3, 2), (2, height - 3), (width - 3, height - 3)]]
        for red, green, blue in corners:
            assert min(red, green, blue) > 200, \
                f"exported on a dark ground ({red},{green},{blue}) — unreadable on paper"


class TestTheViewerIsUnaffected:

    def test_without_a_target_format_the_viewer_still_gets_svg(self, titled_drawing):
        result = app.convert(str(titled_drawing), "application/dxf")
        assert result.get("success"), result.get("error")
        assert result.get("svg", "").startswith("<svg") or "<svg" in result.get("svg", "")
        assert "pdfBytes" not in result

    def test_the_viewer_keeps_its_invisible_text_layer(self, titled_drawing):
        # The two renders want opposite things and must not converge: the
        # viewer draws glyphs as paths with an invisible layer over them,
        # the export draws no glyphs and real text instead.
        svg = app.convert(str(titled_drawing), "application/dxf")["svg"]
        assert f'class="{app.TEXT_LAYER_CLASS}"' in svg
        assert 'fill="none"' in svg
        assert svg.count("<path") > 1, "glyph paths missing from the viewer render"


class TestWhatHasNoPdfForm:

    @pytest.mark.parametrize("name", ["model.ifc", "model.glb", "mesh.stl"])
    def test_a_model_is_refused_with_a_reason_a_caller_can_act_on(self, tmp_path, name):
        # Refusing is right — a model has no sheet, no scale and no page — but
        # the reason has to say what to do instead, or the caller retries.
        path = tmp_path / name
        path.write_bytes(b"not really a model")

        result = app.convert(str(path), "", "PDF")
        assert not result.get("success")
        assert "no defined PDF form" in result["error"]
        assert "tree" in result["error"], "the alternative is not named"

    def test_a_model_still_renders_for_the_viewer(self, tmp_path):
        # The refusal is about the PDF target only; the 3D viewer path is
        # untouched and must not start refusing files it used to serve.
        path = tmp_path / "mesh.stl"
        path.write_bytes(b"solid test\nendsolid test\n")

        result = app.convert(str(path), "")
        assert result.get("success")
        assert result.get("type") == "model3d_passthrough"


class TestTheDwgRouteUsesTheSameRenderer:

    def test_libredwg_renders_with_whatever_it_is_given(self, monkeypatch, tmp_path):
        # A real DWG needs ODA or LibreDWG and a binary fixture, so what is
        # pinned here is the wiring the fix introduced: the extraction step
        # must hand its DXF to the renderer the caller chose, not to the SVG
        # one it used to call by name. Getting this wrong makes DWG->PDF
        # silently return SVG again.
        extracted = tmp_path / "out.dxf"

        def fake_run(cmd, timeout=120):
            extracted.write_text("DXF CONTENT")
            return 0, "", ""

        monkeypatch.setattr(app, "find_dwg2dxf", lambda: "/usr/bin/dwg2dxf")
        monkeypatch.setattr(app, "run_cmd", fake_run)
        monkeypatch.setattr(app, "make_temp_dir", lambda: str(tmp_path))

        seen = {}

        def spy(content):
            seen["content"] = content
            return {"success": True, "type": "pdf", "pdfBytes": b"%PDF-1.4"}

        result = app.dwg_via_libredwg("/tmp/whatever.dwg", spy)
        assert result["type"] == "pdf"
        assert seen["content"] == "DXF CONTENT"

    def test_the_default_renderer_is_still_the_viewer_one(self, monkeypatch, tmp_path):
        # Callers that pass nothing must keep getting SVG, or the viewer
        # breaks the moment someone adds an argument elsewhere.
        (tmp_path / "out.dxf").write_text("DXF CONTENT")
        monkeypatch.setattr(app, "find_dwg2dxf", lambda: "/usr/bin/dwg2dxf")
        monkeypatch.setattr(app, "run_cmd", lambda cmd, timeout=120: (0, "", ""))
        monkeypatch.setattr(app, "make_temp_dir", lambda: str(tmp_path))

        seen = {}

        def viewer_render(content):
            seen["called"] = True
            return {"success": True, "svg": "<svg/>"}

        monkeypatch.setattr(app, "render_dxf_string", viewer_render)

        result = app.dwg_via_libredwg("/tmp/whatever.dwg")
        assert seen.get("called"), "the viewer renderer was not the default"
        assert "svg" in result
