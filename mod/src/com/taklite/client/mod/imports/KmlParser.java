package com.taklite.client.mod.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KML to {@link Feature}s: Placemarks with Point, LineString, LinearRing,
 * Polygon (outer boundary), MultiGeometry and gx:Track, styled from shared or
 * inline Style / StyleMap. Folders are flattened.
 */
final class KmlParser {

    /** Polygon fills are drawn translucent at most, so they never hide the map (ATAK exports opaque white). */
    private static final int MAX_FILL_ALPHA = 0x55;

    private static final class Style {
        Integer lineColor;
        Float lineWidth;
        Integer polyColor;
        Boolean fill;
        Boolean outline;
        Integer iconColor;
    }

    private KmlParser() {
    }

    static List<Feature> parse(XmlNode kml) {
        Map<String, Style> styles = new HashMap<>();
        for (XmlNode s : kml.descendants("Style")) {
            String id = s.attr("id");
            if (id != null) styles.put(id, readStyle(s));
        }
        Map<String, String> styleMaps = new HashMap<>();
        for (XmlNode m : kml.descendants("StyleMap")) {
            String id = m.attr("id");
            if (id == null) continue;
            for (XmlNode pair : m.children("Pair")) {
                if ("normal".equals(pair.childText("key")) && pair.childText("styleUrl") != null) {
                    styleMaps.put(id, stripHash(pair.childText("styleUrl")));
                }
            }
        }

        List<XmlNode> placemarks = kml.descendants("Placemark");
        Set<String> ids = new HashSet<>();
        for (XmlNode p : placemarks) {
            if (p.attr("id") != null) ids.add(p.attr("id"));
        }

        List<Feature> out = new ArrayList<>();
        for (XmlNode p : placemarks) {
            if ("0".equals(p.childText("visibility"))) continue;
            String id = p.attr("id");
            // ATAK exports every drawing twice: "<uid><name> outer" (the shape) and
            // "<uid><name> center" (a label point). Keep only the shape.
            if (id != null && id.endsWith(" center")
                    && ids.contains(id.substring(0, id.length() - " center".length()) + " outer")) {
                continue;
            }
            Style style = new Style();
            String url = p.childText("styleUrl");
            if (url != null) {
                String key = stripHash(url);
                if (styleMaps.containsKey(key)) key = styleMaps.get(key);
                if (styles.containsKey(key)) merge(style, styles.get(key));
            }
            XmlNode inline = p.child("Style");
            if (inline != null) merge(style, readStyle(inline));

            String name = text(p.childText("name"));
            String remarks = stripHtml(text(p.childText("description")));
            int before = out.size();
            addGeometries(p, style, out);
            for (int i = before; i < out.size(); i++) {
                out.get(i).name = name;
                out.get(i).remarks = remarks;
            }
        }
        return out;
    }

    private static void addGeometries(XmlNode parent, Style style, List<Feature> out) {
        for (XmlNode g : parent.children) {
            switch (g.name) {
                case "Point": {
                    List<double[]> pts = coordinates(g.childText("coordinates"));
                    if (!pts.isEmpty()) {
                        Feature f = Feature.point(pts.get(0)[0], pts.get(0)[1]);
                        if (style.iconColor != null) f.pointColor = style.iconColor;
                        out.add(f);
                    }
                    break;
                }
                case "LineString":
                    addLine(coordinates(g.childText("coordinates")), style, out);
                    break;
                case "LinearRing":
                    addPolygon(coordinates(g.childText("coordinates")), style, out);
                    break;
                case "Polygon": {
                    XmlNode outer = g.child("outerBoundaryIs");
                    XmlNode ring = outer == null ? null : outer.child("LinearRing");
                    if (ring != null) addPolygon(coordinates(ring.childText("coordinates")), style, out);
                    break;
                }
                case "Track": {
                    // gx:Track: <gx:coord>lon lat alt</gx:coord>
                    List<double[]> pts = new ArrayList<>();
                    for (XmlNode c : g.children("coord")) {
                        String[] parts = c.text().split("\\s+");
                        if (parts.length >= 2) addIfValid(pts, parts[1], parts[0]);
                    }
                    addLine(pts, style, out);
                    break;
                }
                case "MultiGeometry":
                case "MultiTrack":
                    addGeometries(g, style, out);
                    break;
                default:
                    break;
            }
        }
    }

