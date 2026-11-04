package com.taklite.client.mod;

import android.app.Activity;
import android.view.View;

import org.osmdroid.views.MapView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Small helpers shared by the map source and import code. */
public final class ModUtil {

    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;
    private static final long GB = MB * 1024L;

    /** TAK Lite's own preferences file; the mod keeps its settings there too, under "mod_" / "custom_map_" keys. */
    public static final String PREFS = "TAKLitePrefs";

    private ModUtil() {
    }

    public static android.content.SharedPreferences prefs(android.content.Context context) {
        return context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    /** TAK Lite's main map (layout id "mapView"), or null if this activity doesn't show it. */
    public static MapView mapView(Activity activity) {
        int id = activity.getResources().getIdentifier("mapView", "id", activity.getPackageName());
        View v = id == 0 ? null : activity.findViewById(id);
        return v instanceof MapView ? (MapView) v : null;
    }

    /**
     * Copies {@code in} to {@code out}, failing with {@code tooLarge} once more than
     * {@code maxBytes} have been read. Returns the number of bytes copied.
     */
    public static long copy(InputStream in, OutputStream out, long maxBytes, String tooLarge) throws IOException {
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) throw new IOException(tooLarge);
            out.write(buf, 0, n);
        }
        return total;
    }

    /** Reads and closes {@code in}; see {@link #copy}. */
    public static byte[] readAll(InputStream in, int maxBytes, String tooLarge) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(input, out, maxBytes, tooLarge);
            return out.toByteArray();
        }
    }

    /** "850 KB", "4.2 MB", "37 MB", "1.5 GB". */
    public static String formatSize(long bytes) {
        bytes = Math.max(0, bytes);
        if (bytes >= GB) {
            double gb = bytes / (double) GB;
            return gb >= 10 || gb == Math.floor(gb)
                    ? String.format(Locale.US, "%.0f GB", gb)
                    : String.format(Locale.US, "%.1f GB", gb);
        }
        if (bytes >= MB) {
            double mb = bytes / (double) MB;
            return mb >= 10 ? Math.round(mb) + " MB" : String.format(Locale.US, "%.1f MB", mb);
        }
        return (bytes == 0 ? 0 : Math.max(1, bytes / KB)) + " KB";
    }
}
