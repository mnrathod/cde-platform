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
 * Turning this product's markup into XFDF.
 *
 * <p>XFDF is how markup reaches Bluebeam, Acrobat and Procore, so the
 * assertions name the specific elements and attributes those readers look
 * for. An export that silently degrades a shape looks perfectly fine until
 * somebody opens the file somewhere else, which is the failure these exist
 * to catch.
 */
class XfdfExportTest {

    private XfdfService service;

    @BeforeEach
    void setUp() {
        service = new XfdfService();
    }

    private String exportOne(Annotation.AnnotationType type, String shapeData) {
        return service.toXfdf(List.of(annotation(type, shapeData)), "drawing.pdf");
    }


    @Test
    @DisplayName("produces a well-formed XFDF envelope naming the source file")
    void producesEnvelope() {
        String xfdf = service.toXfdf(List.of(), "CBE-ST-001.pdf");

        assertThat(xfdf)
            .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            .contains("xmlns=\"http://ns.adobe.com/xfdf/\"")
            .contains("<f href=\"CBE-ST-001.pdf\"/>")
            .contains("<annots>")
            .endsWith("</xfdf>");
    }

    /**
     * Regression: shapeToXfdf read a "shape" key that the frontend has
     * never written — its ShapeData key is "tool" — so every MARKUP
     * annotation was exported as a line regardless of its real shape.
     */
    @ParameterizedTest(name = "tool={0} exports as <{1}>")
    @CsvSource({
        "line,     line",
        "arrow,    line",
        "rect,     square",
        "circle,   circle",
        "ellipse,  circle",
        "freehand, ink",
        "cloud,    polygon",
        "polygon,  polygon",
        "polyline, polyline",
    })
    @DisplayName("dispatches on the tool field, not a non-existent shape field")
    void dispatchesOnTool(String tool, String element) {
        String shapeData = """
            {"tool":"%s","x":10,"y":20,"width":100,"height":50,
             "x1":0,"y1":0,"x2":80,"y2":60,"cx":50,"cy":50,"r":25,
             "points":[{"x":1,"y":2},{"x":3,"y":4},{"x":5,"y":6}],
             "color":"#FF0000","strokeWidth":2}
            """.formatted(tool);

        assertThat(exportOne(Annotation.AnnotationType.MARKUP, shapeData))
            .contains("<" + element + " ");
    }

    @Test
    @DisplayName("marks a cloud with IT=PolygonCloud so it imports back as a cloud")
    void cloudCarriesIntent() {
        String xfdf = exportOne(Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"cloud\",\"points\":[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]}");

        assertThat(xfdf).contains("IT=\"PolygonCloud\"");
    }

    @Test
    @DisplayName("does not mark a plain polygon with a cloud intent")
    void polygonHasNoCloudIntent() {
        String xfdf = exportOne(Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"polygon\",\"points\":[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]}");

        assertThat(xfdf).doesNotContain("PolygonCloud");
    }

    @Test
    @DisplayName("gives an arrow a head attribute a line does not have")
    void arrowHasHead() {
        String arrow = exportOne(Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"arrow\",\"x1\":0,\"y1\":0,\"x2\":50,\"y2\":50}");
        String line = exportOne(Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"line\",\"x1\":0,\"y1\":0,\"x2\":50,\"y2\":50}");

        assertThat(arrow).contains("head=\"OpenArrow\"");
        assertThat(line).doesNotContain("head=");
    }

    @ParameterizedTest(name = "{0} exports as <{1}> with quadpoints")
    @CsvSource({
        "UNDERLINE, underline",
        "STRIKEOUT, strikeout",
        "SQUIGGLY,  squiggly",
        "HIGHLIGHT, highlight",
    })
    @DisplayName("text-markup types use their own element and carry quadpoints")
    void textMarkupTypes(Annotation.AnnotationType type, String element) {
        String xfdf = exportOne(type,
            "{\"tool\":\"underline\",\"x\":10,\"y\":20,\"width\":100,\"height\":14}");

        assertThat(xfdf)
            .contains("<" + element + " ")
            .contains("</" + element + ">")
            .contains("<quadpoints>");
    }

    @Test
    @DisplayName("preserves an ellipse's aspect ratio rather than forcing a circle")
    void ellipseKeepsAspectRatio() {
        String xfdf = exportOne(Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"ellipse\",\"x\":0,\"y\":0,\"width\":200,\"height\":50}");

        // XFDF has no ellipse element; a circle bounded by a non-square
        // rect is how readers represent one.
        assertThat(xfdf).contains("rect=\"0.00,0.00,200.00,50.00\"");
    }

