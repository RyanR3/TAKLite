package com.taklite.client.mod.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts one CoT event (as ATAK, iTAK or CloudTAK put them in data packages)
 * into a {@link Feature}:
 * <ul>
 *   <li>events with {@code <link point="lat,lon"/>} vertices (freehand u-d-f,
 *       rectangles u-d-r, routes b-m-r, ...) become lines, or polygons when
 *       closed or filled;</li>
 *   <li>{@code <shape><ellipse/>} (circles u-d-c-c, ellipses) become polygons;</li>
 *   <li>anything else becomes a point marker at the event's point.</li>
 * </ul>
 */
final class CotShapes {

    private static final int CIRCLE_SEGMENTS = 72;
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private CotShapes() {
    }

    /** Returns null for events that don't describe something to draw. */
    static Feature toFeature(XmlNode event) {
        if (!"event".equals(event.name)) return null;
        String type = nonNull(event.attr("type"));
        // Control messages, not map items.
        if (type.startsWith("t-") || type.startsWith("b-f-t") || type.startsWith("b-t-f")) return null;

        XmlNode point = event.child("point");
        XmlNode detail = event.child("detail");
        if (detail == null) detail = XmlNode.empty("detail");
        double lat = parseDouble(point == null ? null : point.attr("lat"));
        double lon = parseDouble(point == null ? null : point.attr("lon"));

        List<double[]> vertices = new ArrayList<>();
        for (XmlNode link : detail.children("link")) {
            double[] ll = parseLatLon(link.attr("point"));
            if (ll != null) vertices.add(ll);
        }

        Feature f;
        XmlNode ellipse = firstDescendant(detail, "ellipse");
        if (vertices.size() >= 2) {
            boolean endsAtStart = sameSpot(vertices.get(0), vertices.get(vertices.size() - 1));
            int fill = argb(detail, "fillColor", 0);
            boolean polygon = endsAtStart || type.startsWith("u-d-r")
                    || ((fill >>> 24) > 0 && vertices.size() >= 3);
            if (polygon && !endsAtStart) vertices.add(vertices.get(0));
            int kind = polygon ? Feature.POLYGON : Feature.LINE;
            f = Feature.path(kind, vertices);
            f.fillColor = kind == Feature.POLYGON ? fill : 0;
        } else if (ellipse != null && Feature.validLatLon(lat, lon)) {
            double major = parseDouble(ellipse.attr("major"));
            double minor = parseDouble(ellipse.attr("minor"));
            double angle = parseDouble(ellipse.attr("angle"));
            if (Double.isNaN(major) || major <= 0) return null;
            if (Double.isNaN(minor) || minor <= 0) minor = major;
            f = Feature.path(Feature.POLYGON, ellipse(lat, lon, major, minor, Double.isNaN(angle) ? 0 : angle));
            f.fillColor = argb(detail, "fillColor", 0);
        } else if (Feature.validLatLon(lat, lon)) {
            f = Feature.point(lat, lon);
            f.cotType = type;
            XmlNode icon = detail.child("usericon");
            if (icon != null && icon.attr("iconsetpath") != null) f.iconsetPath = icon.attr("iconsetpath");
            f.pointColor = argb(detail, "color", Feature.DEFAULT_POINT_COLOR);
        } else {
            return null;
        }

        if (f.kind != Feature.POINT) {
            f.strokeColor = argb(detail, "strokeColor", argb(detail, "color", Feature.DEFAULT_LINE_COLOR));
            double weight = parseDouble(valueOf(detail.child("strokeWeight")));
            if (!Double.isNaN(weight) && weight > 0) f.strokeWidth = (float) weight;
        }
        f.uid = event.attr("uid");
        XmlNode contact = detail.child("contact");
        f.name = nonNull(contact == null ? null : contact.attr("callsign"));
        f.remarks = nonNull(detail.childText("remarks"));
        return f;
    }

    /** {@code <x value="-2737863"/>} or {@code <color argb="-1"/>}, as signed ARGB ints. */
    private static int argb(XmlNode detail, String element, int fallback) {
        XmlNode n = detail.child(element);
        if (n == null) return fallback;
        String v = n.attr("argb") != null ? n.attr("argb") : n.attr("value");
        if (v == null) return fallback;
        try {
            return (int) Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String valueOf(XmlNode n) {
        return n == null ? null : n.attr("value");
    }

    /** "lat,lon" or "lat,lon,hae". */
    static double[] parseLatLon(String s) {
        if (s == null) return null;
        String[] parts = s.split(",");
        if (parts.length < 2) return null;
        double lat = parseDouble(parts[0]);
        double lon = parseDouble(parts[1]);
        return Feature.validLatLon(lat, lon) ? new double[]{lat, lon} : null;
    }

    static double parseDouble(String s) {
        if (s == null) return Double.NaN;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static boolean sameSpot(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-7 && Math.abs(a[1] - b[1]) < 1e-7;
    }

    private static XmlNode firstDescendant(XmlNode node, String name) {
        List<XmlNode> found = node.descendants(name);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Ellipse outline; semi-axes in metres, angle in degrees clockwise from north. */
    static List<double[]> ellipse(double lat, double lon, double major, double minor, double angleDeg) {
        List<double[]> pts = new ArrayList<>();
        double rot = Math.toRadians(angleDeg);
        double latRad = Math.toRadians(lat);
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double t = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            double north = major * Math.cos(t);
            double east = minor * Math.sin(t);
            double n = north * Math.cos(rot) - east * Math.sin(rot);
            double e = north * Math.sin(rot) + east * Math.cos(rot);
            double dLat = Math.toDegrees(n / EARTH_RADIUS_M);
            double dLon = Math.toDegrees(e / (EARTH_RADIUS_M * Math.cos(latRad)));
            pts.add(new double[]{lat + dLat, lon + dLon});
        }
        return pts;
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
