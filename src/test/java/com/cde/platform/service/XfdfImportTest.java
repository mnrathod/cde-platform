package com.cde.platform.service;

import com.cde.platform.model.Annotation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.cde.platform.service.XfdfFixtures.annotation;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading XFDF written somewhere else.
 *
 * <p>The input is a file from another vendor's tool, so it is untrusted in
 * both senses: it may be damaged, and it may be hostile. A damaged file
 * yields the annotations that could be read rather than nothing at all, and
 * a DOCTYPE declaration is refused outright — XFDF is XML, and an external
 * entity in it is a file-disclosure vector (§5.13.9).
 */
class XfdfImportTest {

    private XfdfService service;

    @BeforeEach
    void setUp() {
        service = new XfdfService();
    }


    private String wrap(String annots) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <xfdf xmlns="http://ns.adobe.com/xfdf/">
              <f href="d.pdf"/>
              <annots>
            %s
              </annots>
            </xfdf>
            """.formatted(annots);
    }

    private List<XfdfService.ImportedAnnotation> parse(String annots) throws Exception {
        return service.fromXfdf(wrap(annots).getBytes());
    }

    @Test
    @DisplayName("maps a square to a rect tool")
    void squareBecomesRect() throws Exception {
        var imported = parse("<square page=\"0\" rect=\"10,20,110,70\" color=\"#FF0000\"/>");

        assertThat(imported).hasSize(1);
        assertThat(imported.get(0).shapeData()).contains("\"tool\":\"rect\"");
        assertThat(imported.get(0).pageNumber()).isEqualTo(1);
    }

    /**
     * Regression: every polygon was imported as a cloud, so a plain
     * polygon round-tripped into the wrong shape and type.
     */
    @Test
    @DisplayName("distinguishes a plain polygon from a cloud by its intent")
    void polygonVersusCloud() throws Exception {
        var plain = parse("<polygon page=\"0\" vertices=\"1,2;3,4;5,6\"/>").get(0);
        var cloud = parse(
            "<polygon page=\"0\" IT=\"PolygonCloud\" vertices=\"1,2;3,4;5,6\"/>").get(0);

        assertThat(plain.shapeData()).contains("\"tool\":\"polygon\"");
        assertThat(plain.type()).isEqualTo(Annotation.AnnotationType.MARKUP);

        assertThat(cloud.shapeData()).contains("\"tool\":\"cloud\"");
        assertThat(cloud.type()).isEqualTo(Annotation.AnnotationType.CLOUD);
    }

    @Test
    @DisplayName("imports a polyline as an open shape")
    void polyline() throws Exception {
        var imported = parse("<polyline page=\"0\" vertices=\"1,2;3,4;5,6\"/>");

        assertThat(imported.get(0).shapeData()).contains("\"tool\":\"polyline\"");
    }

    @ParameterizedTest(name = "<{0}> imports as the {0} tool")
    @ValueSource(strings = {"underline", "strikeout", "squiggly"})
    @DisplayName("imports text-markup elements as their own tools")
    void textMarkup(String element) throws Exception {
        var imported = parse(
            "<" + element + " page=\"0\" rect=\"10,20,110,40\"/>");

        assertThat(imported.get(0).shapeData()).contains("\"tool\":\"" + element + "\"");
        assertThat(imported.get(0).type().name()).isEqualTo(element.toUpperCase());
    }

    @Test
    @DisplayName("converts ink gestures into freehand points")
    void inkBecomesFreehand() throws Exception {
        var imported = parse("""
            <ink page="0" rect="0,0,100,100">
              <inklist><gesture>1,2;3,4;5,6</gesture></inklist>
            </ink>
            """);

        assertThat(imported.get(0).shapeData())
            .contains("\"tool\":\"freehand\"")
            .contains("\"x\":1.0")
            .contains("\"y\":6.0");
    }

    @Test
    @DisplayName("reads contents into the comment")
    void readsContents() throws Exception {
        var imported = parse("""
            <text page="0" rect="0,0,20,20"><contents>Check this detail</contents></text>
            """);

        assertThat(imported.get(0).comment()).isEqualTo("Check this detail");
        assertThat(imported.get(0).type()).isEqualTo(Annotation.AnnotationType.COMMENT);
    }

    @Test
    @DisplayName("converts XFDF's zero-based page to a one-based page number")
    void pageIsOneBased() throws Exception {
        assertThat(parse("<square page=\"4\" rect=\"0,0,1,1\"/>").get(0).pageNumber())
            .isEqualTo(5);
    }

    @Test
    @DisplayName("salvages the readable annotations from a damaged XFDF")
    void salvagesWhatItCan() throws Exception {
        // XFDF arrives from other vendors' tools, so a file that is well
        // formed XML but nonsense inside is the normal case rather than
        // the exceptional one. Importing eleven of twelve markups beats
        // rejecting the file, and the per-element guard is what makes the
        // difference — hence the assertion on the survivor, not on a log
        // line.
        var imported = parse("""
            <square page="0" rect="not,a,rectangle,at,all"/>
            <circle page="zero" rect=""/>
            <ink page="0"><inklist><gesture>;;,,;</gesture></inklist></ink>
            <polygon page="0" vertices=";;"/>
            <freetext page="0" rect="0"/>
            <square page="0" rect="0,0,10,10"/>
            """);

        assertThat(imported)
            .extracting(XfdfService.ImportedAnnotation::shapeData)
            .anySatisfy(shape -> assertThat(shape).contains("\"tool\":\"rect\""));
    }

    @Test
    @DisplayName("ignores an unsupported element rather than failing the import")
    void skipsUnsupported() throws Exception {
        var imported = parse("""
            <caret page="0" rect="0,0,1,1"/>
            <square page="0" rect="0,0,10,10"/>
            """);

        assertThat(imported).hasSize(1);
    }

    @Test
    @DisplayName("returns nothing for an XFDF with no annots element")
    void noAnnots() throws Exception {
        var imported = service.fromXfdf(
            "<?xml version=\"1.0\"?><xfdf xmlns=\"http://ns.adobe.com/xfdf/\"/>".getBytes());

        assertThat(imported).isEmpty();
    }

    @Test
    @DisplayName("rejects a DOCTYPE declaration, closing the XXE vector")
    void rejectsDoctype() {
        String hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <xfdf xmlns="http://ns.adobe.com/xfdf/"><annots>
              <square page="0" rect="0,0,1,1"/>
            </annots></xfdf>
            """;

