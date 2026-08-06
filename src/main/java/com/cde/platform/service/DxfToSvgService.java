package com.cde.platform.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Parses DXF (Drawing Exchange Format) text files and converts them to SVG.
 * Supported entities: LINE, CIRCLE, ARC, ELLIPSE, LWPOLYLINE, POLYLINE/VERTEX,
 *                     TEXT, MTEXT, SOLID
 *
 * DWG binary format is detected and rejected with a clear error + version info.
 */
@Service
public class DxfToSvgService {

    public record ConvertResult(boolean success, String svg, String error) {}

    public ConvertResult convert(Path filePath, String originalName) {
        try {
            byte[] bytes = Files.readAllBytes(filePath);

            // DWG magic: starts with "AC" + 4 digit version e.g. AC1027
            if (bytes.length > 6 && bytes[0] == 'A' && bytes[1] == 'C'
                    && Character.isDigit(bytes[2]) && Character.isDigit(bytes[3])) {
                return new ConvertResult(false, null, "DWG_BINARY:" + detectDwgVersion(bytes));
            }

            // Try UTF-8 first, fallback to Latin-1
            String content;
            try { content = new String(bytes, StandardCharsets.UTF_8); }
            catch (Exception e) { content = new String(bytes, "ISO-8859-1"); }

            if (!content.contains("SECTION") && !content.contains("LINE")
                    && !content.contains("CIRCLE") && !content.contains("ARC")) {
                return new ConvertResult(false, null, "UNKNOWN_FORMAT: not a recognisable DXF file");
            }
            return parseDxf(content);

        } catch (Exception e) {
            return new ConvertResult(false, null, "PARSE_ERROR: " + e.getMessage());
        }
    }

    private String detectDwgVersion(byte[] bytes) {
        String ver = new String(bytes, 0, 6, StandardCharsets.US_ASCII);
        return switch (ver) {
            case "AC1015" -> "AutoCAD 2000 (" + ver + ")";
            case "AC1018" -> "AutoCAD 2004 (" + ver + ")";
            case "AC1021" -> "AutoCAD 2007 (" + ver + ")";
            case "AC1024" -> "AutoCAD 2010 (" + ver + ")";
            case "AC1027" -> "AutoCAD 2013 (" + ver + ")";
            case "AC1032" -> "AutoCAD 2018 (" + ver + ")";
            default       -> ver;
        };
    }

    // ── DXF Parser ────────────────────────────────────────────────────────────