    @Test
    @DisplayName("converts page numbers to XFDF's zero-based indexing")
    void pageIsZeroBased() {
        Annotation onPageThree = annotation(
            Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"rect\",\"x\":0,\"y\":0,\"width\":10,\"height\":10}", 3, "");

        assertThat(service.toXfdf(List.of(onPageThree), "d.pdf"))
            .contains("page=\"2\"");
    }

    @Test
    @DisplayName("escapes XML metacharacters in comments and text")
    void escapesXml() {
        Annotation annotation = annotation(
            Annotation.AnnotationType.COMMENT,
            "{\"tool\":\"text\",\"x\":0,\"y\":0}", 1, "a < b & c > d");

        String xfdf = service.toXfdf(List.of(annotation), "d.pdf");

        assertThat(xfdf).contains("a &lt; b &amp; c &gt; d");
    }

    @Test
    @DisplayName("skips a malformed annotation instead of failing the whole export")
    void skipsMalformed() {
        List<Annotation> annotations = List.of(
            annotation(Annotation.AnnotationType.MARKUP, "not json at all"),
            annotation(Annotation.AnnotationType.MARKUP,
                "{\"tool\":\"rect\",\"x\":0,\"y\":0,\"width\":10,\"height\":10}"));

        String xfdf = service.toXfdf(annotations, "d.pdf");

        assertThat(xfdf).contains("<square ").endsWith("</xfdf>");
    }

    @Test
    @DisplayName("names the author on the exported annotation")
    void carriesAuthor() {
        assertThat(exportOne(Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"rect\",\"x\":0,\"y\":0,\"width\":1,\"height\":1}"))
            .contains("author=\"engineer1\"");
    }

    // ── The annotation types that are not shapes ──────────────────────────

    @ParameterizedTest(name = "{0} exports as <{1}>")
    @CsvSource({
        "COMMENT,   text",
        "HIGHLIGHT, highlight",
        "UNDERLINE, underline",
        "STRIKEOUT, strikeout",
        "SQUIGGLY,  squiggly",
        "STAMP,     stamp",
        "DIMENSION, line",
    })
    @DisplayName("each annotation type exports as its own XFDF element")
    void eachTypeGetsItsOwnElement(String type, String element) {
        // The arms of this switch are the round trip: an annotation exported
        // under the wrong element imports back as a different kind of markup,
        // which is how ARROW and CLOUD once became sticky notes.
        String xfdf = exportOne(Annotation.AnnotationType.valueOf(type),
            """
            {"tool":"rect","x":10,"y":20,"width":100,"height":40,
             "x1":10,"y1":20,"x2":110,"y2":60,"points":[[10,20],[110,60]]}""");

        assertThat(xfdf).contains("<" + element);
    }

    @Test
    @DisplayName("every annotation type this platform has is named in the exporter")
    void everyTypeIsHandled() {
        // The switch has no default arm on purpose, so this is really a
        // statement about the build: a new type stops the exporter compiling
        // until somebody decides what it becomes. That is the decision that
        // was missed for ARROW and CLOUD, and a default arm is what hid it —
        // they exported as sticky notes and nothing said so.
        for (Annotation.AnnotationType type : Annotation.AnnotationType.values()) {
            assertThat(exportOne(type,
                """
                {"tool":"rect","x":10,"y":20,"width":100,"height":40,
                 "x1":10,"y1":20,"x2":110,"y2":60,"points":[[10,20],[110,60]]}"""))
                .as("%s must export as something", type)
                .doesNotContain("<annots></annots>");
        }
    }

    @Test
    @DisplayName("an unknown tool falls back to free text rather than vanishing")
    void unknownToolBecomesFreeText() {
        String xfdf = exportOne(Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"sketch\",\"x\":10,\"y\":20,\"width\":50,\"height\":10}");

        assertThat(xfdf).contains("<freetext");
    }

    @Test
    @DisplayName("a text box exports as free text without a callout line")
    void textBoxHasNoCallout() {
        String xfdf = exportOne(Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"text\",\"x\":10,\"y\":20,\"width\":50,\"height\":10,\"text\":\"Check\"}");

        assertThat(xfdf).contains("<freetext").doesNotContain("IT=\"FreeTextCallout\"");
    }

    @Test
    @DisplayName("a callout exports with the intent that keeps its tail")
    void calloutKeepsItsTail() {
        // Without the intent a callout imports back as a plain text box and
        // its leader line — the thing pointing at what the note is about —
        // is gone.
        String xfdf = exportOne(Annotation.AnnotationType.MARKUP,
            """
            {"tool":"callout","x":10,"y":20,"width":50,"height":10,
             "x2":90,"y2":60,"text":"Check this level"}""");

        assertThat(xfdf).contains("FreeTextCallout");
    }

