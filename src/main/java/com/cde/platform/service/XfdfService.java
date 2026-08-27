package com.cde.platform.service;

import com.cde.platform.model.Annotation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Converts CDE annotations to/from XFDF (XML Forms Data Format).
 * XFDF is the Adobe/PDF standard used by Bluebeam, Acrobat, Procore etc.
 * Spec: https://opensource.adobe.com/dc-acrobat-sdk-docs/standards/pdfstandards/pdf/PDF32000_2008.pdf
 */
@Service
public class XfdfService {

    private static final Logger log = LoggerFactory.getLogger(XfdfService.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /** Tolerance for treating a bounding box as square (circle vs ellipse). */
    private static final double SHAPE_EPSILON = 0.01;
    private static final DateTimeFormatter XFDF_DATE = DateTimeFormatter.ofPattern("'D:'yyyyMMddHHmmss");

    // ── Export: CDE annotations → XFDF ────────────────────────────
    public String toXfdf(List<Annotation> annotations, String filename) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<xfdf xmlns=\"http://ns.adobe.com/xfdf/\" xml:space=\"preserve\">\n");
        sb.append("  <f href=\"").append(escapeXml(filename)).append("\"/>\n");
        sb.append("  <annots>\n");

        for (Annotation ann : annotations) {
            try {
                sb.append(annotationToXfdf(ann));
            } catch (Exception e) {
                // Skip malformed annotations
            }
        }

