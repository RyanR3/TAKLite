package com.taklite.client.mod;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

/**
 * Raster XYZ tile source built from a URL template such as
 * {@code https://host/styles/basic/512/{z}/{x}/{y}.png} (TileServer GL).
 *
 * Supported placeholders: {z}, {x}, {y}, {-y} (TMS row order), {q} (Bing-style
 * quadkey) and {s:a,b,c} (one of the listed server names, spread across tiles).
 */
final class TemplateTileSource extends OnlineTileSourceBase {

    private final String template;

    TemplateTileSource(String cacheName, int minZoom, int maxZoom, int tileSize, String template) {
        super(cacheName, minZoom, maxZoom, tileSize, extensionOf(template), new String[]{template});
        this.template = template;
    }

    @Override
    public String getTileURLString(long index) {
        return expand(template, MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index));
    }

    private static final java.util.regex.Pattern SERVERS = java.util.regex.Pattern.compile("\\{s:([^}]*)\\}");

    static String expand(String template, int z, int x, int y) {
        String url = template
                .replace("{z}", Integer.toString(z))
                .replace("{x}", Integer.toString(x))
                .replace("{-y}", Integer.toString((1 << z) - 1 - y))
                .replace("{y}", Integer.toString(y));
        if (url.contains("{q}")) url = url.replace("{q}", quadKey(z, x, y));
        if (url.contains("{s:")) {
            java.util.regex.Matcher m = SERVERS.matcher(url);
            if (m.find()) {
                String[] parts = m.group(1).trim().split("[,\\s]+");
                String part = parts[Math.floorMod(x + y, parts.length)];
                url = url.substring(0, m.start()) + part + url.substring(m.end());
            }
        }
        return url;
    }

    /** Whether {@code template} names a tile: {z}, {x} and {y} (or {-y}), or a {q} quadkey. */
    static boolean isValidTemplate(String template) {
        if (template.contains("{q}")) return true;
        return template.contains("{z}") && template.contains("{x}")
                && (template.contains("{y}") || template.contains("{-y}"));
    }

    static String quadKey(int z, int x, int y) {
        StringBuilder key = new StringBuilder(z);
        for (int i = z; i > 0; i--) {
            int mask = 1 << (i - 1);
            int digit = ((x & mask) != 0 ? 1 : 0) + ((y & mask) != 0 ? 2 : 0);
            key.append(digit);
        }
        return key.toString();
    }

    /** File extension osmdroid uses when caching tiles on disk. */
    private static String extensionOf(String template) {
        String path = template;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash && path.length() - dot <= 5) return path.substring(dot);
        return ".png";
    }
}
