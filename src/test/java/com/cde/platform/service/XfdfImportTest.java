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
}
