package com.taklite.client.mod.imports;

import com.taklite.client.mod.CustomMapSources;

import java.util.Locale;

/**
 * ATAK map source files ({@code <customMapSource>}, the Mobile Atlas Creator format
 * ATAK reads from imagery/mobile/mapsources), as found in map packs:
 * <pre>
 *   &lt;customMapSource&gt;
 *     &lt;name&gt;Google Hybrid&lt;/name&gt;
 *     &lt;minZoom&gt;0&lt;/minZoom&gt;
 *     &lt;maxZoom&gt;20&lt;/maxZoom&gt;
 *     &lt;url&gt;https://mt{$serverpart}.google.com/vt/lyrs=y&amp;amp;x={$x}&amp;amp;y={$y}&amp;amp;z={$z}&lt;/url&gt;
 *     &lt;serverParts&gt;0 1 2 3&lt;/serverParts&gt;
 *     &lt;invertYCoordinate&gt;false&lt;/invertYCoordinate&gt;
 *   &lt;/customMapSource&gt;
 * </pre>
 * The URL's {$x} {$y} {$z} {$q} {$serverpart} placeholders become TAK Lite's
 * {x} {y} {z} {q} {s:...}. WMS and multi-layer sources aren't supported.
 */
final class MapSourceParser {

    private static final int MAX_ZOOM = 29;

    private MapSourceParser() {
    }

    static boolean isMapSource(XmlNode root) {
        String n = root.name.toLowerCase(Locale.US);
        return n.equals("custommapsource") || n.equals("customwmsmapsource") || n.equals("custommultilayermapsource");
    }

    /** Adds the source to {@code result}, or records why it was left out. */
    static void parse(XmlNode root, String fileName, Importer.Result result) {
        String kind = root.name.toLowerCase(Locale.US);
        if (kind.equals("customwmsmapsource")) {
            result.skip(fileName, "WMS map sources aren't supported, only tile (XYZ) sources");
            return;
        }
        if (kind.equals("custommultilayermapsource")) {
            result.skip(fileName, "multi-layer map sources aren't supported");
            return;
        }
        CustomMapSources.Imported s = new CustomMapSources.Imported();
        s.name = text(root, "name");
        if (s.name == null || s.name.isEmpty()) s.name = Importer.stripExtension(fileName);

        String crs = text(root, "coordinatesystem");
        if (crs != null && !crs.isEmpty() && !isWebMercator(crs)) {
            result.skip(fileName, "uses " + crs + "; TAK Lite maps need Web Mercator (EPSG:3857)");
            return;
        }

        String url = text(root, "url");
        if (url == null || url.isEmpty()) {
            result.skip(fileName, "map source has no <url>");
            return;
        }
        boolean invertY = "true".equalsIgnoreCase(text(root, "invertYCoordinate"));
        url = url.replace("{$x}", "{x}")
                .replace("{$y}", invertY ? "{-y}" : "{y}")
                .replace("{$z}", "{z}")
                .replace("{$q}", "{q}");
        if (url.contains("{$serverpart}") || url.contains("{$s}")) {
            String parts = text(root, "serverParts");
            if (parts == null || parts.trim().isEmpty()) {
                result.skip(fileName, "URL uses {$serverpart} but no <serverParts> are listed");
                return;
            }
            String list = "{s:" + parts.trim().replaceAll("[,\\s]+", ",") + "}";
            url = url.replace("{$serverpart}", list).replace("{$s}", list);
        }
        s.url = url;
        s.minZoom = Math.max(0, intOr(text(root, "minZoom"), 0));
        s.maxZoom = Math.min(MAX_ZOOM, intOr(text(root, "maxZoom"), 20));
        if (s.maxZoom < s.minZoom) s.maxZoom = s.minZoom;
        s.tileSize = intOr(text(root, "tileSize"), 0);

        String problem = s.problem();
        if (problem != null) {
            result.skip(fileName, problem);
            return;
        }
        result.mapSources.add(s);
    }

    private static boolean isWebMercator(String crs) {
        String c = crs.toUpperCase(Locale.US).replace(" ", "");
        return c.endsWith(":3857") || c.endsWith(":900913") || c.endsWith(":3785") || c.endsWith(":102100")
                || c.endsWith(":102113") || c.equals("3857");
    }

    /** Text of the first child with this name, ignoring letter case (files differ). */
    private static String text(XmlNode node, String name) {
        for (XmlNode c : node.children) {
            if (c.name.equalsIgnoreCase(name)) return c.text();
        }
        return null;
    }

    private static int intOr(String s, int fallback) {
        try {
            return s == null || s.trim().isEmpty() ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
