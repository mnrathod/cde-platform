package com.cde.platform.service;

import com.cde.platform.model.Annotation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes CDE annotations out as XFDF.
 *
 * <p>Split from the reader because the two directions share almost nothing —
 * one walks a shape tree emitting markup, the other walks a DOM building
 * shapes — and together they made a single class of nearly six hundred lines
 * whose two halves were already separated by a comment banner.
 *
 * <p>Package-private: callers go through {@link XfdfService}, so this split is
 * an internal arrangement rather than a change to anyone's API.
 */
class XfdfWriter {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final DateTimeFormatter XFDF_DATE =
        DateTimeFormatter.ofPattern("'D:'yyyyMMddHHmmss");

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

    private String rect(double x1, double y1, double x2, double y2) {
        return String.format("%.2f,%.2f,%.2f,%.2f", x1, y1, x2, y2);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
