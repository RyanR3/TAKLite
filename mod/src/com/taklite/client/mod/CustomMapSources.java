package com.taklite.client.mod;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.taklite.client.MainActivity;
import com.taklite.client.mod.imports.Imports;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.util.TileSystem;
import org.osmdroid.views.MapView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * User-defined raster map sources (e.g. TileServer GL) for TAK Lite.
 *
 * The public static methods are called from small hooks patched into the
 * stock app (see mod/patches): MainActivity's map-type cycling, its onCreate
 * and onResume, and SettingsActivity's onCreate. Custom sources take map type
 * indexes after the four built-in ones, so the existing "map_type" preference
 * and the map layers button keep working unchanged.
 */
public final class CustomMapSources {

    private static final String TAG = "CustomMapSources";
    private static final String KEY_SOURCES = "custom_map_sources";
    private static final String KEY_MAP_TYPE = "map_type";
    private static final String KEY_TIP_SHOWN = "custom_map_sources_tip_shown";
    private static final String SETTINGS_BUTTON_TAG = "custom_map_sources_button";

    /** Same order as the stock MainActivity.applyMapType(). */
    private static final String[] BUILT_IN_NAMES = {"Street Map", "Dark", "Satellite", "Topographical"};
    private static final int BUILT_IN_TILE_SIZE = 256;

    static final int DEFAULT_MIN_ZOOM = 0;
    static final int DEFAULT_MAX_ZOOM = 20;
    private static final int MAX_ZOOM_LIMIT = 29;
    private static final int TIMEOUT_MS = 8000;

    /** Sources JSON the main map was last drawn with, so onResume can tell when Settings changed it. */
    private static String appliedSourcesJson;

    private CustomMapSources() {
    }

    static final class Source {
        String name;
        String url;
        int minZoom = DEFAULT_MIN_ZOOM;
        int maxZoom = DEFAULT_MAX_ZOOM;
        int tileSize;

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("name", name)
                    .put("url", url)
                    .put("minZoom", minZoom)
                    .put("maxZoom", maxZoom)
                    .put("tileSize", tileSize);
        }

        static Source fromJson(JSONObject o) {
            Source s = new Source();
            s.name = o.optString("name", "");
            s.url = o.optString("url", "");
            s.minZoom = o.optInt("minZoom", DEFAULT_MIN_ZOOM);
            s.maxZoom = o.optInt("maxZoom", DEFAULT_MAX_ZOOM);
            s.tileSize = o.optInt("tileSize", guessTileSize(s.url));
            return s;
        }

