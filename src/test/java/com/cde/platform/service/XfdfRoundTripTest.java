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
 * Markup that leaves as XFDF and comes back the same.
 *
 * <p>Export and import can each look correct on their own while losing
 * something between them, and the loss is invisible until a file is
 * reopened. Four tools degraded silently this way — an arrow returned as a
 * line, an ellipse as a circle, a dimension as a line and a note as a text
 * box — which is why every tool the frontend can draw is checked here rather
 * than a representative few.
 */
class XfdfRoundTripTest {

    private XfdfService service;

    @BeforeEach
    void setUp() {
        service = new XfdfService();
    }


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
