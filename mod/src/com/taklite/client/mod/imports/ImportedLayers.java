package com.taklite.client.mod.imports;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.taklite.client.MainActivity;
import com.taklite.client.mod.DarkDialog;
import com.taklite.client.mod.ModUtil;
import com.taklite.client.mod.RemoteMarkers;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.FolderOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Draws imported data on the main map, and the dialogs to inspect and manage it. */
final class ImportedLayers {

    private static final String TAG = "ImportedLayers";

    private static WeakReference<MainActivity> activityRef = new WeakReference<>(null);
    /**
     * The drawn layer of each visible import, by import id. An import's items never change
     * (a re-import gets a new id), so a reload only builds layers for newly shown imports.
     */
    private static Map<String, FolderOverlay> layers = new LinkedHashMap<>();
    private static int drawnVersion = -1;
    private static int loadGeneration;

    private ImportedLayers() {
    }

    static void attach(MainActivity activity) {
        activityRef = new WeakReference<>(activity);
        layers = new LinkedHashMap<>();
        drawnVersion = -1;
        reload();
    }

    static void onResume(MainActivity activity) {
        activityRef = new WeakReference<>(activity);
        if (drawnVersion != Imports.version()) reload();
    }

    /** TAK Lite's map screen if it is showing, for toasts. */
    static Activity visibleActivity() {
        MainActivity a = activityRef.get();
        return a != null && a.hasWindowFocus() ? a : null;
    }

    private static MapView mapView(Activity activity) {
        return ModUtil.mapView(activity);
    }

