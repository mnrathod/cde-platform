package com.cde.platform.service;

import com.cde.platform.model.Annotation;
import com.cde.platform.service.XfdfService.ImportedAnnotation;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads XFDF produced by other tools back into CDE annotations.
 *
 * <p>The counterpart to {@link XfdfWriter}. Kept apart from it for the reason
 * in that class, and package-private for the same reason: {@link XfdfService}
 * is the entry point.
 */
class XfdfReader {

    private static final Logger log = LoggerFactory.getLogger(XfdfReader.class);

    private final ObjectMapper mapper = new ObjectMapper();

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
                if (Math.abs(boxWidth - boxHeight) > XfdfService.SHAPE_EPSILON) {
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
}