    @Test
    @DisplayName("a sticky note drawn as a shape still exports as a note")
    void noteToolExportsAsNote() {
        String xfdf = exportOne(Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"note\",\"x\":10,\"y\":20}");

        assertThat(xfdf).contains("<text");
    }

    @Test
    @DisplayName("a highlight drawn as a shape still exports as a highlight")
    void highlightToolExportsAsHighlight() {
        String xfdf = exportOne(Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"highlight\",\"x\":10,\"y\":20,\"width\":80,\"height\":12}");

        assertThat(xfdf).contains("<highlight");
    }

    // ── Shapes whose geometry is a list of points ─────────────────────────

    @ParameterizedTest(name = "{0} with no points still exports")
    @CsvSource({"freehand, ink", "polygon, polygon", "polyline, polyline"})
    @DisplayName("a points-based shape with no points exports rather than throwing")
    void pointlessShapesStillExport(String tool, String element) {
        // Markup can be saved mid-draw, and a half-drawn shape reaching the
        // exporter must not take the whole document's export down with it.
        String xfdf = exportOne(Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"" + tool + "\",\"x\":10,\"y\":20}");

        assertThat(xfdf).contains("<" + element);
    }

    @ParameterizedTest(name = "{0} with no points has a readable rect")
    @CsvSource({"polygon", "polyline", "cloud"})
    @DisplayName("a shape with no points gets a rect a reader can parse")
    void pointlessShapesHaveAReadableRect(String tool) {
        // The bounding box is folded out of the points, and with none the
        // running minimum was still Double.MAX_VALUE and the maximum still
        // its negative — so the attribute came out as a three-hundred-digit
        // number. That is not a large rectangle, it is an unparseable file:
        // one half-drawn shape and the whole export stops loading.
        String xfdf = exportOne(Annotation.AnnotationType.MARKUP,
            "{\"tool\":\"" + tool + "\",\"x\":10,\"y\":20}");

        assertThat(xfdf)
            .as("a rect attribute must hold coordinates, not Double.MAX_VALUE")
            .doesNotContain("17976931348623157");
    }

    @ParameterizedTest(name = "{0} carries its points")
    @CsvSource({"freehand, ink", "polygon, polygon", "polyline, polyline"})
    @DisplayName("a points-based shape carries the points it was drawn with")
    void pointsAreCarried(String tool, String element) {
        String xfdf = exportOne(Annotation.AnnotationType.MARKUP,
            """
            {"tool":"%s","points":[{"x":10,"y":20},{"x":30,"y":40},{"x":50,"y":60}]}"""
                .formatted(tool));

        assertThat(xfdf).contains("<" + element).contains("30");
    }

    @Test
    @DisplayName("a dimension carries its measurement text")
    void dimensionCarriesItsMeasurement() {
        // The number is the whole point of a dimension; exporting the line
        // without it leaves a reviewer measuring the drawing by hand.
        String xfdf = exportOne(Annotation.AnnotationType.DIMENSION,
            """
            {"tool":"dimension","x1":10,"y1":20,"x2":110,"y2":20,
             "measurement":"12.40 m"}""");

        assertThat(xfdf).contains("12.40 m");
    }

    @Test
    @DisplayName("a dimension with a comment as well carries both")
    void dimensionCarriesBoth() {
        String xfdf = service.toXfdf(List.of(annotation(
            Annotation.AnnotationType.DIMENSION,
            """
            {"tool":"dimension","x1":10,"y1":20,"x2":110,"y2":20,
             "measurement":"12.40 m"}""",
            1, "Check against survey")), "drawing.pdf");

        assertThat(xfdf).contains("12.40 m").contains("Check against survey");
    }

    @Test
    @DisplayName("a dimension with no measurement falls back to the comment alone")
    void dimensionWithoutMeasurement() {
        String xfdf = service.toXfdf(List.of(annotation(
            Annotation.AnnotationType.DIMENSION,
            "{\"tool\":\"dimension\",\"x1\":10,\"y1\":20,\"x2\":110,\"y2\":20}",
            1, "Span")), "drawing.pdf");

        assertThat(xfdf).contains("Span");
    }

    @Test
    @DisplayName("nothing to escape is left exactly as it was")
    void plainTextIsUnchanged() {
        // The branch the escaping check takes for almost every real comment.
        String xfdf = service.toXfdf(List.of(annotation(
            Annotation.AnnotationType.COMMENT, "{\"tool\":\"note\",\"x\":1,\"y\":2}",
            1, "Issued for construction")), "drawing.pdf");

        assertThat(xfdf).contains("Issued for construction").doesNotContain("&amp;amp;");
    }
}
