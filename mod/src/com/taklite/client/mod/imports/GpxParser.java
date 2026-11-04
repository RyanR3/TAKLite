package com.taklite.client.mod.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** GPX 1.0/1.1 to {@link Feature}s: waypoints, routes and track segments. */
final class GpxParser {

    /** Garmin gpxx:DisplayColor names. */
    private static final Map<String, Integer> GARMIN_COLORS = new HashMap<>();

    static {
        GARMIN_COLORS.put("black", 0xFF000000);
        GARMIN_COLORS.put("darkred", 0xFF8B0000);
        GARMIN_COLORS.put("darkgreen", 0xFF006400);
        GARMIN_COLORS.put("darkyellow", 0xFFB5B500);
        GARMIN_COLORS.put("darkblue", 0xFF00008B);
        GARMIN_COLORS.put("darkmagenta", 0xFF8B008B);
        GARMIN_COLORS.put("darkcyan", 0xFF008B8B);
        GARMIN_COLORS.put("lightgray", 0xFFD3D3D3);
        GARMIN_COLORS.put("darkgray", 0xFFA9A9A9);
        GARMIN_COLORS.put("red", 0xFFFF0000);
        GARMIN_COLORS.put("green", 0xFF00C000);
        GARMIN_COLORS.put("yellow", 0xFFFFFF00);
        GARMIN_COLORS.put("blue", 0xFF0000FF);
        GARMIN_COLORS.put("magenta", 0xFFFF00FF);
        GARMIN_COLORS.put("cyan", 0xFF00FFFF);
        GARMIN_COLORS.put("white", 0xFFFFFFFF);
    }

    private GpxParser() {
    }

    static List<Feature> parse(XmlNode gpx) {
        List<Feature> out = new ArrayList<>();
        for (XmlNode w : gpx.children("wpt")) {
            double lat = CotShapes.parseDouble(w.attr("lat"));
            double lon = CotShapes.parseDouble(w.attr("lon"));
            if (!Feature.validLatLon(lat, lon)) continue;
            Feature f = Feature.point(lat, lon);
            f.name = text(w.childText("name"));
            f.remarks = describe(w);
            out.add(f);
        }
        for (XmlNode r : gpx.children("rte")) {
            addLine(out, points(r.children("rtept")), r);
        }
        for (XmlNode t : gpx.children("trk")) {
            for (XmlNode seg : t.children("trkseg")) {
                addLine(out, points(seg.children("trkpt")), t);
            }
        }
        return out;
    }

    private static void addLine(List<Feature> out, List<double[]> pts, XmlNode owner) {
        if (pts.size() < 2) return;
        Feature f = Feature.path(Feature.LINE, pts);
        f.name = text(owner.childText("name"));
        f.remarks = describe(owner);
        Integer color = color(owner);
        if (color != null) f.strokeColor = color;
        out.add(f);
    }

    private static List<double[]> points(List<XmlNode> nodes) {
        List<double[]> pts = new ArrayList<>();
        for (XmlNode p : nodes) {
            double lat = CotShapes.parseDouble(p.attr("lat"));
            double lon = CotShapes.parseDouble(p.attr("lon"));
            if (Feature.validLatLon(lat, lon)) pts.add(new double[]{lat, lon});
        }
        return pts;
    }

    /** Track colour from the common extensions (Garmin, OsmAnd, GPS Track Editor). */
    private static Integer color(XmlNode owner) {
        XmlNode ext = owner.child("extensions");
        if (ext == null) return null;
        for (XmlNode n : ext.descendants("DisplayColor")) {
            Integer c = GARMIN_COLORS.get(n.text().toLowerCase(Locale.US));
            if (c != null) return c;
        }
        for (XmlNode n : ext.descendants("color")) {
            Integer c = hexColor(n.text());
            if (c != null) return c;
        }
        return null;
    }

    /** "#rrggbb" or "#aarrggbb". */
    static Integer hexColor(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 6) return 0xFF000000 | Integer.parseInt(s, 16);
            if (s.length() == 8) return (int) Long.parseLong(s, 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static String describe(XmlNode n) {
        String desc = text(n.childText("desc"));
        String cmt = text(n.childText("cmt"));
        if (desc.isEmpty()) return cmt;
        if (cmt.isEmpty() || cmt.equals(desc)) return desc;
        return desc + "\n" + cmt;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }
}