    private ConvertResult parseDxf(String content) {
        List<String[]> groups = readGroups(content);
        List<DxfEntity> entities = extractEntities(groups);

        if (entities.isEmpty())
            return new ConvertResult(false, null, "No drawable entities found in DXF file");

        // Calculate bounding box
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (DxfEntity e : entities) {
            double[] bb = e.bounds();
            if (bb != null) {
                minX = Math.min(minX, bb[0]); minY = Math.min(minY, bb[1]);
                maxX = Math.max(maxX, bb[2]); maxY = Math.max(maxY, bb[3]);
            }
        }

        double pad = Math.max(maxX - minX, maxY - minY) * 0.04 + 5;
        minX -= pad; minY -= pad; maxX += pad; maxY += pad;
        double vw = maxX - minX, vh = maxY - minY;
        if (vw <= 0) vw = 100; if (vh <= 0) vh = 100;

        double svgW = Math.min(vw, 1400);
        double svgH = Math.min(vh * (svgW / vw), 1000);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
            "viewBox=\"%.3f %.3f %.3f %.3f\" width=\"%.0f\" height=\"%.0f\" " +
            "style=\"background:#1a1d27;display:block\">%n",
            minX, minY, vw, vh, svgW, svgH));

        // DXF Y-axis is bottom-up; SVG is top-down — flip vertically
        double flipY = minY + maxY;
        sb.append(String.format("<g transform=\"scale(1,-1) translate(0,%.3f)\">%n", -flipY));

        for (DxfEntity e : entities) {
            String el = e.toSvg();
            if (el != null && !el.isBlank())
                sb.append("  ").append(el).append("\n");
        }
        sb.append("</g>\n</svg>");
        return new ConvertResult(true, sb.toString(), null);
    }

    // ── Group code reader ─────────────────────────────────────────────────────

    private List<String[]> readGroups(String content) {
        List<String[]> groups = new ArrayList<>();
        String[] lines = content.split("\r?\n");
        for (int i = 0; i + 1 < lines.length; i += 2) {
            groups.add(new String[]{ lines[i].trim(), lines[i + 1].trim() });
        }
        return groups;
    }

    // ── Entity extraction ─────────────────────────────────────────────────────

    private List<DxfEntity> extractEntities(List<String[]> groups) {
        List<DxfEntity> entities = new ArrayList<>();
        boolean inEntities = false;
        int i = 0;

        while (i < groups.size()) {
            String[] g = groups.get(i);
            if ("2".equals(g[0])) {
                if ("ENTITIES".equals(g[1])) { inEntities = true; i++; continue; }
                if ("ENDSEC".equals(g[1]))   { inEntities = false; }
            }
            if (inEntities && "0".equals(g[0])) {
                String type = g[1];
                int end = i + 1;
                while (end < groups.size() && !"0".equals(groups.get(end)[0])) end++;
                DxfEntity entity = buildEntity(type, groups.subList(i, end));
                if (entity != null) entities.add(entity);
                i = end;
            } else {
                i++;
            }
        }
        return entities;
    }

    private DxfEntity buildEntity(String type, List<String[]> props) {
        Map<String, String> first = new LinkedHashMap<>();
        List<Map<String, String>> vertices = new ArrayList<>();
        Map<String, String> cur = first;
        for (String[] g : props) {
            if ("0".equals(g[0]) && "VERTEX".equals(g[1])) { cur = new LinkedHashMap<>(); vertices.add(cur); }
            else cur.putIfAbsent(g[0], g[1]);
        }
        String color = aciToHex(parseInt(first.get("62"), 7));
        return switch (type) {
            case "LINE"           -> new LineEnt(first, color);
            case "CIRCLE"         -> new CircleEnt(first, color);
            case "ARC"            -> new ArcEnt(first, color);
            case "ELLIPSE"        -> new EllipseEnt(first, color);
            case "LWPOLYLINE"     -> new LwPolyEnt(props, color);
            case "POLYLINE"       -> new PolyEnt(vertices, color);
            case "TEXT", "MTEXT"  -> new TextEnt(first, color);
            case "SOLID"          -> new SolidEnt(first, color);
            default               -> null;
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static double dbl(Map<String,String> m, String k) { return dbl(m, k, 0.0); }
    private static double dbl(Map<String,String> m, String k, double def) {
        String v = m.get(k); return v == null ? def : Double.parseDouble(v);
    }
    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
    private String aciToHex(int ci) {
        return switch (ci) {
            case 1 -> "#ff4444"; case 2 -> "#ffff00"; case 3 -> "#44ff44";
            case 4 -> "#44ffff"; case 5 -> "#4488ff"; case 6 -> "#ff44ff";
            case 7, 0 -> "#e0e0e0"; case 8 -> "#888888"; case 9 -> "#cccccc";
            default -> "#00bfff";
        };
    }

    // ── Entity types ──────────────────────────────────────────────────────────

    interface DxfEntity { String toSvg(); double[] bounds(); }

    class LineEnt implements DxfEntity {
        double x1,y1,x2,y2; String c;
        LineEnt(Map<String,String> p, String c) { x1=dbl(p,"10");y1=dbl(p,"20");x2=dbl(p,"11");y2=dbl(p,"21");this.c=c; }
        public String toSvg() { return String.format("<line x1=\"%.3f\" y1=\"%.3f\" x2=\"%.3f\" y2=\"%.3f\" stroke=\"%s\" stroke-width=\"0.5\" vector-effect=\"non-scaling-stroke\"/>",x1,y1,x2,y2,c); }
        public double[] bounds() { return new double[]{Math.min(x1,x2),Math.min(y1,y2),Math.max(x1,x2),Math.max(y1,y2)}; }
    }

    class CircleEnt implements DxfEntity {
        double cx,cy,r; String c;
        CircleEnt(Map<String,String> p, String c) { cx=dbl(p,"10");cy=dbl(p,"20");r=dbl(p,"40");this.c=c; }
        public String toSvg() { return String.format("<circle cx=\"%.3f\" cy=\"%.3f\" r=\"%.3f\" stroke=\"%s\" fill=\"none\" stroke-width=\"0.5\" vector-effect=\"non-scaling-stroke\"/>",cx,cy,r,c); }
        public double[] bounds() { return new double[]{cx-r,cy-r,cx+r,cy+r}; }
    }

    class ArcEnt implements DxfEntity {
        double cx,cy,r,sa,ea; String c;
        ArcEnt(Map<String,String> p, String c) { cx=dbl(p,"10");cy=dbl(p,"20");r=dbl(p,"40");sa=dbl(p,"50");ea=dbl(p,"51");this.c=c; }
        public String toSvg() {
            double s=Math.toRadians(sa),e=Math.toRadians(ea);
            double x1=cx+r*Math.cos(s),y1=cy+r*Math.sin(s),x2=cx+r*Math.cos(e),y2=cy+r*Math.sin(e);
            double sw=ea>sa?ea-sa:360-sa+ea; int lg=sw>180?1:0;
            return String.format("<path d=\"M %.3f %.3f A %.3f %.3f 0 %d 1 %.3f %.3f\" stroke=\"%s\" fill=\"none\" stroke-width=\"0.5\" vector-effect=\"non-scaling-stroke\"/>",x1,y1,r,r,lg,x2,y2,c);
        }
        public double[] bounds() { return new double[]{cx-r,cy-r,cx+r,cy+r}; }
    }

    class EllipseEnt implements DxfEntity {
        double cx,cy,mx,my,ratio; String c;
        EllipseEnt(Map<String,String> p, String c) { cx=dbl(p,"10");cy=dbl(p,"20");mx=dbl(p,"11");my=dbl(p,"21");ratio=dbl(p,"40",1);this.c=c; }
        public String toSvg() {
            double rx=Math.sqrt(mx*mx+my*my),ry=rx*ratio,ang=Math.toDegrees(Math.atan2(my,mx));
            return String.format("<ellipse cx=\"%.3f\" cy=\"%.3f\" rx=\"%.3f\" ry=\"%.3f\" transform=\"rotate(%.2f %.3f %.3f)\" stroke=\"%s\" fill=\"none\" stroke-width=\"0.5\" vector-effect=\"non-scaling-stroke\"/>",cx,cy,rx,ry,ang,cx,cy,c);
        }
        public double[] bounds() { double rx=Math.sqrt(mx*mx+my*my); return new double[]{cx-rx,cy-rx,cx+rx,cy+rx}; }
    }

    class LwPolyEnt implements DxfEntity {
        List<double[]> pts=new ArrayList<>(); boolean closed; String c;
        LwPolyEnt(List<String[]> props, String c) {
            this.c=c; double px=0,py=0; boolean hx=false,hy=false;
            for (String[] g:props) {
                if ("70".equals(g[0])) closed=(parseInt(g[1],0)&1)==1;
                if ("10".equals(g[0])){px=Double.parseDouble(g[1]);hx=true;}
                if ("20".equals(g[0])){py=Double.parseDouble(g[1]);hy=true;}
                if (hx&&hy){pts.add(new double[]{px,py});hx=false;hy=false;}
            }
        }
        public String toSvg() {
            if (pts.isEmpty()) return null;
            StringBuilder sb=new StringBuilder(closed?"<polygon points=\"":"<polyline points=\"");
            for (double[] p:pts) sb.append(String.format("%.3f,%.3f ",p[0],p[1]));
            sb.append("\" stroke=\"").append(c).append("\" fill=\"none\" stroke-width=\"0.5\" vector-effect=\"non-scaling-stroke\"/>");
            return sb.toString();
        }
        public double[] bounds() {
            if (pts.isEmpty()) return null;
            double mnX=pts.get(0)[0],mnY=pts.get(0)[1],mxX=mnX,mxY=mnY;
            for (double[] p:pts){mnX=Math.min(mnX,p[0]);mnY=Math.min(mnY,p[1]);mxX=Math.max(mxX,p[0]);mxY=Math.max(mxY,p[1]);}
            return new double[]{mnX,mnY,mxX,mxY};
        }
    }

    class PolyEnt implements DxfEntity {
        List<Map<String,String>> verts; String c;
        PolyEnt(List<Map<String,String>> v, String c){this.verts=v;this.c=c;}
        public String toSvg() {
            if (verts.isEmpty()) return null;
            StringBuilder sb=new StringBuilder("<polyline points=\"");
            for (Map<String,String> v:verts) sb.append(String.format("%.3f,%.3f ",dbl(v,"10"),dbl(v,"20")));
            sb.append("\" stroke=\"").append(c).append("\" fill=\"none\" stroke-width=\"0.5\" vector-effect=\"non-scaling-stroke\"/>");
            return sb.toString();
        }
        public double[] bounds() {
            if (verts.isEmpty()) return null;
            double mnX=dbl(verts.get(0),"10"),mnY=dbl(verts.get(0),"20"),mxX=mnX,mxY=mnY;
            for (Map<String,String> v:verts){double x=dbl(v,"10"),y=dbl(v,"20");mnX=Math.min(mnX,x);mnY=Math.min(mnY,y);mxX=Math.max(mxX,x);mxY=Math.max(mxY,y);}
            return new double[]{mnX,mnY,mxX,mxY};
        }
    }

    class TextEnt implements DxfEntity {
        double x,y,h; String text,c;
        TextEnt(Map<String,String> p, String c) {
            x=dbl(p,"10");y=dbl(p,"20");h=dbl(p,"40",2.5);
            text=p.getOrDefault("1","").replace("\\P"," ").replaceAll("\\\\[^;]+;","");
            if (text.isBlank()) text=p.getOrDefault("3","");
            this.c=c;
        }
        public String toSvg() {
            if (text.isBlank()) return null;
            String esc=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
            return String.format("<text x=\"%.3f\" y=\"%.3f\" font-size=\"%.2f\" fill=\"%s\" font-family=\"Arial,sans-serif\" transform=\"scale(1,-1) translate(0,%.3f)\">%s</text>",x,-y,Math.max(h,1.0),c,2*y,esc);
        }
        public double[] bounds(){return new double[]{x,y,x+text.length()*h*0.6,y+h};}
    }

    class SolidEnt implements DxfEntity {
        double x1,y1,x2,y2,x3,y3,x4,y4; String c;
        SolidEnt(Map<String,String> p, String c){x1=dbl(p,"10");y1=dbl(p,"20");x2=dbl(p,"11");y2=dbl(p,"21");x3=dbl(p,"12");y3=dbl(p,"22");x4=dbl(p,"13");y4=dbl(p,"23");this.c=c;}
        public String toSvg(){return String.format("<polygon points=\"%.2f,%.2f %.2f,%.2f %.2f,%.2f %.2f,%.2f\" fill=\"%s\" stroke=\"none\"/>",x1,y1,x2,y2,x4,y4,x3,y3,c);}
        public double[] bounds(){return new double[]{Math.min(Math.min(x1,x2),Math.min(x3,x4)),Math.min(Math.min(y1,y2),Math.min(y3,y4)),Math.max(Math.max(x1,x2),Math.max(x3,x4)),Math.max(Math.max(y1,y2),Math.max(y3,y4))};}
    }
}
