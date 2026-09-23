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
}