        TemplateTileSource toTileSource() {
            // osmdroid caches tiles by source name, so key the cache on what changes the pixels.
            String cacheName = "custom-" + Integer.toHexString((url + "|" + tileSize).hashCode());
            return new TemplateTileSource(cacheName, minZoom, maxZoom, tileSize, url);
        }
    }

    // ==================== Hooks called from the patched app ====================

    /** MainActivity.cycleMapType(): number of map types to cycle through. */
    public static int getMapTypeCount(Context context) {
        return BUILT_IN_NAMES.length + load(context).size();
    }

    /**
     * Start of MainActivity.applyMapType(). Applies a custom source and returns its
     * name, or returns null to let the stock code handle a built-in map type.
     */
    public static String applyMapType(Context context, MapView mapView, int type) {
        appliedSourcesJson = rawJson(context);
        List<Source> sources = load(context);
        int index = type - BUILT_IN_NAMES.length;
        if (index < 0 || index >= sources.size()) {
            keepScaleForBuiltIn(mapView);
            return null;
        }
        Source source = sources.get(index);
        try {
            double zoom = scaledZoom(mapView, source.tileSize);
            mapView.setTileSource(source.toTileSource());
            mapView.getController().setZoom(zoom);
            return source.name;
        } catch (RuntimeException e) {
            // This runs on every app start; never let a bad source stop the map from opening.
            Log.e(TAG, "Could not apply map source " + source.url, e);
            keepScaleForBuiltIn(mapView);
            return null;
        }
    }

    /**
     * MainActivity.onCreate(), right after osmdroid's Configuration is loaded and before the
     * MapView exists: osmdroid trims its tile cache when the map starts, so the user's cache
     * size has to be in place by then.
     */
    public static void beforeMapCreated(Context context) {
        Imports.init(context);
        try {
            TileCache.apply(context);
            TileCache.trimSoon(context);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not apply tile cache size", e);
        }
    }

    /**
     * End of MainActivity.onCreate(): a tap on the map layers button opens the picker
     * (stock cycled to the next map, which means a lot of tapping once there are many
     * sources); a long-press does the same.
     */
    public static void onMainActivityCreated(final MainActivity activity) {
        Imports.onMainActivityCreated(activity);
        int id = activity.getResources().getIdentifier("mapTypeButton", "id", activity.getPackageName());
        View button = id == 0 ? null : activity.findViewById(id);
        if (button == null) {
            Log.w(TAG, "mapTypeButton not found");
            return;
        }
        // Replaces the stock listener set earlier in onCreate, which called cycleMapType().
        button.setOnClickListener(v -> showPicker(activity));
        button.setOnLongClickListener(v -> {
            showPicker(activity);
            return true;
        });
    }

    /** MainActivity.onResume(): redraw the map if sources were edited in Settings. */
    public static void onMainActivityResumed(MainActivity activity) {
        refreshMainMap(activity);
        TileCache.trimSoon(activity);
        MapOptions.onMainActivityResumed(activity);
        Imports.onMainActivityResumed(activity);
    }

    /** End of SettingsActivity.onCreate(): wire up the "Manage Map Sources" button. */
    public static void onSettingsCreated(final Activity activity) {
        MapOptions.onSettingsCreated(activity);
        View button = activity.getWindow().getDecorView().findViewWithTag(SETTINGS_BUTTON_TAG);
        if (button == null) {
            Log.w(TAG, "Map sources settings button not found");
            return;
        }
        button.setOnClickListener(v -> showManager(activity, null));
    }

    // ==================== Storage ====================

    private static SharedPreferences prefs(Context context) {
        return ModUtil.prefs(context);
    }

    private static String rawJson(Context context) {
        return prefs(context).getString(KEY_SOURCES, "[]");
    }

    static List<Source> load(Context context) {
        List<Source> sources = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(rawJson(context));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o != null) sources.add(Source.fromJson(o));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Bad custom map source list", e);
        }
        return sources;
    }

    static void save(Context context, List<Source> sources) {
        JSONArray array = new JSONArray();
        try {
            for (Source s : sources) array.put(s.toJson());
        } catch (JSONException e) {
            Log.e(TAG, "Could not save custom map sources", e);
            return;
        }
        prefs(context).edit().putString(KEY_SOURCES, array.toString()).apply();
    }

    /** A map source read from an imported file (e.g. an ATAK map source XML). */
    public static final class Imported {
        public String name;
        /** In TAK Lite's template syntax (see {@link TemplateTileSource}). */
        public String url;
        public int minZoom = DEFAULT_MIN_ZOOM;
        public int maxZoom = DEFAULT_MAX_ZOOM;
        /** 0 to guess from the URL. */
        public int tileSize;

        /** Null if usable, else why not. */
        public String problem() {
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                return "tile URL must start with http:// or https://";
            }
            if (!TemplateTileSource.isValidTemplate(url)) return "tile URL has no {z}/{x}/{y} or {q}";
            if (minZoom < 0 || maxZoom > MAX_ZOOM_LIMIT || minZoom > maxZoom) return "bad zoom range";
            if (tileSize != 0 && (tileSize < 64 || tileSize > 1024)) return "bad tile size " + tileSize;
            return null;
        }
    }

    /**
     * Adds imported map sources to the list. One with the same name as an existing source
     * replaces it (a re-sent map pack updates its sources); one whose URL is already
     * listed under another name is left out. Returns {added, updated}.
     */
    public static synchronized int[] addImported(Context context, List<Imported> imported) {
        List<Source> sources = load(context);
        int added = 0;
        int updated = 0;
        for (Imported in : imported) {
            Source s = new Source();
            s.name = in.name == null || in.name.trim().isEmpty() ? hostOf(in.url) : in.name.trim();
            s.url = in.url;
            s.minZoom = in.minZoom;
            s.maxZoom = in.maxZoom;
            s.tileSize = in.tileSize > 0 ? in.tileSize : guessTileSize(in.url);
            int sameName = -1;
            for (int i = 0; i < sources.size(); i++) {
                if (sources.get(i).name.equalsIgnoreCase(s.name)) sameName = i;
            }
            if (sameName >= 0) {
                Source old = sources.get(sameName);
                if (!old.url.equals(s.url) || old.minZoom != s.minZoom || old.maxZoom != s.maxZoom
                        || old.tileSize != s.tileSize) {
                    sources.set(sameName, s);
                    updated++;
                }
            } else if (!containsUrl(sources, s.url)) {
                sources.add(s);
                added++;
            }
        }
        if (added + updated > 0) save(context, sources);
        Log.i(TAG, "Imported map sources: " + added + " added, " + updated + " updated, "
                + (imported.size() - added - updated) + " already listed");
        return new int[]{added, updated};
    }

    /** Removes a source and keeps the selected map type pointing at the same map. */
    private static void delete(Context context, List<Source> sources, int index) {
        sources.remove(index);
        save(context, sources);
        int selected = prefs(context).getInt(KEY_MAP_TYPE, 0) - BUILT_IN_NAMES.length;
        if (selected == index) {
            prefs(context).edit().putInt(KEY_MAP_TYPE, 0).apply();
        } else if (selected > index) {
            prefs(context).edit().putInt(KEY_MAP_TYPE, selected - 1 + BUILT_IN_NAMES.length).apply();
        }
    }

    // ==================== Map helpers ====================

    private static void refreshMainMap(MainActivity activity) {
        if (rawJson(activity).equals(appliedSourcesJson)) return;
        int type = prefs(activity).getInt(KEY_MAP_TYPE, 0);
        if (type >= getMapTypeCount(activity)) {
            activity.modSetMapType(0, true);
        } else {
            activity.modSetMapType(type, false);
        }
    }

    /**
     * osmdroid's zoom number is relative to the tile size, so a 512 px source at zoom z
     * looks like a 256 px source at z + 1. Adjust the zoom so switching sources keeps
     * the same scale on screen.
     */
    private static double scaledZoom(MapView mapView, int newTileSize) {
        int oldTileSize = TileSystem.getTileSize();
        double zoom = mapView.getZoomLevelDouble();
        if (oldTileSize <= 0 || newTileSize <= 0 || oldTileSize == newTileSize) return zoom;
        return zoom + Math.log((double) oldTileSize / newTileSize) / Math.log(2);
    }

    /**
     * Leaving a 512 px custom source for a built-in (256 px) map needs one zoom level more
     * for the same scale. The stock code sets the built-in source after this hook returns,
     * and setting the zoom now would clamp it to the custom source's max zoom, so set it
     * once the stock code is done.
     */
    private static void keepScaleForBuiltIn(final MapView mapView) {
        if (TileSystem.getTileSize() == BUILT_IN_TILE_SIZE) return;
        final double zoom = scaledZoom(mapView, BUILT_IN_TILE_SIZE);
        mapView.post(() -> mapView.getController().setZoom(zoom));
    }

    static int guessTileSize(String url) {
        return url != null && (url.contains("/512/") || url.contains("@2x")) ? 512 : 256;
    }

    // ==================== UI ====================

    /** Picker on the map layers button, listing every map type, built-in and custom. */
    static void showPicker(final MainActivity activity) {
        final DarkDialog d = new DarkDialog(activity, "Map Source");
        int selected = prefs(activity).getInt(KEY_MAP_TYPE, 0);
        List<Source> sources = load(activity);
        int count = BUILT_IN_NAMES.length + sources.size();
        for (int i = 0; i < count; i++) {
            final int type = i;
            boolean custom = i >= BUILT_IN_NAMES.length;
            Source source = custom ? sources.get(i - BUILT_IN_NAMES.length) : null;
            String name = custom ? source.name : BUILT_IN_NAMES[i];
            String subtitle = custom ? hostOf(source.url) : null;
            d.addRow(i == selected ? name + "  ✓" : name, subtitle,
                    i == selected ? DarkDialog.COLOR_ACCENT : Color.WHITE, v -> {
                        d.dismiss();
                        activity.modSetMapType(type, true);
                    });
        }

        d.addDivider();
        int selectedIndex = selected - BUILT_IN_NAMES.length;
        final MapView mapView = ModUtil.mapView(activity);
        if (selectedIndex >= 0 && selectedIndex < sources.size() && mapView != null) {
            final Source source = sources.get(selectedIndex);
            d.addButton("Download visible area for offline", DarkDialog.COLOR_ACCENT, v -> {
                d.dismiss();
                AreaDownloader.show(activity, mapView, source);
            });
        } else {
            d.addText(sources.isEmpty()
                    ? "Add your own map source to download maps for offline use."
                    : "Switch to one of your own map sources to download it for offline use. "
                    + "(The built-in maps' providers don't allow bulk downloads.)");
        }

        d.addAction("MANAGE SOURCES", DarkDialog.COLOR_ACCENT, v -> {
            d.dismiss();
            showManager(activity, () -> refreshMainMap(activity));
        });
        d.addActionSpacer();
        d.addAction("CLOSE", DarkDialog.COLOR_LABEL, v -> d.dismiss());
        d.show();
    }

    /** Lists custom sources with add / import / edit. */
    static void showManager(final Context context, final Runnable onClose) {
        final DarkDialog d = new DarkDialog(context, "Custom Map Sources");
        // Cleared before switching to a sub-dialog, which reopens the manager when done,
        // so onClose runs only when the user actually leaves.
        d.dialog.setOnDismissListener(dialog -> {
            if (onClose != null) onClose.run();
        });

        d.addText("Raster XYZ tile servers such as TileServer GL. Use {z}, {x} and {y} in the "
                + "URL, e.g. https://tiles.example.com/styles/mapName/512/{z}/{x}/{y}.png");

        final List<Source> sources = load(context);
        if (sources.isEmpty()) {
            d.addLabel("No custom map sources yet.");
        }
        for (int i = 0; i < sources.size(); i++) {
            final int index = i;
            Source s = sources.get(i);
            d.addRow(s.name, s.url, Color.WHITE, v -> {
                d.dialog.setOnDismissListener(null);
                d.dismiss();
                showEditor(context, index, () -> showManager(context, onClose));
            });
        }
        d.addDivider();
        d.addButton("Add Map Source", DarkDialog.COLOR_ACCENT, v -> {
            d.dialog.setOnDismissListener(null);
            d.dismiss();
            showEditor(context, -1, () -> showManager(context, onClose));
        });
        d.addButton("Import from TileServer GL", DarkDialog.COLOR_FIELD, v -> {
            d.dialog.setOnDismissListener(null);
            d.dismiss();
            showImport(context, () -> showManager(context, onClose));
        });

        d.addDivider();
        d.addLabel("Offline cache");
        d.addText(TileCache.summary(context) + " To save an area ahead of time, tap the "
                + "map layers button and choose \"Download visible area\".");
        d.addButton("Cache size: " + ModUtil.formatSize(TileCache.limitMb(context) * 1024L * 1024L),
                DarkDialog.COLOR_FIELD, v -> {
                    d.dialog.setOnDismissListener(null);
                    d.dismiss();
                    TileCache.showLimitPicker(context, () -> showManager(context, onClose));
                });
        d.addButton("Clear Cache", DarkDialog.COLOR_DANGER, v -> {
            d.dialog.setOnDismissListener(null);
            d.dismiss();
            TileCache.confirmClear(context, () -> showManager(context, onClose));
        });
        d.addAction("CLOSE", DarkDialog.COLOR_LABEL, v -> d.dismiss());
        d.show();
    }

    /** Add (index -1) or edit a source. {@code next} runs when the editor closes. */
    static void showEditor(final Context context, final int index, final Runnable next) {
        final List<Source> sources = load(context);
        final Source existing = index >= 0 && index < sources.size() ? sources.get(index) : null;
        final DarkDialog d = new DarkDialog(context, existing == null ? "Add Map Source" : "Edit Map Source");
        d.dialog.setOnDismissListener(dialog -> next.run());

        final EditText name = d.addField("Name", "My TileServer map",
                existing == null ? null : existing.name, InputType.TYPE_CLASS_TEXT);
        final EditText url = d.addField("Tile URL", "https://tiles.example.com/styles/mapName/512/{z}/{x}/{y}.png",
                existing == null ? null : existing.url,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final EditText minZoom = d.addField("Min zoom", String.valueOf(DEFAULT_MIN_ZOOM),
                existing == null ? null : String.valueOf(existing.minZoom), InputType.TYPE_CLASS_NUMBER);
        final EditText maxZoom = d.addField("Max zoom", String.valueOf(DEFAULT_MAX_ZOOM),
                existing == null ? null : String.valueOf(existing.maxZoom), InputType.TYPE_CLASS_NUMBER);
        final EditText tileSize = d.addField("Tile size (px)", "Auto: 512 if URL has /512/, else 256",
                existing == null ? null : String.valueOf(existing.tileSize), InputType.TYPE_CLASS_NUMBER);
        final TextView status = d.addLabel("");
        status.setVisibility(View.GONE);

        if (existing != null) {
            d.addAction("DELETE", DarkDialog.COLOR_DANGER, v -> {
                delete(context, sources, index);
                d.dismiss();
            });
            d.addActionSpacer();
        }
        d.addAction("TEST", DarkDialog.COLOR_LABEL, v -> {
            Source s = new Source();
            String error = readForm(s, name, url, minZoom, maxZoom, tileSize);
            status.setVisibility(View.VISIBLE);
            if (error != null) {
                status.setTextColor(DarkDialog.COLOR_DANGER);
                status.setText(error);
                return;
            }
            status.setTextColor(DarkDialog.COLOR_LABEL);
            status.setText("Testing…");
            testTile(context, s, status);
        });
        d.addAction("CANCEL", DarkDialog.COLOR_LABEL, v -> d.dismiss());
        d.addAction("SAVE", Color.WHITE, v -> {
            Source s = new Source();
            String error = readForm(s, name, url, minZoom, maxZoom, tileSize);
            if (error != null) {
                status.setVisibility(View.VISIBLE);
                status.setTextColor(DarkDialog.COLOR_DANGER);
                status.setText(error);
                return;
            }
            if (existing == null) {
                sources.add(s);
            } else {
                sources.set(index, s);
            }
            save(context, sources);
            showTipOnce(context);
            d.dismiss();
        });
        d.show();
    }

    /** Fetches /styles.json from a TileServer GL host and adds the chosen styles. */
    static void showImport(final Context context, final Runnable next) {
        final DarkDialog d = new DarkDialog(context, "Import from TileServer GL");
        d.dialog.setOnDismissListener(dialog -> next.run());

        d.addText("Enter the TileServer GL address. Its styles are listed from /styles.json "
                + "and added as raster map sources.");
        final EditText server = d.addField("Server", "http://192.168.1.10:8080", null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final CheckBox large = new CheckBox(context);
        large.setText("512 px tiles (sharper text, recommended)");
        large.setTextColor(Color.WHITE);
        large.setButtonTintList(ColorStateList.valueOf(DarkDialog.COLOR_ACCENT));
        large.setChecked(true);
        d.content.addView(large, DarkDialog.matchWrap());
        final TextView status = d.addLabel("");
        status.setVisibility(View.GONE);
        final LinearLayout styleList = new LinearLayout(context);
        styleList.setOrientation(LinearLayout.VERTICAL);
        d.content.addView(styleList, DarkDialog.matchWrap());

        final List<CheckBox> boxes = new ArrayList<>();
        final List<String[]> styles = new ArrayList<>(); // {id, name}
        final String[] base = new String[1];
        final Handler main = new Handler(Looper.getMainLooper());

        d.addAction("FETCH STYLES", DarkDialog.COLOR_ACCENT, v -> {
            final String baseUrl = normalizeServer(server.getText().toString());
            if (baseUrl == null) {
                status.setVisibility(View.VISIBLE);
                status.setTextColor(DarkDialog.COLOR_DANGER);
                status.setText("Enter the server address, e.g. http://192.168.1.10:8080");
                return;
            }
            status.setVisibility(View.VISIBLE);
            status.setTextColor(DarkDialog.COLOR_LABEL);
            status.setText("Fetching " + baseUrl + "/styles.json…");
            styleList.removeAllViews();
            boxes.clear();
            styles.clear();
            new Thread(() -> {
                String error = null;
                List<String[]> found = new ArrayList<>();
                try {
                    JSONArray array = new JSONArray(new String(
                            httpGet(context, baseUrl + "/styles.json", 2_000_000), "UTF-8"));
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject o = array.getJSONObject(i);
                        String id = o.optString("id", "");
                        if (!id.isEmpty()) found.add(new String[]{id, o.optString("name", id)});
                    }
                    if (found.isEmpty()) error = "The server has no styles.";
                } catch (JSONException e) {
                    error = "Not a TileServer GL styles list (" + e.getMessage() + ")";
                } catch (IOException e) {
                    error = "Could not reach server: " + e.getMessage();
                }
                final String finalError = error;
                main.post(() -> {
                    if (!d.dialog.isShowing()) return;
                    if (finalError != null) {
                        status.setTextColor(DarkDialog.COLOR_DANGER);
                        status.setText(finalError);
                        return;
                    }
                    base[0] = baseUrl;
                    status.setTextColor(DarkDialog.COLOR_LABEL);
                    status.setText("Select styles to add:");
                    for (String[] style : found) {
                        CheckBox box = new CheckBox(context);
                        box.setText(style[1].equals(style[0]) ? style[1] : style[1] + " (" + style[0] + ")");
                        box.setTextColor(Color.WHITE);
                        box.setButtonTintList(ColorStateList.valueOf(DarkDialog.COLOR_ACCENT));
                        box.setChecked(true);
                        styleList.addView(box, DarkDialog.matchWrap());
                        boxes.add(box);
                        styles.add(style);
                    }
                });
            }).start();
        });
        d.addActionSpacer();
        d.addAction("CANCEL", DarkDialog.COLOR_LABEL, v -> d.dismiss());
        d.addAction("ADD", Color.WHITE, v -> {
            if (base[0] == null || boxes.isEmpty()) {
                status.setVisibility(View.VISIBLE);
                status.setTextColor(DarkDialog.COLOR_DANGER);
                status.setText("Fetch the server's styles first.");
                return;
            }
            List<Source> sources = load(context);
            int added = 0;
            for (int i = 0; i < boxes.size(); i++) {
                if (!boxes.get(i).isChecked()) continue;
                Source s = new Source();
                s.name = styles.get(i)[1];
                s.tileSize = large.isChecked() ? 512 : 256;
                s.url = base[0] + "/styles/" + encodePathSegment(styles.get(i)[0])
                        + (large.isChecked() ? "/512" : "") + "/{z}/{x}/{y}.png";
                if (containsUrl(sources, s.url)) continue;
                sources.add(s);
                added++;
            }
            save(context, sources);
            Toast.makeText(context, "Added " + added + " map source" + (added == 1 ? "" : "s"),
                    Toast.LENGTH_SHORT).show();
            if (added > 0) showTipOnce(context);
            d.dismiss();
        });
        d.show();
    }

    // ==================== Form / network helpers ====================

    /** Fills {@code out} from the editor fields. Returns an error message, or null if valid. */
    private static String readForm(Source out, EditText name, EditText url, EditText minZoom,
                                   EditText maxZoom, EditText tileSize) {
        out.url = url.getText().toString().trim();
        if (!out.url.startsWith("http://") && !out.url.startsWith("https://")) {
            return "URL must start with http:// or https://";
        }
        if (!TemplateTileSource.isValidTemplate(out.url)) {
            return "URL must contain {z}, {x} and {y} (or a {q} quadkey)";
        }
        out.name = name.getText().toString().trim();
        if (out.name.isEmpty()) out.name = hostOf(out.url);
        try {
            out.minZoom = parseInt(minZoom, DEFAULT_MIN_ZOOM);
            out.maxZoom = parseInt(maxZoom, DEFAULT_MAX_ZOOM);
            out.tileSize = parseInt(tileSize, guessTileSize(out.url));
        } catch (NumberFormatException e) {
            return "Zoom levels and tile size must be numbers";
        }
        if (out.minZoom < 0 || out.maxZoom > MAX_ZOOM_LIMIT || out.minZoom > out.maxZoom) {
            return "Zoom levels must be between 0 and " + MAX_ZOOM_LIMIT + ", min ≤ max";
        }
        if (out.tileSize < 64 || out.tileSize > 1024) {
            return "Tile size must be between 64 and 1024 (usually 256 or 512)";
        }
        return null;
    }

    private static int parseInt(EditText field, int fallback) {
        String text = field.getText().toString().trim();
        return text.isEmpty() ? fallback : Integer.parseInt(text);
    }

    private static void testTile(final Context context, final Source source, final TextView status) {
        final String tileUrl = TemplateTileSource.expand(source.url, source.minZoom, 0, 0);
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String message;
            boolean ok = false;
            HttpURLConnection conn = null;
            try {
                conn = open(context, tileUrl);
                int code = conn.getResponseCode();
                String type = conn.getContentType();
                if (code == 200 && type != null && type.startsWith("image/")) {
                    byte[] body = ModUtil.readAll(conn.getInputStream(), 10_000_000, "Response too large");
                    message = "OK: " + type + ", " + Math.max(1, body.length / 1024) + " KB";
                    ok = true;
                } else {
                    message = "Server answered HTTP " + code + (type == null ? "" : " (" + type + ")");
                }
            } catch (IOException e) {
                message = "Could not load tile: " + e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String finalMessage = message + "\n" + tileUrl;
            final boolean finalOk = ok;
            main.post(() -> {
                status.setTextColor(finalOk ? DarkDialog.COLOR_ACCENT : DarkDialog.COLOR_DANGER);
                status.setText(finalMessage);
            });
        }).start();
    }

    private static HttpURLConnection open(Context context, String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", context.getPackageName());
        return conn;
    }

    private static byte[] httpGet(Context context, String url, int maxBytes) throws IOException {
        HttpURLConnection conn = open(context, url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " from " + url);
            return ModUtil.readAll(conn.getInputStream(), maxBytes, "Response too large");
        } finally {
            conn.disconnect();
        }
    }

    /** "192.168.1.10:8080/" or "http://host/styles.json" -> "http://192.168.1.10:8080" / "http://host". */
    static String normalizeServer(String input) {
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        if (s.endsWith("/styles.json")) s = s.substring(0, s.length() - "/styles.json".length());
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String encodePathSegment(String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (IOException e) {
            return segment;
        }
    }

    private static boolean containsUrl(List<Source> sources, String url) {
        for (Source s : sources) {
            if (s.url.equals(url)) return true;
        }
        return false;
    }

    private static String hostOf(String url) {
        String host = Uri.parse(url).getHost();
        return TextUtils.isEmpty(host) ? url : host;
    }

    private static void showTipOnce(Context context) {
        if (context instanceof MainActivity || prefs(context).getBoolean(KEY_TIP_SHOWN, false)) return;
        prefs(context).edit().putBoolean(KEY_TIP_SHOWN, true).apply();
        Toast.makeText(context, "Tip: on the map, tap the layers button to pick a map.", Toast.LENGTH_LONG).show();
    }
}