    /**
     * Brings the map in line with the imports: removes hidden or deleted ones and draws
     * newly shown ones, reading their items and making their dot icons off the main thread.
     * Main thread.
     */
    static void reload() {
        final MainActivity activity = activityRef.get();
        if (activity == null || Imports.store() == null) return;
        final int version = Imports.version();
        final int generation = ++loadGeneration;
        final Set<String> drawn = new HashSet<>(layers.keySet());
        new Thread(() -> {
            List<ImportStore.Entry> entries = new ArrayList<>();
            // Per entry: null if already drawn, else its items and their pre-made icons.
            List<List<Feature>> features = new ArrayList<>();
            List<Drawable[]> dotIcons = new ArrayList<>();
            for (ImportStore.Entry e : Imports.store().list()) {
                if (!e.visible) continue;
                if (drawn.contains(e.id)) {
                    entries.add(e);
                    features.add(null);
                    dotIcons.add(null);
                    continue;
                }
                try {
                    List<Feature> items = Imports.store().features(e.id);
                    Drawable[] icons = new Drawable[items.size()];
                    for (int i = 0; i < items.size(); i++) {
                        Feature f = items.get(i);
                        if (f.kind == Feature.POINT && !usesTakIcon(f)) icons[i] = dotIcon(activity, label(f), f.pointColor);
                    }
                    entries.add(e);
                    features.add(items);
                    dotIcons.add(icons);
                } catch (IOException | OutOfMemoryError ex) {
                    Log.e(TAG, "Could not load import " + e.name, ex);
                }
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                if (generation != loadGeneration || activity.isFinishing()) return;
                MapView map = mapView(activity);
                if (map == null) return;
                map.getOverlays().removeAll(layers.values());
                Map<String, FolderOverlay> next = new LinkedHashMap<>();
                // Just above the map's tap handler (index 0), below markers and teammates.
                int index = Math.min(1, map.getOverlays().size());
                for (int i = 0; i < entries.size(); i++) {
                    ImportStore.Entry e = entries.get(i);
                    FolderOverlay folder = features.get(i) == null ? layers.get(e.id) : null;
                    if (folder == null) {
                        if (features.get(i) == null) continue; // drawn layer went away meanwhile
                        folder = build(activity, map, e, features.get(i), dotIcons.get(i));
                    }
                    map.getOverlays().add(index++, folder);
                    next.put(e.id, folder);
                }
                layers = next;
                drawnVersion = version;
                map.invalidate();
            });
        }, "ImportedLayersLoad").start();
    }

    private static FolderOverlay build(MainActivity activity, MapView map, ImportStore.Entry entry,
                                       List<Feature> features, Drawable[] dotIcons) {
        FolderOverlay folder = new FolderOverlay();
        float density = activity.getResources().getDisplayMetrics().density;
        // Lines and areas first so markers draw on top of them.
        for (Feature f : features) {
            if (f.kind == Feature.POINT) continue;
            List<GeoPoint> pts = new ArrayList<>(f.pointCount());
            for (int i = 0; i < f.pointCount(); i++) pts.add(new GeoPoint(f.lat(i), f.lon(i)));
            if (f.kind == Feature.LINE) {
                Polyline line = new Polyline(map);
                line.setPoints(pts);
                line.getOutlinePaint().setColor(f.strokeColor);
                line.getOutlinePaint().setStrokeWidth(Math.max(1f, f.strokeWidth) * density);
                line.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
                line.setInfoWindow(null);
                line.setOnClickListener((p, mv, at) -> {
                    showInfo(activity, entry, f);
                    return true;
                });
                folder.add(line);
            } else {
                Polygon area = new Polygon(map);
                area.setPoints(pts);
                area.getFillPaint().setColor(f.fillColor);
                area.getOutlinePaint().setColor(f.strokeColor);
                area.getOutlinePaint().setStrokeWidth(Math.max(1f, f.strokeWidth) * density);
                area.setInfoWindow(null);
                area.setOnClickListener((p, mv, at) -> {
                    showInfo(activity, entry, f);
                    return true;
                });
                folder.add(area);
            }
        }
        for (int i = 0; i < features.size(); i++) {
            final Feature f = features.get(i);
            if (f.kind != Feature.POINT) continue;
            Marker marker = new Marker(map);
            marker.setPosition(new GeoPoint(f.lat(0), f.lon(0)));
            marker.setIcon(dotIcons[i] != null ? dotIcons[i] : takIcon(activity, f));
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            marker.setTitle(f.name);
            marker.setInfoWindow(null);
            marker.setOnMarkerClickListener((m, mv) -> {
                showInfo(activity, entry, f);
                return true;
            });
            folder.add(marker);
        }
        return folder;
    }

    /** CoT units use TAK Lite's own MIL-STD-2525 / iconset symbols; other points get a dot. */
    private static boolean usesTakIcon(Feature f) {
        return f.iconsetPath != null || (f.cotType != null && f.cotType.startsWith("a-"));
    }

    private static String label(Feature f) {
        return f.image != null ? "📷 " + f.name : f.name;
    }

    /** TAK Lite's own symbol for a CoT unit (main thread: it uses TAK Lite's icon code). */
    private static Drawable takIcon(MainActivity activity, Feature f) {
        try {
            String iconset = f.iconsetPath != null ? RemoteMarkers.iconsetOrStandIn(f.iconsetPath)
                    : RemoteMarkers.STAND_IN_ICONSET;
            Drawable d = activity.modMarkerIcon(label(f), f.cotType == null ? "" : f.cotType, iconset);
            if (d != null) return d;
        } catch (RuntimeException e) {
            Log.w(TAG, "TAK Lite icon failed for " + f.cotType, e);
        }
        return dotIcon(activity, label(f), f.pointColor);
    }

    /** Coloured dot with a label underneath, in the style of TAK Lite's teammate markers. */
    private static Drawable dotIcon(Context context, String label, int color) {
        float d = context.getResources().getDisplayMetrics().density;
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(10 * d);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        Paint.FontMetrics fm = text.getFontMetrics();
        int pad = Math.round(3 * d);
        int dot = Math.round(14 * d);
        boolean hasLabel = label != null && !label.isEmpty();
        int labelW = hasLabel ? Math.round(text.measureText(label)) + pad * 2 : 0;
        int labelH = hasLabel ? Math.round(fm.descent - fm.ascent) + pad : 0;
        int w = Math.max(dot, labelW);
        int h = dot + (hasLabel ? pad + labelH : 0);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color | 0xFF000000);
        c.drawCircle(w / 2f, dot / 2f, dot / 2f - d, fill);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.5f * d);
        ring.setColor(0xFF000000);
        c.drawCircle(w / 2f, dot / 2f, dot / 2f - d, ring);
        if (hasLabel) {
            float top = dot + pad;
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(Color.argb(140, 0, 0, 0));
            float left = (w - labelW) / 2f;
            c.drawRoundRect(left, top, left + labelW, top + labelH, pad, pad, bg);
            c.drawText(label, left + pad, top + pad / 2f - fm.ascent, text);
        }
        return new BitmapDrawable(context.getResources(), bmp);
    }

    // ==================== Dialogs ====================

    private static void showInfo(MainActivity activity, ImportStore.Entry entry, Feature f) {
        DarkDialog d = new DarkDialog(activity, f.name.isEmpty() ? "Untitled" : f.name);
        if (f.image != null) {
            Bitmap photo = decodeSampled(new File(Imports.store().dir(entry.id), f.image), 1200);
            if (photo != null) {
                ImageView image = new ImageView(activity);
                image.setImageBitmap(photo);
                image.setAdjustViewBounds(true);
                LinearLayout.LayoutParams lp = DarkDialog.matchWrap();
                lp.bottomMargin = d.dp(12);
                d.content.addView(image, lp);
            }
        }
        d.addLabel(describe(f));
        if (!f.remarks.isEmpty()) d.addText(f.remarks);
        if (f.kind == Feature.POINT) {
            d.addText(String.format(Locale.US, "%.5f, %.5f", f.lat(0), f.lon(0)));
        }
        d.addLabel("From \"" + entry.name + "\" (" + entry.origin + ")");
        d.addAction("CLOSE", DarkDialog.COLOR_LABEL, v -> d.dismiss());
        d.show();
    }

    private static String describe(Feature f) {
        if (f.kind == Feature.POINT) {
            return f.cotType != null && !f.cotType.isEmpty() ? "Point · " + f.cotType : "Point";
        }
        double meters = 0;
        for (int i = 1; i < f.pointCount(); i++) {
            meters += new GeoPoint(f.lat(i - 1), f.lon(i - 1)).distanceToAsDouble(new GeoPoint(f.lat(i), f.lon(i)));
        }
        String length = meters >= 1000
                ? String.format(Locale.US, "%.2f km (%.2f mi)", meters / 1000, meters / 1609.344)
                : String.format(Locale.US, "%.0f m (%.0f ft)", meters, meters * 3.28084);
        return (f.kind == Feature.LINE ? "Line · " : "Area · perimeter ") + length;
    }

    private static Bitmap decodeSampled(File file, int maxSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), opts);
    }

    /** The Data Packages menu (toolbar button): imports with show/hide eyes, server, file. */
    static void showManager(final Context context) {
        final DarkDialog d = new DarkDialog(context, "Data Packages");
        List<ImportStore.Entry> entries = Imports.list();
        if (entries.isEmpty()) {
            d.addText("Nothing imported yet. Data packages sent to you over TAK appear here "
                    + "automatically; you can also download them from the server or import a file.");
        }
        boolean anyOnMap = false;
        for (ImportStore.Entry e : entries) anyOnMap |= e.count > 0;
        if (anyOnMap) d.addText("Tap the eye to show or hide a package on the map.");
        for (final ImportStore.Entry e : entries) {
            addEntryRow(context, d, e);
        }
        d.addDivider();
        d.addButton("Download from Server", DarkDialog.COLOR_ACCENT, v -> {
            d.dismiss();
            showServerPackages(context);
        });
        d.addButton("Import File (KML, KMZ, GPX, CoT, .zip)", DarkDialog.COLOR_FIELD, v -> {
            d.dismiss();
            context.startActivity(new Intent(context, ImportActivity.class));
        });
        d.addAction("CLOSE", DarkDialog.COLOR_LABEL, v -> d.dismiss());
        d.show();
    }

    /**
     * One package: the eye (show/hide on the map, as in TAK Aware) at the left, then the name,
     * where it came from and what it holds; tapping the text opens zoom / show / delete. The
     * delete button stays apart at the right.
     */
    private static void addEntryRow(final Context context, final DarkDialog d, final ImportStore.Entry e) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        final ImageView eye = new ImageView(context);
        eye.setScaleType(ImageView.ScaleType.CENTER);
        eye.setBackgroundResource(themeAttr(context, android.R.attr.selectableItemBackgroundBorderless));

        String date = DateFormat.getMediumDateFormat(context).format(new Date(e.importedAt));
        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(d.dp(4), d.dp(10), d.dp(8), d.dp(10));
        text.setBackgroundResource(themeAttr(context, android.R.attr.selectableItemBackground));
        text.setOnClickListener(v -> {
            d.dismiss();
            showEntry(context, e);
        });
        TextView name = rowText(context, e.name, 15, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(name, DarkDialog.matchWrap());
        String origin = ImportStore.Entry.originText(e.origin);
        if (!origin.isEmpty()) text.addView(rowText(context, origin, 12, DarkDialog.COLOR_SECONDARY), DarkDialog.matchWrap());
        final TextView contents = rowText(context, e.contents() + " \u00b7 " + date, 12, DarkDialog.COLOR_LABEL);
        text.addView(contents, DarkDialog.matchWrap());

        // Only map sources: nothing on the map to show or hide.
        if (e.count == 0) {
            eye.setVisibility(View.INVISIBLE);
        } else {
            showEyeState(eye, contents, e);
            eye.setOnClickListener(v -> {
                if (setVisible(context, e, !e.visible)) showEyeState(eye, contents, e);
            });
        }
        row.addView(eye, new LinearLayout.LayoutParams(d.dp(48), d.dp(48)));
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ImageButton delete = new android.widget.ImageButton(context);
        delete.setImageResource(android.R.drawable.ic_menu_delete);
        delete.setImageTintList(android.content.res.ColorStateList.valueOf(DarkDialog.COLOR_DANGER));
        delete.setBackgroundColor(Color.TRANSPARENT);
        delete.setContentDescription("Delete " + e.name);
        delete.setOnClickListener(v -> {
            d.dismiss();
            confirmDelete(context, e, () -> showManager(context));
        });
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(d.dp(48), d.dp(48));
        deleteLp.leftMargin = d.dp(8);
        row.addView(delete, deleteLp);
        d.content.addView(row, DarkDialog.matchWrap());
    }

    /** Bright open eye when shown, dim slashed eye when hidden; the name stays readable either way. */
    private static void showEyeState(ImageView eye, TextView contents, ImportStore.Entry e) {
        eye.setImageDrawable(new EyeIcon(eye.getResources().getDisplayMetrics().density, e.visible));
        eye.setContentDescription((e.visible ? "Hide from map: " : "Show on map: ") + e.name);
        contents.setAlpha(e.visible ? 1f : 0.6f);
    }

    /**
     * Shows or hides a package's items on the map; nothing is deleted and its map sources are
     * untouched. Logged under DataPackages so a test run can be checked. False if it couldn't save.
     */
    private static boolean setVisible(Context context, ImportStore.Entry e, boolean visible) {
        try {
            Imports.setVisible(e.id, visible);
            e.visible = visible;
            Log.i("DataPackages", "Package \"" + e.name + "\" " + (visible ? "shown" : "hidden")
                    + " on the map (" + e.count + (e.count == 1 ? " item)" : " items)"));
            return true;
        } catch (IOException ex) {
            Log.e("DataPackages", "Could not save show/hide for \"" + e.name + "\"", ex);
            Toast.makeText(context, "Could not save: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static TextView rowText(Context context, String value, int sp, int color) {
        TextView t = new TextView(context);
        t.setText(value);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static int themeAttr(Context context, int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        context.getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId;
    }

    /** TAK Aware's show/hide eye: white and open when shown, grey with a slash when hidden. */
    private static final class EyeIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final boolean shown;

        EyeIcon(float density, boolean shown) {
            this.density = density;
            this.shown = shown;
            paint.setColor(shown ? Color.WHITE : 0xFF8A8A8A);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.round(26 * density);
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.round(26 * density);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float w = 11 * density, h = 6.5f * density;
            // Almond outline: two quadratic curves meeting at the corners of the eye.
            android.graphics.Path outline = new android.graphics.Path();
            outline.moveTo(cx - w, cy);
            outline.quadTo(cx, cy - 2 * h, cx + w, cy);
            outline.quadTo(cx, cy + 2 * h, cx - w, cy);
            outline.close();
            if (shown) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(outline, paint);
                paint.setColor(Color.BLACK);
                canvas.drawCircle(cx, cy, 3.6f * density, paint);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(cx, cy, 1.8f * density, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f * density);
                canvas.drawPath(outline, paint);
                canvas.drawCircle(cx, cy, 3f * density, paint);
                paint.setStrokeWidth(2f * density);
                canvas.drawLine(cx - w * 0.85f, cy - w * 0.85f, cx + w * 0.85f, cy + w * 0.85f, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Lists the server's data packages; tap one to download and import it. */
    private static void showServerPackages(final Context context) {
        final DarkDialog d = new DarkDialog(context, "Server Data Packages");
        final android.widget.TextView status = d.addLabel("Asking the server\u2026");
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        d.content.addView(list, DarkDialog.matchWrap());
        d.addAction("BACK", DarkDialog.COLOR_LABEL, v -> {
            d.dismiss();
            showManager(context);
        });
        d.show();

        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<DataPackages.ServerPackage> found = null;
            String error = null;
            try {
                found = DataPackages.queryServer(context);
            } catch (IOException | RuntimeException ex) {
                Log.e(TAG, "Server query failed", ex);
                error = ex.getMessage();
            }
            final List<DataPackages.ServerPackage> packages = found;
            final String failure = error;
            main.post(() -> {
                if (!d.dialog.isShowing()) return;
                if (packages == null) {
                    status.setTextColor(DarkDialog.COLOR_DANGER);
                    status.setText("Could not get the list: " + failure);
                    return;
                }
                status.setText(packages.isEmpty() ? "The server has no data packages you can see."
                        : "Tap a package to download it and add it to the map.");
                for (final DataPackages.ServerPackage p : packages) {
                    boolean have = DataPackages.isImported(context, p.hash);
                    StringBuilder sub = new StringBuilder(p.size > 0 ? ModUtil.formatSize(p.size) : "size unknown");
                    if (!p.submitter.isEmpty()) sub.append(" \u00b7 ").append(p.submitter);
                    if (p.submitted.length() >= 10) sub.append(" \u00b7 ").append(p.submitted.substring(0, 10));
                    if (have) sub.append(" \u00b7 on this phone");
                    final android.widget.TextView[] title = new android.widget.TextView[1];
                    LinearLayout row = DarkDialog.row(context, have ? p.name + "  \u2713" : p.name, sub.toString(),
                            have ? DarkDialog.COLOR_ACCENT : Color.WHITE, v -> download(context, p, title[0]));
                    title[0] = (android.widget.TextView) row.getChildAt(0);
                    list.addView(row, DarkDialog.matchWrap());
                }
            });
        }, "ServerPackageQuery").start();
    }

    private static void download(final Context context, final DataPackages.ServerPackage p,
                                 final android.widget.TextView title) {
        title.setText(p.name + "  (downloading\u2026)");
        title.setTextColor(DarkDialog.COLOR_LABEL);
        final Handler main = new Handler(Looper.getMainLooper());
        try {
            // If it is already downloading (a repeat tap, or the same package sent over
            // TAK), this just joins the waiting list and the row updates when it ends.
            DataPackages.importFromServer(context, p, (entry, error) -> main.post(() -> {
                if (entry != null) {
                    title.setText(p.name + "  \u2713");
                    title.setTextColor(DarkDialog.COLOR_ACCENT);
                    Toast.makeText(context, "\"" + entry.name + "\": " + entry.addedText(),
                            Toast.LENGTH_LONG).show();
                } else {
                    title.setText(p.name + "  (failed)");
                    title.setTextColor(DarkDialog.COLOR_DANGER);
                    Toast.makeText(context, "Could not download " + p.name + ": " + error, Toast.LENGTH_LONG).show();
                }
            }));
        } catch (IllegalStateException ex) {
            Toast.makeText(context, ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void showEntry(final Context context, final ImportStore.Entry e) {
        final DarkDialog d = new DarkDialog(context, e.name);
        String origin = ImportStore.Entry.originText(e.origin);
        d.addText((origin.isEmpty() ? "" : origin + "\n") + e.contents() + ", imported "
                + DateFormat.getMediumDateFormat(context).format(new Date(e.importedAt)) + " "
                + DateFormat.getTimeFormat(context).format(new Date(e.importedAt)));
        if (e.count == 0) {
            d.addText("Its map sources are in Map Sources: tap the map layers button to pick "
                    + "one, or edit them in Settings \u2192 Map Sources.");
        } else d.addButton("Zoom To", DarkDialog.COLOR_ACCENT, v -> {
            d.dismiss();
            if (!e.visible) setVisible(context, e, true);
            if (context instanceof MainActivity) {
                MapView map = mapView((MainActivity) context);
                if (map != null) zoomTo((MainActivity) context, map, e);
            }
        });
        if (e.count > 0) d.addButton(e.visible ? "Hide from Map" : "Show on Map", DarkDialog.COLOR_FIELD, v -> {
            setVisible(context, e, !e.visible);
            d.dismiss();
            showManager(context);
        });
        d.addAction("DELETE", DarkDialog.COLOR_DANGER, v -> {
            d.dismiss();
            confirmDelete(context, e, () -> showEntry(context, e));
        });
        d.addActionSpacer();
        d.addAction("BACK", DarkDialog.COLOR_LABEL, v -> {
            d.dismiss();
            showManager(context);
        });
        d.show();
    }

    /** {@code back} reopens whichever dialog the delete was started from. */
    private static void confirmDelete(final Context context, final ImportStore.Entry e, final Runnable back) {
        final DarkDialog d = new DarkDialog(context, "Delete \"" + e.name + "\"?");
        d.addText(e.count > 0 ? "Removes its " + e.count + " items from the map and this phone."
                + (e.mapSources > 0 ? " Its map sources stay in Map Sources." : "")
                : "Removes it from this phone. Its map sources stay in Map Sources; delete them there.");
        d.addAction("CANCEL", DarkDialog.COLOR_LABEL, v -> {
            d.dismiss();
            back.run();
        });
        d.addAction("DELETE", DarkDialog.COLOR_DANGER, v -> {
            try {
                Imports.delete(e.id);
            } catch (IOException ex) {
                Toast.makeText(context, "Could not delete: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
            d.dismiss();
            showManager(context);
        });
        d.show();
    }

    private static void zoomTo(MainActivity activity, MapView map, ImportStore.Entry e) {
        activity.modStopFollowing();
        if (e.north - e.south < 1e-4 && e.east - e.west < 1e-4) {
            map.getController().setZoom(Math.max(map.getZoomLevelDouble(), 16.0));
            map.getController().animateTo(new GeoPoint((e.north + e.south) / 2, (e.east + e.west) / 2));
        } else {
            map.zoomToBoundingBox(new BoundingBox(e.north, e.east, e.south, e.west).increaseByScale(1.15f), true);
        }
    }
}