        assertThat(
            org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class, () -> service.fromXfdf(hostile.getBytes()))
        ).isNotNull();
    }

    // ── Lines, arrows and dimensions ──────────────────────────────────────

    @Test
    @DisplayName("a line with no head comes back as a plain line")
    void plainLineHasNoHead() throws Exception {
        var imported = parse("<line page=\"0\" start=\"10,20\" end=\"90,80\"/>");

        assertThat(imported.get(0).shapeData()).contains("\"tool\":\"line\"");
        assertThat(imported.get(0).type()).isEqualTo(Annotation.AnnotationType.MARKUP);
    }

    @Test
    @DisplayName("a line with an arrowhead comes back as an arrow")
    void lineWithAHeadBecomesAnArrow() throws Exception {
        // The head test used to compare the literal "head" against the
        // attribute's value, so it never matched and every arrow another tool
        // drew arrived as a plain line.
        var imported = parse(
            "<line page=\"0\" start=\"10,20\" end=\"90,80\" head=\"OpenArrow\"/>");

        assertThat(imported.get(0).shapeData()).contains("\"tool\":\"arrow\"");
        assertThat(imported.get(0).type()).isEqualTo(Annotation.AnnotationType.ARROW);
    }

    @Test
    @DisplayName("a measurement line comes back as a dimension, carrying its reading")
    void dimensionLineKeepsItsMeasurement() throws Exception {
        var imported = parse("""
            <line page="0" start="10,20" end="90,20" IT="LineDimension">
              <contents>4 250 mm</contents>
            </line>""");

        assertThat(imported.get(0).shapeData())
            .contains("\"tool\":\"dimension\"")
            .contains("4 250 mm");
        assertThat(imported.get(0).type()).isEqualTo(Annotation.AnnotationType.DIMENSION);
    }

    @Test
    @DisplayName("a dimension with no reading written on it still imports")
    void dimensionWithoutAMeasurement() throws Exception {
        // The reading is optional in XFDF, and a dimension without one is a
        // line somebody measured and did not label — still a dimension.
        var imported = parse(
            "<line page=\"0\" start=\"10,20\" end=\"90,20\" IT=\"LineDimension\"/>");

        assertThat(imported.get(0).type()).isEqualTo(Annotation.AnnotationType.DIMENSION);
        assertThat(imported.get(0).shapeData()).doesNotContain("measurement");
    }

    @Test
    @DisplayName("a dimension marked as such wins over an arrowhead")
    void dimensionOutranksAHead() throws Exception {
        // A dimension line is conventionally drawn with heads at both ends, so
        // both conditions hold at once and the order they are tested in is the
        // behaviour.
        var imported = parse("<line page=\"0\" start=\"10,20\" end=\"90,20\""
            + " IT=\"LineDimension\" head=\"OpenArrow\"/>");

        assertThat(imported.get(0).type()).isEqualTo(Annotation.AnnotationType.DIMENSION);
    }

    @Test
    @DisplayName("a head attribute present but empty is not an arrow")
    void anEmptyHeadIsNoHead() throws Exception {
        // Some exporters write every attribute whether or not it has a value.
        var imported = parse(
            "<line page=\"0\" start=\"10,20\" end=\"90,80\" head=\"\"/>");

        assertThat(imported.get(0).type()).isEqualTo(Annotation.AnnotationType.MARKUP);
    }

    @Test
    @DisplayName("a line whose endpoint has only one number reads the missing one as zero")
    void halfAnEndpointIsNotAFailure() throws Exception {
        // Truncated coordinates come from a file that was cut short. Dropping
        // the annotation would lose the rest of someone's markup over it.
        var imported = parse("<line page=\"0\" start=\"10\" end=\"90\"/>");

        assertThat(imported).hasSize(1);
        assertThat(imported.get(0).shapeData())
            .contains("\"y1\":0.0")
            .contains("\"y2\":0.0");
    }

    @Test
    @DisplayName("a line with no coordinates at all falls back to a visible default")
    void aLineWithNoCoordinates() throws Exception {
        var imported = parse("<line page=\"0\"/>");

        assertThat(imported).hasSize(1);
        assertThat(imported.get(0).shapeData()).contains("\"x2\":100.0");
    }

    // ── Circles, ellipses and rectangles ──────────────────────────────────

    @Test
    @DisplayName("a circle in a square box comes back as a circle")
    void squareBoundsMeanACircle() throws Exception {
        var imported = parse("<circle page=\"0\" rect=\"10,10,110,110\"/>");

        assertThat(imported.get(0).shapeData())
            .contains("\"tool\":\"circle\"")
            .contains("\"r\":50.0");
    }

    @Test
    @DisplayName("a circle in an oblong box comes back as an ellipse")
    void oblongBoundsMeanAnEllipse() throws Exception {
        // XFDF has no ellipse element, so the bounding box is the only thing
        // that distinguishes the two and reading it back is the import side of
        // that decision.
        var imported = parse("<circle page=\"0\" rect=\"10,10,210,110\"/>");

        assertThat(imported.get(0).shapeData())
            .contains("\"tool\":\"ellipse\"")
            .contains("\"width\":200.0");
    }

    @Test
    @DisplayName("a box within rounding of square is still a circle")
    void nearlySquareIsStillACircle() throws Exception {
        // Coordinates survive a round trip through a text format, so an exact
        // comparison would turn a circle into an ellipse on the strength of a
        // final decimal place.
        var imported = parse("<circle page=\"0\" rect=\"10,10,110,110.0001\"/>");

        assertThat(imported.get(0).shapeData()).contains("\"tool\":\"circle\"");
    }

    @Test
    @DisplayName("a rect with fewer than four numbers reads the rest as zero")
    void shortRectIsPaddedRatherThanRefused() throws Exception {
        var imported = parse("<square page=\"0\" rect=\"10,20\"/>");

        assertThat(imported).hasSize(1);
    }

    @Test
    @DisplayName("a rect with no numbers at all falls back to a visible default")
    void missingRectFallsBackToADefault() throws Exception {
        // Zero-sized would be invisible, which is worse than wrong: the
        // annotation would be in the list with nothing on the page.
        var imported = parse("<square page=\"0\"/>");

        assertThat(imported.get(0).shapeData()).contains("\"width\":100.0");
    }

    @Test
    @DisplayName("a coordinate that is not a number reads as zero rather than failing")
    void unreadableCoordinateReadsAsZero() throws Exception {
        var imported = parse("<square page=\"0\" rect=\"ten,20,110,70\"/>");

        assertThat(imported).hasSize(1);
        assertThat(imported.get(0).shapeData()).contains("\"x\":0.0");
    }

    // ── Ink, polygons and polylines ───────────────────────────────────────

    @Test
    @DisplayName("an ink annotation with no gesture imports as an empty stroke")
    void inkWithNoGesture() throws Exception {
        // Writing nothing rather than dropping it keeps the author, the page
        // and the comment, which is the part a reviewer replied to.
        var imported = parse("<ink page=\"0\"><inklist/></ink>");

        assertThat(imported).hasSize(1);
        assertThat(imported.get(0).shapeData()).contains("\"points\":[]");
    }

    @Test
    @DisplayName("a point missing its second coordinate is dropped, not read as zero")
    void halfAPointIsDropped() throws Exception {
        // Within a stroke the position of each point matters relative to its
        // neighbours, so inventing a zero would put a spike through the
        // drawing. Omitting the point leaves the stroke's shape intact.
        var imported = parse(
            "<ink page=\"0\"><inklist><gesture>10,20;30;50,60</gesture></inklist></ink>");

        assertThat(imported.get(0).shapeData())
            .contains("\"x\":10.0")
            .contains("\"x\":50.0")
            .doesNotContain("\"x\":30.0");
    }

    @Test
    @DisplayName("a polygon with no vertices imports as an empty shape")
    void polygonWithNoVertices() throws Exception {
        var imported = parse("<polygon page=\"0\"/>");

        assertThat(imported).hasSize(1);
        assertThat(imported.get(0).shapeData()).contains("\"points\":[]");
    }

    @Test
    @DisplayName("a polyline with a half vertex drops that vertex only")
    void polylineDropsHalfVertices() throws Exception {
        var imported = parse("<polyline page=\"0\" vertices=\"10,20;30;50,60\"/>");

        assertThat(imported.get(0).shapeData())
            .contains("\"x\":10.0")
            .doesNotContain("\"x\":30.0");
    }

    // ── Text markup ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a squiggly import keeps its own type rather than the group's")
    void squigglyKeepsItsType() throws Exception {
        // The three text-markup elements share one branch and differ only in
        // the type they map to, so a fallback arm quietly turns the other two
        // into squigglies.
        var imported = parse("<squiggly page=\"0\" rect=\"10,20,110,40\"/>");

        assertThat(imported.get(0).type()).isEqualTo(Annotation.AnnotationType.SQUIGGLY);
    }

    @Test
    @DisplayName("a free-text box with a leader line comes back as a callout")
    void freeTextWithALeaderIsACallout() throws Exception {
        var imported = parse("""
            <freetext page="0" rect="10,20,110,70" IT="FreeTextCallout">
              <contents>Check this detail</contents>
            </freetext>""");

        assertThat(imported.get(0).shapeData())
            .contains("\"tool\":\"callout\"")
            .contains("\"x2\":")
            .contains("Check this detail");
    }

    @Test
    @DisplayName("a free-text box without one comes back as plain text")
    void freeTextWithoutALeaderIsPlainText() throws Exception {
        var imported = parse(
            "<freetext page=\"0\" rect=\"10,20,110,70\"><contents>Note</contents></freetext>");

        assertThat(imported.get(0).shapeData())
            .contains("\"tool\":\"text\"")
            .doesNotContain("\"x2\":");
    }

    @Test
    @DisplayName("a stamp with no name carries the conventional wording")
    void stampWithoutANameHasADefault() throws Exception {
        var imported = parse("<stamp page=\"0\" rect=\"10,20,110,70\"/>");

        assertThat(imported.get(0).type()).isEqualTo(Annotation.AnnotationType.STAMP);
        assertThat(imported.get(0).shapeData()).contains("APPROVED");
    }

    @Test
    @DisplayName("a stamp's own name is kept when it has one")
    void stampKeepsItsName() throws Exception {
        var imported = parse("<stamp page=\"0\" rect=\"10,20,110,70\" name=\"FOR REVIEW\"/>");

        assertThat(imported.get(0).shapeData()).contains("FOR REVIEW");
    }

    @Test
    @DisplayName("a highlight is recoloured to the conventional yellow")
    void highlightIsAlwaysYellow() throws Exception {
        var imported = parse(
            "<highlight page=\"0\" rect=\"10,20,110,40\" color=\"#00FF00\"/>");

        assertThat(imported.get(0).shapeData()).contains("#FFFF00");
    }

    // ── Attributes and defaults ───────────────────────────────────────────

    @Test
    @DisplayName("an annotation with no colour gets a visible one")
    void missingColourGetsADefault() throws Exception {
        var imported = parse("<square page=\"0\" rect=\"10,20,110,70\"/>");

        assertThat(imported.get(0).shapeData()).contains("#FF0000");
    }

    @Test
    @DisplayName("a colour attribute present but empty is treated as absent")
    void blankColourIsTreatedAsAbsent() throws Exception {
        var imported = parse("<square page=\"0\" rect=\"10,20,110,70\" color=\"\"/>");

        assertThat(imported.get(0).shapeData()).contains("#FF0000");
    }

    @Test
    @DisplayName("a stroke width that is not a number falls back to a visible one")
    void unreadableWidthFallsBack() throws Exception {
        var imported = parse(
            "<square page=\"0\" rect=\"10,20,110,70\" width=\"thick\"/>");

        assertThat(imported.get(0).shapeData()).contains("\"strokeWidth\":2");
    }

    @Test
    @DisplayName("an annotation with no page attribute lands on the first page")
    void missingPageIsTheFirst() throws Exception {
        // XFDF counts from zero and the model from one, so an absent page has
        // to come out as 1 — a 0 would be a page no document has.
        var imported = parse("<square rect=\"10,20,110,70\"/>");

        assertThat(imported.get(0).pageNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("an annotation with no author recorded still imports")
    void missingAuthorStillImports() throws Exception {
        var imported = parse("<square page=\"0\" rect=\"10,20,110,70\"/>");

        assertThat(imported).hasSize(1);
        assertThat(imported.get(0).authorName()).isEmpty();
    }

    @Test
    @DisplayName("non-element nodes between annotations are skipped")
    void textNodesBetweenAnnotationsAreSkipped() throws Exception {
        // Whitespace and comments are nodes too, and treating one as an
        // element would throw on the first file that was pretty-printed.
        var imported = parse("""
            <!-- exported by another tool -->
            <square page="0" rect="10,20,110,70"/>
            <!-- end -->""");

        assertThat(imported).hasSize(1);
    }
}