        sb.append("  </annots>\n");
        sb.append("</xfdf>");
        return sb.toString();
    }

    private String annotationToXfdf(Annotation ann) throws Exception {
        JsonNode data = mapper.readTree(ann.getShapeData());
        String author = ann.getAuthor() != null ? ann.getAuthor().getUsername() : "Unknown";
        String date   = ann.getCreatedAt() != null
            ? ann.getCreatedAt().format(XFDF_DATE) : XFDF_DATE.format(LocalDateTime.now());
        int page      = ann.getPageNumber() != null ? ann.getPageNumber() - 1 : 0;
        String color  = data.path("color").asText("#FF0000");
        String width  = data.path("strokeWidth").asText("2");
        String comment = ann.getComment() != null ? escapeXml(ann.getComment()) : "";

        return switch (ann.getType()) {
            // ARROW and CLOUD carry their shape in the `tool` field exactly
            // as MARKUP does. They previously fell through to the default
            // branch and were exported as sticky notes — silently turning
            // the two commonest construction markups into text.
            case MARKUP, ARROW, CLOUD ->
                shapeToXfdf(data, author, date, page, color, width, comment);
            case COMMENT -> textAnnot(data, author, date, page, color, comment);
            case HIGHLIGHT -> highlightAnnot(data, author, date, page, comment);
            case UNDERLINE -> textMarkupAnnot("underline", data, author, date, page, color, comment);
            case STRIKEOUT -> textMarkupAnnot("strikeout", data, author, date, page, color, comment);
            case SQUIGGLY  -> textMarkupAnnot("squiggly",  data, author, date, page, color, comment);
            case STAMP -> stampAnnot(data, author, date, page, comment);
            case DIMENSION -> dimensionAnnot(data, author, date, page, color, width, comment);
            default -> textAnnot(data, author, date, page, color, comment);
        };
    }

    private String shapeToXfdf(JsonNode d, String author, String date, int page,
                                 String color, String width, String comment) {
        // The frontend's ShapeData JSON key is "tool" (see viewer-state.service.ts),
        // not "shape" — read the field that's actually present.
        String shape = d.path("tool").asText("line");
        return switch (shape) {
            case "line"      -> lineAnnot(d, author, date, page, color, width, comment, false);
            case "arrow"     -> lineAnnot(d, author, date, page, color, width, comment, true);
            case "rect"      -> squareAnnot(d, author, date, page, color, width, comment);
            case "circle"    -> circleAnnot(d, author, date, page, color, width, comment);
            case "ellipse"   -> ellipseAnnot(d, author, date, page, color, width, comment);
            case "freehand"  -> inkAnnot(d, author, date, page, color, width, comment);
            case "cloud"     -> polygonAnnot(d, author, date, page, color, width, comment, true);
            case "polygon"   -> polygonAnnot(d, author, date, page, color, width, comment, false);
            case "polyline"  -> polylineAnnot(d, author, date, page, color, width, comment);
            case "text"      -> freetextAnnot(d, author, date, page, color, comment, false);
            case "callout"   -> freetextAnnot(d, author, date, page, color, comment, true);
            case "note"      -> textAnnot(d, author, date, page, color, comment);
            case "highlight" -> highlightAnnot(d, author, date, page, comment);
            default          -> freetextAnnot(d, author, date, page, color, comment, false);
        };
    }

    private String lineAnnot(JsonNode d, String author, String date, int page,
                              String color, String width, String comment, boolean arrow) {
        double x1 = d.path("x1").asDouble(0), y1 = d.path("y1").asDouble(0);
        double x2 = d.path("x2").asDouble(100), y2 = d.path("y2").asDouble(100);
        String rect = rect(Math.min(x1,x2), Math.min(y1,y2), Math.max(x1,x2), Math.max(y1,y2));
        String head = arrow ? " head=\"OpenArrow\" tail=\"None\"" : "";
        return String.format(
            "    <line page=\"%d\" rect=\"%s\" color=\"%s\" width=\"%s\"\n" +
            "          start=\"%.2f,%.2f\" end=\"%.2f,%.2f\"%s\n" +
            "          author=\"%s\" date=\"%s\">\n" +
            "      <contents>%s</contents>\n" +
            "    </line>\n",
            page, rect, color, width, x1, y1, x2, y2, head, author, date, comment);
    }

    private String squareAnnot(JsonNode d, String author, String date, int page,
                                String color, String width, String comment) {
        double x = d.path("x").asDouble(0), y = d.path("y").asDouble(0);
        double w = d.path("width").asDouble(100), h = d.path("height").asDouble(100);
        return String.format(
            "    <square page=\"%d\" rect=\"%s\" color=\"%s\" width=\"%s\"\n" +
            "            author=\"%s\" date=\"%s\">\n" +
            "      <contents>%s</contents>\n" +
            "    </square>\n",
            page, rect(x, y, x+w, y+h), color, width, author, date, comment);
    }

    private String circleAnnot(JsonNode d, String author, String date, int page,
                                String color, String width, String comment) {
        double cx = d.path("cx").asDouble(0), cy = d.path("cy").asDouble(0);
        double r  = d.path("r").asDouble(50);
        return String.format(
            "    <circle page=\"%d\" rect=\"%s\" color=\"%s\" width=\"%s\"\n" +
            "            author=\"%s\" date=\"%s\">\n" +
            "      <contents>%s</contents>\n" +
            "    </circle>\n",
            page, rect(cx-r, cy-r, cx+r, cy+r), color, width, author, date, comment);
    }

    private String ellipseAnnot(JsonNode d, String author, String date, int page,
                                 String color, String width, String comment) {
        // XFDF has no dedicated ellipse element — Acrobat/Bluebeam render a
        // Circle annotation bounded by a non-square rect as an oval, so a
        // <circle> whose rect keeps the drawn aspect ratio is the correct
        // interchange representation (unlike circleAnnot, which is built
        // from a cx/cy/r scalar radius and always yields a perfect circle).
        double x = d.path("x").asDouble(0), y = d.path("y").asDouble(0);
        double w = d.path("width").asDouble(100), h = d.path("height").asDouble(60);
        return String.format(
            "    <circle page=\"%d\" rect=\"%s\" color=\"%s\" width=\"%s\"\n" +
            "            author=\"%s\" date=\"%s\">\n" +
            "      <contents>%s</contents>\n" +
            "    </circle>\n",
            page, rect(x, y, x+w, y+h), color, width, author, date, comment);
    }

    private String inkAnnot(JsonNode d, String author, String date, int page,
                             String color, String width, String comment) {
        JsonNode pts = d.path("points");
        StringBuilder gesture = new StringBuilder();
        if (pts.isArray()) {
            for (JsonNode pt : pts) {
                if (gesture.length() > 0) gesture.append(";");
                gesture.append(String.format("%.2f,%.2f", pt.path("x").asDouble(), pt.path("y").asDouble()));
            }
        }
        double minX = d.path("minX").asDouble(0), minY = d.path("minY").asDouble(0);
        double maxX = d.path("maxX").asDouble(200), maxY = d.path("maxY").asDouble(200);
        return String.format(
            "    <ink page=\"%d\" rect=\"%s\" color=\"%s\" width=\"%s\"\n" +
            "         author=\"%s\" date=\"%s\">\n" +
            "      <inklist><gesture>%s</gesture></inklist>\n" +
            "      <contents>%s</contents>\n" +
            "    </ink>\n",
            page, rect(minX, minY, maxX, maxY), color, width, author, date, gesture, comment);
    }

    private String polygonAnnot(JsonNode d, String author, String date, int page,
                                 String color, String width, String comment, boolean cloud) {
        JsonNode pts = d.path("points");
        StringBuilder verts = new StringBuilder();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        if (pts.isArray()) {
            for (JsonNode pt : pts) {
                if (verts.length() > 0) verts.append(";");
                double px = pt.path("x").asDouble(), py = pt.path("y").asDouble();
                verts.append(String.format("%.2f,%.2f", px, py));
                minX = Math.min(minX, px); minY = Math.min(minY, py);
                maxX = Math.max(maxX, px); maxY = Math.max(maxY, py);
            }
        }
        String intent = cloud ? " IT=\"PolygonCloud\"" : "";
        return String.format(
            "    <polygon page=\"%d\" rect=\"%s\" color=\"%s\" width=\"%s\"%s\n" +
            "             vertices=\"%s\" author=\"%s\" date=\"%s\">\n" +
            "      <contents>%s</contents>\n" +
            "    </polygon>\n",
            page, rect(minX, minY, maxX, maxY), color, width, intent,
            verts, author, date, comment);
    }

    private String polylineAnnot(JsonNode d, String author, String date, int page,
                                  String color, String width, String comment) {
        JsonNode pts = d.path("points");
        StringBuilder verts = new StringBuilder();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        if (pts.isArray()) {
            for (JsonNode pt : pts) {
                if (verts.length() > 0) verts.append(";");
                double px = pt.path("x").asDouble(), py = pt.path("y").asDouble();
                verts.append(String.format("%.2f,%.2f", px, py));
                minX = Math.min(minX, px); minY = Math.min(minY, py);
                maxX = Math.max(maxX, px); maxY = Math.max(maxY, py);
            }
        }
        return String.format(
            "    <polyline page=\"%d\" rect=\"%s\" color=\"%s\" width=\"%s\"\n" +
            "              vertices=\"%s\" author=\"%s\" date=\"%s\">\n" +
            "      <contents>%s</contents>\n" +
            "    </polyline>\n",
            page, rect(minX, minY, maxX, maxY), color, width, verts, author, date, comment);
    }

    /**
     * @param callout writes IT="FreeTextCallout", the marker that lets a
     *   reader — and our own import — tell a callout from plain free text.
     *   Without it both come back as the same tool.
     */
    private String freetextAnnot(JsonNode d, String author, String date, int page,
                                  String color, String comment, boolean callout) {
        double x = d.path("x").asDouble(0), y = d.path("y").asDouble(0);
        // A callout's box is where its leader points, which the shape
        // records as x2/y2; plain text has no leader.
        double x2 = callout ? d.path("x2").asDouble(x + 200) : x + 200;
        double y2 = callout ? d.path("y2").asDouble(y + 30)  : y + 30;
        String text   = escapeXml(d.path("text").asText(comment));
        String intent = callout ? " IT=\"FreeTextCallout\"" : "";
        return String.format(
            "    <freetext page=\"%d\" rect=\"%s\" color=\"%s\"%s\n" +
            "              author=\"%s\" date=\"%s\">\n" +
            "      <contents>%s</contents>\n" +
            "      <defaultappearance>/Helvetica 11 Tf 0 0 0 rg</defaultappearance>\n" +
            "    </freetext>\n",
            page, rect(x, y, x2, y2), color, intent, author, date, text);
    }

    private String textAnnot(JsonNode d, String author, String date, int page,
                              String color, String comment) {
        double x = d.path("x").asDouble(0), y = d.path("y").asDouble(0);
        return String.format(
            "    <text page=\"%d\" rect=\"%s\" color=\"%s\" icon=\"Comment\"\n" +
            "          author=\"%s\" date=\"%s\">\n" +
            "      <contents>%s</contents>\n" +
            "    </text>\n",
            page, rect(x, y, x+20, y+20), color, author, date, comment);
    }

    private String highlightAnnot(JsonNode d, String author, String date, int page,
                                   String comment) {
        double x = d.path("x").asDouble(0), y = d.path("y").asDouble(0);
        double w = d.path("width").asDouble(100), h = d.path("height").asDouble(20);
        String qp = String.format("%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
            x, y+h, x+w, y+h, x, y, x+w, y);
        return String.format(
            "    <highlight page=\"%d\" rect=\"%s\" color=\"#FFFF00\"\n" +
            "               author=\"%s\" date=\"%s\">\n" +
            "      <quadpoints>%s</quadpoints>\n" +
            "      <contents>%s</contents>\n" +
            "    </highlight>\n",
            page, rect(x, y, x+w, y+h), author, date, qp, comment);
    }

    // Underline / Strikeout / Squiggly are all PDF "text markup" annotation
    // subtypes — same quadpoints-over-a-region structure as Highlight, just
    // rendered differently by the reader. `tag` must be one of those three.
    private String textMarkupAnnot(String tag, JsonNode d, String author, String date, int page,
                                    String color, String comment) {
        double x = d.path("x").asDouble(0), y = d.path("y").asDouble(0);
        double w = d.path("width").asDouble(100), h = d.path("height").asDouble(14);
        String qp = String.format("%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
            x, y+h, x+w, y+h, x, y, x+w, y);
        return String.format(
            "    <%s page=\"%d\" rect=\"%s\" color=\"%s\"\n" +
            "               author=\"%s\" date=\"%s\">\n" +
            "      <quadpoints>%s</quadpoints>\n" +
            "      <contents>%s</contents>\n" +
            "    </%s>\n",
            tag, page, rect(x, y, x+w, y+h), color, author, date, qp, comment, tag);
    }

    private String stampAnnot(JsonNode d, String author, String date, int page,
                               String comment) {
        double x = d.path("x").asDouble(0), y = d.path("y").asDouble(0);
        String name = escapeXml(d.path("text").asText("Approved"));
        return String.format(
            "    <stamp page=\"%d\" rect=\"%s\" name=\"%s\"\n" +
            "           author=\"%s\" date=\"%s\">\n" +
            "      <contents>%s</contents>\n" +
            "    </stamp>\n",
            page, rect(x, y, x+100, y+40), name, author, date, comment);
    }

    private String dimensionAnnot(JsonNode d, String author, String date, int page,
                                   String color, String width, String comment) {
        // Dimension rendered as line with measurement text
        double x1 = d.path("x1").asDouble(0), y1 = d.path("y1").asDouble(0);
        double x2 = d.path("x2").asDouble(100), y2 = d.path("y2").asDouble(100);
        String measurement = d.path("measurement").asText("");
        String contents = measurement.isEmpty() ? comment : measurement + (comment.isEmpty() ? "" : " - " + comment);
        String rect = rect(Math.min(x1,x2), Math.min(y1,y2), Math.max(x1,x2), Math.max(y1,y2));
        return String.format(
            "    <line page=\"%d\" rect=\"%s\" color=\"%s\" width=\"%s\"\n" +
            "          start=\"%.2f,%.2f\" end=\"%.2f,%.2f\"\n" +
            "          IT=\"LineDimension\" author=\"%s\" date=\"%s\">\n" +
            "      <contents>%s</contents>\n" +
            "    </line>\n",
            page, rect, color, width, x1, y1, x2, y2, author, date, escapeXml(contents));
    }

    // ── Import: XFDF → CDE annotations ───────────────────────────
    /**
     * Parse an XFDF file and convert each annotation to an ImportedAnnotation record.
     * The caller is responsible for persisting them via AnnotationRepository.
     *
     * Supports: line, square, circle, ink, text, highlight, freetext,
     *           polygon, stamp, arrow (line with head attribute)
     */
    public List<ImportedAnnotation> fromXfdf(byte[] xfdfBytes) throws Exception {
        List<ImportedAnnotation> result = new ArrayList<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        // Prevent XXE attacks
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xfdfBytes));
        doc.getDocumentElement().normalize();

        // Find <annots> element
        NodeList annotsList = doc.getElementsByTagName("annots");
        if (annotsList.getLength() == 0) return result;
        Element annots = (Element) annotsList.item(0);

        // Process each child element as an annotation
        NodeList children = annots.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element el)) continue;

            try {
                ImportedAnnotation ann = parseXfdfElement(el);
                if (ann != null) result.add(ann);
            } catch (Exception e) {
                // Skip malformed annotations, continue processing rest.
                // The tag name is markup vocabulary and safe to log; the
                // exception message can quote attribute values, which are user
                // content, so only its type is recorded (§5.7).
                log.warn("Skipped malformed XFDF element {} ({})",
                    el.getTagName(), e.getClass().getSimpleName());
            }
        }
        return result;
    }

    private ImportedAnnotation parseXfdfElement(Element el) {
        String tag    = el.getTagName().toLowerCase();
        String author = attr(el, "author");
        String date   = attr(el, "date");
        String color  = attr(el, "color", "#FF0000");
        int    width  = parseInt(attr(el, "width"), 2);
        int    page   = parseInt(attr(el, "page"), 0) + 1;  // XFDF is 0-based
        String contents = getContents(el);

        // Determine annotation type and build shapeData JSON
        ObjectNode shape = mapper.createObjectNode();
        shape.put("color", color);
        shape.put("strokeWidth", width);
        shape.put("opacity", 0.15);
        shape.put("pageNumber", page);
        shape.put("author", author);

        Annotation.AnnotationType type;

        switch (tag) {
            case "line" -> {
                String[] start = attr(el,"start","0,0").split(",");
                String[] end   = attr(el,"end","100,100").split(",");
                // A measurement line is marked with IT="LineDimension"; an
                // arrow by the presence of a head. The head test used to
                // compare the literal "head" against the attribute's VALUE
                // ("OpenArrow"), so it never matched and every arrow came
                // back as a plain line.
                boolean isDimension = "LineDimension".equals(attr(el, "IT"));
                boolean hasHead     = !attr(el, "head").isBlank();

                shape.put("tool", isDimension ? "dimension" : hasHead ? "arrow" : "line");
                shape.put("x1", parseDouble(start[0]));
                shape.put("y1", parseDouble(start.length>1?start[1]:"0"));
                shape.put("x2", parseDouble(end[0]));
                shape.put("y2", parseDouble(end.length>1?end[1]:"0"));
                if (isDimension && !contents.isBlank()) shape.put("measurement", contents);

                type = isDimension ? Annotation.AnnotationType.DIMENSION
                     : hasHead     ? Annotation.AnnotationType.ARROW
                                   : Annotation.AnnotationType.MARKUP;
            }
            case "square" -> {
                double[] r = parseRect(attr(el,"rect"));
                shape.put("tool","rect");
                shape.put("x",r[0]); shape.put("y",r[1]);
                shape.put("width",r[2]-r[0]); shape.put("height",r[3]-r[1]);
                type = Annotation.AnnotationType.MARKUP;
            }
            case "circle" -> {
                double[] r = parseRect(attr(el,"rect"));
                double boxWidth = r[2]-r[0], boxHeight = r[3]-r[1];
                // XFDF has no ellipse element — an oval is a Circle bounded
                // by a non-square rect, which is exactly how ellipseAnnot
                // writes one. Reading the rect back is therefore what
                // distinguishes the two on import.
                if (Math.abs(boxWidth - boxHeight) > SHAPE_EPSILON) {
                    shape.put("tool","ellipse");
                    shape.put("x",r[0]); shape.put("y",r[1]);
                    shape.put("width",boxWidth); shape.put("height",boxHeight);
                } else {
                    shape.put("tool","circle");
                    shape.put("cx",(r[0]+r[2])/2); shape.put("cy",(r[1]+r[3])/2);
                    shape.put("r", Math.max(boxWidth, boxHeight)/2);
                }
                type = Annotation.AnnotationType.MARKUP;
            }
            case "ink" -> {
                // Parse <inklist><gesture>x,y;x,y;...</gesture></inklist>
                var pts = mapper.createArrayNode();
                NodeList gestures = el.getElementsByTagName("gesture");
                if (gestures.getLength() > 0) {
                    String[] pairs = gestures.item(0).getTextContent().split(";");
                    for (String pair : pairs) {
                        String[] xy = pair.trim().split(",");
                        if (xy.length >= 2) {
                            var pt = mapper.createObjectNode();
                            pt.put("x", parseDouble(xy[0]));
                            pt.put("y", parseDouble(xy[1]));
                            pts.add(pt);
                        }
                    }
                }
                shape.put("tool","freehand");
                shape.set("points", pts);
                type = Annotation.AnnotationType.MARKUP;
            }
            case "highlight" -> {
                double[] r = parseRect(attr(el,"rect"));
                shape.put("tool","highlight");
                shape.put("x",r[0]); shape.put("y",r[1]);
                shape.put("width",r[2]-r[0]); shape.put("height",r[3]-r[1]);
                shape.put("color","#FFFF00");
                type = Annotation.AnnotationType.HIGHLIGHT;
            }
            case "underline", "strikeout", "squiggly" -> {
                double[] r = parseRect(attr(el,"rect"));
                shape.put("tool", tag);
                shape.put("x",r[0]); shape.put("y",r[1]);
                shape.put("width",r[2]-r[0]); shape.put("height",r[3]-r[1]);
                type = switch (tag) {
                    case "underline" -> Annotation.AnnotationType.UNDERLINE;
                    case "strikeout" -> Annotation.AnnotationType.STRIKEOUT;
                    default          -> Annotation.AnnotationType.SQUIGGLY;
                };
            }
            case "text" -> {
                // A PDF /Text annotation is a sticky note, not a text box —
                // the text box is /FreeText, handled below.
                double[] r = parseRect(attr(el,"rect"));
                shape.put("tool","note");
                shape.put("x",r[0]); shape.put("y",r[1]);
                shape.put("text", contents);
                type = Annotation.AnnotationType.COMMENT;
            }
            case "freetext" -> {
                double[] r = parseRect(attr(el,"rect"));
                // A callout is a FreeText carrying a leader line, marked
                // with IT="FreeTextCallout"; without it this is plain text.
                boolean isCallout = "FreeTextCallout".equals(attr(el, "IT"));
                shape.put("tool", isCallout ? "callout" : "text");
                shape.put("x",r[0]); shape.put("y",r[1]);
                if (isCallout) { shape.put("x2",r[2]); shape.put("y2",r[3]); }
                shape.put("text", contents);
                type = Annotation.AnnotationType.MARKUP;
            }
            case "polygon" -> {
                var pts = mapper.createArrayNode();
                String[] pairs = attr(el,"vertices").split(";");
                for (String pair : pairs) {
                    String[] xy = pair.trim().split(",");
                    if (xy.length >= 2) {
                        var pt = mapper.createObjectNode();
                        pt.put("x", parseDouble(xy[0]));
                        pt.put("y", parseDouble(xy[1]));
                        pts.add(pt);
                    }
                }
                // Bluebeam/Acrobat mark cloud-style polygons with IT="PolygonCloud"
                // (see polygonAnnot's export side) — a plain polygon has no IT.
                boolean isCloud = "PolygonCloud".equals(attr(el, "IT"));
                shape.put("tool", isCloud ? "cloud" : "polygon");
                shape.set("points", pts);
                type = isCloud ? Annotation.AnnotationType.CLOUD : Annotation.AnnotationType.MARKUP;
            }
            case "polyline" -> {
                var pts = mapper.createArrayNode();
                String[] pairs = attr(el,"vertices").split(";");
                for (String pair : pairs) {
                    String[] xy = pair.trim().split(",");
                    if (xy.length >= 2) {
                        var pt = mapper.createObjectNode();
                        pt.put("x", parseDouble(xy[0]));
                        pt.put("y", parseDouble(xy[1]));
                        pts.add(pt);
                    }
                }
                shape.put("tool","polyline");
                shape.set("points", pts);
                type = Annotation.AnnotationType.MARKUP;
            }
            case "stamp" -> {
                double[] r = parseRect(attr(el,"rect"));
                shape.put("tool","stamp");
                shape.put("x",r[0]); shape.put("y",r[1]);
                shape.put("text", attr(el,"name","APPROVED"));
                type = Annotation.AnnotationType.STAMP;
            }
            default -> { return null; }  // unsupported element — skip
        }

        return new ImportedAnnotation(
            type,
            shape.toString(),
            contents,
            page,
            author
        );
    }

    // ── XFDF parse helpers ────────────────────────────────────────
    private String attr(Element el, String name) { return attr(el, name, ""); }
    private String attr(Element el, String name, String def) {
        String v = el.getAttribute(name);
        return (v == null || v.isBlank()) ? def : v;
    }
    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
    private double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; }
    }
    private double[] parseRect(String rect) {
        if (rect == null || rect.isBlank()) return new double[]{0,0,100,100};
        String[] p = rect.split(",");
        double[] r = new double[4];
        for (int i = 0; i < Math.min(4, p.length); i++) r[i] = parseDouble(p[i]);
        return r;
    }
    private String getContents(Element el) {
        NodeList c = el.getElementsByTagName("contents");
        if (c.getLength() > 0) return c.item(0).getTextContent().trim();
        return "";
    }

    // ── Value type returned from fromXfdf ─────────────────────────
    public record ImportedAnnotation(
        Annotation.AnnotationType type,
        String shapeData,
        String comment,
        int    pageNumber,
        String authorName
    ) {}

    // ── Helpers ──────────────────────────────────────────────────
    private String rect(double x1, double y1, double x2, double y2) {
        return String.format("%.2f,%.2f,%.2f,%.2f", x1, y1, x2, y2);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
