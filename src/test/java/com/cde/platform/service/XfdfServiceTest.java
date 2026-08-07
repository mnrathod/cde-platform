package com.cde.platform.service;

import com.cde.platform.model.Annotation;
import com.cde.platform.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for XFDF interchange.
 *
 * XFDF is how markup moves between this product and Bluebeam/Acrobat/Procore,
 * so the assertions target the specific element and attribute names those
 * readers rely on. Round-trip tests matter most: an export that silently
 * degrades a shape looks fine until someone opens it elsewhere.
 */
class XfdfServiceTest {

    private XfdfService service;

    @BeforeEach
    void setUp() {
        service = new XfdfService();
    }

    private Annotation annotation(Annotation.AnnotationType type, String shapeData) {
        return annotation(type, shapeData, 1, "");
    }

    private Annotation annotation(Annotation.AnnotationType type, String shapeData,
                                  int pageNumber, String comment) {
        User author = User.builder().username("engineer1").build();
        Annotation annotation = new Annotation();
        annotation.setType(type);
        annotation.setShapeData(shapeData);
        annotation.setComment(comment);
        annotation.setPageNumber(pageNumber);
        annotation.setAuthor(author);
        annotation.setCreatedAt(LocalDateTime.of(2026, 8, 7, 12, 0, 0));
        return annotation;
    }

    private String exportOne(Annotation.AnnotationType type, String shapeData) {
        return service.toXfdf(List.of(annotation(type, shapeData)), "drawing.pdf");
    }

    // ── Export ───────────────────────────────────────────────────────
    @Nested
    @DisplayName("export")
    class Export {

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

    // ── Import ───────────────────────────────────────────────────────
    @Nested
    @DisplayName("import")
    class Import {

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
    }

    // ── Round trip ───────────────────────────────────────────────────
    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        /**
         * Every tool the frontend can draw must come back as the same tool.
         * Four of these previously degraded silently — an arrow returned as
         * a line, an ellipse as a circle, a dimension as a line and a note
         * as a text box — which only shows up when the file is reopened.
         */
        @ParameterizedTest(name = "{1} survives export then import")
        @CsvSource({
            "MARKUP,    line",
            "ARROW,     arrow",
            "MARKUP,    rect",
            "MARKUP,    circle",
            "MARKUP,    ellipse",
            "MARKUP,    freehand",
            "CLOUD,     cloud",
            "MARKUP,    polygon",
            "MARKUP,    polyline",
            "MARKUP,    text",
            "MARKUP,    callout",
            "COMMENT,   note",
            "HIGHLIGHT, highlight",
            "UNDERLINE, underline",
            "STRIKEOUT, strikeout",
            "SQUIGGLY,  squiggly",
            "STAMP,     stamp",
            "DIMENSION, dimension",
        })
        @DisplayName("a shape exported and re-imported keeps its tool")
        void toolSurvives(Annotation.AnnotationType type, String tool) throws Exception {
            String shapeData = """
                {"tool":"%s","x":10,"y":20,"width":100,"height":50,
                 "x1":0,"y1":0,"x2":80,"y2":60,"cx":50,"cy":50,"r":25,
                 "points":[{"x":1,"y":2},{"x":3,"y":4},{"x":5,"y":6}],
                 "text":"content","color":"#FF0000","strokeWidth":2}
                """.formatted(tool);

            String xfdf = service.toXfdf(List.of(annotation(type, shapeData)), "d.pdf");
            var imported = service.fromXfdf(xfdf.getBytes());

            assertThat(imported).hasSize(1);
            assertThat(imported.get(0).shapeData()).contains("\"tool\":\"" + tool + "\"");
        }

        @Test
        @DisplayName("a circle stays a circle rather than becoming an ellipse")
        void circleIsNotWidenedToEllipse() throws Exception {
            String xfdf = service.toXfdf(List.of(annotation(Annotation.AnnotationType.MARKUP,
                "{\"tool\":\"circle\",\"cx\":50,\"cy\":50,\"r\":25}")), "d.pdf");

            assertThat(service.fromXfdf(xfdf.getBytes()).get(0).shapeData())
                .contains("\"tool\":\"circle\"");
        }

        @Test
        @DisplayName("a dimension keeps its measurement text")
        void dimensionKeepsMeasurement() throws Exception {
            Annotation dimension = annotation(Annotation.AnnotationType.DIMENSION,
                "{\"tool\":\"dimension\",\"x1\":0,\"y1\":0,\"x2\":100,\"y2\":0," +
                "\"measurement\":\"12.5 m\"}", 1, "");

            String xfdf = service.toXfdf(List.of(dimension), "d.pdf");
            var imported = service.fromXfdf(xfdf.getBytes());

            assertThat(imported.get(0).type()).isEqualTo(Annotation.AnnotationType.DIMENSION);
            assertThat(imported.get(0).shapeData()).contains("12.5 m");
        }

        @ParameterizedTest(name = "{0} survives export then import")
        @ValueSource(strings = {"UNDERLINE", "STRIKEOUT", "SQUIGGLY", "HIGHLIGHT"})
        @DisplayName("a text-markup type exported and re-imported keeps its type")
        void textMarkupTypeSurvives(String typeName) throws Exception {
            var type = Annotation.AnnotationType.valueOf(typeName);
            String xfdf = service.toXfdf(List.of(annotation(type,
                "{\"tool\":\"underline\",\"x\":10,\"y\":20,\"width\":100,\"height\":14}")), "d.pdf");

            var imported = service.fromXfdf(xfdf.getBytes());

            assertThat(imported).hasSize(1);
            assertThat(imported.get(0).type()).isEqualTo(type);
        }

        @Test
        @DisplayName("page numbers survive the zero-based conversion in both directions")
        void pageNumberSurvives() throws Exception {
            Annotation onPageSeven = annotation(Annotation.AnnotationType.MARKUP,
                "{\"tool\":\"rect\",\"x\":0,\"y\":0,\"width\":10,\"height\":10}", 7, "");

            String xfdf = service.toXfdf(List.of(onPageSeven), "d.pdf");
            var imported = service.fromXfdf(xfdf.getBytes());

            assertThat(imported.get(0).pageNumber()).isEqualTo(7);
        }
    }
}