    private static void addLine(List<double[]> pts, Style style, List<Feature> out) {
        if (pts.size() < 2) return;
        Feature f = Feature.path(Feature.LINE, pts);
        if (style.lineColor != null) f.strokeColor = style.lineColor;
        if (style.lineWidth != null && style.lineWidth > 0) f.strokeWidth = style.lineWidth;
        out.add(f);
    }

    private static void addPolygon(List<double[]> pts, Style style, List<Feature> out) {
        if (pts.size() < 3) return;
        Feature f = Feature.path(Feature.POLYGON, pts);
        boolean outline = style.outline == null || style.outline;
        f.strokeColor = outline ? (style.lineColor != null ? style.lineColor : Feature.DEFAULT_LINE_COLOR) : 0;
        if (style.lineWidth != null && style.lineWidth > 0) f.strokeWidth = style.lineWidth;
        boolean fill = style.fill == null || style.fill;
        int color = style.polyColor != null ? style.polyColor : f.strokeColor;
        int alpha = Math.min(color >>> 24, MAX_FILL_ALPHA);
        f.fillColor = fill ? (alpha << 24) | (color & 0xFFFFFF) : 0;
        out.add(f);
    }

    private static Style readStyle(XmlNode s) {
        Style st = new Style();
        XmlNode line = s.child("LineStyle");
        if (line != null) {
            st.lineColor = kmlColor(line.childText("color"));
            Double w = number(line.childText("width"));
            if (w != null) st.lineWidth = w.floatValue();
        }
        XmlNode poly = s.child("PolyStyle");
        if (poly != null) {
            st.polyColor = kmlColor(poly.childText("color"));
            st.fill = bool(poly.childText("fill"));
            st.outline = bool(poly.childText("outline"));
        }
        XmlNode icon = s.child("IconStyle");
        if (icon != null) st.iconColor = kmlColor(icon.childText("color"));
        return st;
    }

    private static void merge(Style into, Style from) {
        if (from.lineColor != null) into.lineColor = from.lineColor;
        if (from.lineWidth != null) into.lineWidth = from.lineWidth;
        if (from.polyColor != null) into.polyColor = from.polyColor;
        if (from.fill != null) into.fill = from.fill;
        if (from.outline != null) into.outline = from.outline;
        if (from.iconColor != null) into.iconColor = from.iconColor;
    }

    /** KML colours are aabbggrr hex. */
    static Integer kmlColor(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 8) return null;
        try {
            long v = Long.parseLong(s, 16);
            int a = (int) (v >>> 24) & 0xFF;
            int b = (int) (v >>> 16) & 0xFF;
            int g = (int) (v >>> 8) & 0xFF;
            int r = (int) v & 0xFF;
            return (a << 24) | (r << 16) | (g << 8) | b;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "lon,lat[,alt] lon,lat[,alt] ..." */
    static List<double[]> coordinates(String text) {
        List<double[]> pts = new ArrayList<>();
        if (text == null) return pts;
        for (String tuple : text.trim().split("\\s+")) {
            String[] parts = tuple.split(",");
            if (parts.length >= 2) addIfValid(pts, parts[1], parts[0]);
        }
        return pts;
    }

    private static void addIfValid(List<double[]> pts, String lat, String lon) {
        double la = CotShapes.parseDouble(lat);
        double lo = CotShapes.parseDouble(lon);
        if (Feature.validLatLon(la, lo)) pts.add(new double[]{la, lo});
    }

    private static Boolean bool(String s) {
        if (s == null) return null;
        s = s.trim();
        return !(s.equals("0") || s.equalsIgnoreCase("false"));
    }

    private static Double number(String s) {
        double d = CotShapes.parseDouble(s);
        return Double.isNaN(d) ? null : d;
    }

    private static String stripHash(String url) {
        String u = url.trim();
        int hash = u.lastIndexOf('#');
        return hash >= 0 ? u.substring(hash + 1) : u;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    /** Descriptions are often HTML; keep readable text. */
    static String stripHtml(String s) {
        if (s.indexOf('<') < 0) return s;
        String t = s.replaceAll("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>", "\n")
                .replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return t.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
    }
}
