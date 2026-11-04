package com.taklite.client.mod.imports;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * One drawable item from an imported file: a point, line or polygon, with its
 * label, notes and style. Every format (CoT, KML, GPX) is converted to this,
 * and it is what gets stored on the phone and drawn on the map.
 */
final class Feature {

    static final int POINT = 0;
    static final int LINE = 1;
    static final int POLYGON = 2;

    static final int DEFAULT_LINE_COLOR = 0xFFE53935;
    static final float DEFAULT_LINE_WIDTH = 3f;
    static final int DEFAULT_POINT_COLOR = 0xFFFFFFFF;

    int kind;
    String name = "";
    String remarks = "";
    /** lat0, lon0, lat1, lon1, ... */
    double[] coords;
    int strokeColor = DEFAULT_LINE_COLOR;
    float strokeWidth = DEFAULT_LINE_WIDTH;
    int fillColor;
    /** Points only: CoT type (e.g. a-n-G) and ATAK iconset path, for the symbol. */
    String cotType;
    String iconsetPath;
    int pointColor = DEFAULT_POINT_COLOR;
    /** File name of an attached photo, inside the import's folder. */
    String image;
    /** CoT uid, used to attach data package photos to their marker. */
    transient String uid;

    static Feature point(double lat, double lon) {
        Feature f = new Feature();
        f.kind = POINT;
        f.coords = new double[]{lat, lon};
        return f;
    }

    static Feature path(int kind, List<double[]> latLons) {
        Feature f = new Feature();
        f.kind = kind;
        f.coords = new double[latLons.size() * 2];
        for (int i = 0; i < latLons.size(); i++) {
            f.coords[i * 2] = latLons.get(i)[0];
            f.coords[i * 2 + 1] = latLons.get(i)[1];
        }
        return f;
    }

    int pointCount() {
        return coords.length / 2;
    }

    double lat(int i) {
        return coords[i * 2];
    }

    double lon(int i) {
        return coords[i * 2 + 1];
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("k", kind);
        if (!name.isEmpty()) o.put("n", name);
        if (!remarks.isEmpty()) o.put("r", remarks);
        JSONArray c = new JSONArray();
        for (double v : coords) c.put(Math.round(v * 1e7) / 1e7);
        o.put("c", c);
        if (kind == POINT) {
            if (cotType != null) o.put("t", cotType);
            if (iconsetPath != null) o.put("i", iconsetPath);
            o.put("pc", pointColor);
        } else {
            o.put("sc", strokeColor);
            o.put("sw", (double) strokeWidth);
            if (fillColor != 0) o.put("fc", fillColor);
        }
        if (image != null) o.put("img", image);
        return o;
    }

    static Feature fromJson(JSONObject o) throws JSONException {
        Feature f = new Feature();
        f.kind = o.getInt("k");
        f.name = o.optString("n", "");
        f.remarks = o.optString("r", "");
        JSONArray c = o.getJSONArray("c");
        f.coords = new double[c.length()];
        for (int i = 0; i < c.length(); i++) f.coords[i] = c.getDouble(i);
        f.cotType = o.has("t") ? o.getString("t") : null;
        f.iconsetPath = o.has("i") ? o.getString("i") : null;
        f.pointColor = o.optInt("pc", DEFAULT_POINT_COLOR);
        f.strokeColor = o.optInt("sc", DEFAULT_LINE_COLOR);
        f.strokeWidth = (float) o.optDouble("sw", DEFAULT_LINE_WIDTH);
        f.fillColor = o.optInt("fc", 0);
        f.image = o.has("img") ? o.getString("img") : null;
        return f;
    }

    static boolean validLatLon(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }
}
