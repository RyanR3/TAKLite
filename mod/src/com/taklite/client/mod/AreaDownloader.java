package com.taklite.client.mod;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.tileprovider.modules.CantContinueException;
import org.osmdroid.tileprovider.modules.IFilesystemCache;
import org.osmdroid.tileprovider.modules.TileDownloader;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads every tile of a custom map source in the visible area, over a zoom
 * range, into osmdroid's tile cache so the area works without the server.
 *
 * osmdroid's own CacheManager downloads on one thread and crashes when asked to
 * bulk-download a source whose policy forbids it, so this does the loop itself.
 * Only user-added sources are offered: the built-in OpenStreetMap tiles must not
 * be bulk-downloaded under OSM's tile usage policy.
 */
final class AreaDownloader {

    private static final String TAG = "AreaDownloader";
    private static final int THREADS = 4;
    private static final long MAX_TILES = 100_000;
    /** Give up when this many tiles fail before any succeeds (server unreachable). */
    private static final int FAILURES_BEFORE_GIVING_UP = 20;
    /** Rough PNG sizes from TileServer GL, for the estimate only. */
    private static final long BYTES_PER_512_TILE = 50 * 1024;
    private static final long BYTES_PER_256_TILE = 20 * 1024;

    /**
     * Downloads stop at street-level detail: standard web zoom 18 (about 0.6 m per pixel).
     * Closer than that, the map enlarges these tiles (osmdroid approximates missing zooms
     * from cached lower ones, offline too), so deeper levels cost 4x the tiles each for
     * little gain.
     */
    static final int MAX_STANDARD_ZOOM = 18;

    private static volatile boolean running;

    private AreaDownloader() {
    }

    static boolean isRunning() {
        return running;
    }

    /**
     * Highest zoom to download for this source. A 512 px tile at zoom z carries the
     * detail of a 256 px tile at z + 1, so 512 px sources stop one level lower.
     */
    static int maxDownloadZoom(CustomMapSources.Source source) {
        int extra = 0;
        for (int size = source.tileSize; size > 256; size /= 2) extra++;
        return Math.max(source.minZoom, Math.min(source.maxZoom, MAX_STANDARD_ZOOM - extra));
    }

    static void show(final Context context, final MapView mapView, final CustomMapSources.Source source) {
        if (running) {
            android.widget.Toast.makeText(context, "A map download is already running",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final BoundingBox area = mapView.getBoundingBox();
        int currentZoom = (int) Math.floor(mapView.getZoomLevelDouble());
        final int maxZoom = maxDownloadZoom(source);

        final DarkDialog d = new DarkDialog(context, "Download Area for Offline");
        d.addText("Saves \"" + source.name + "\" for the area on screen, so it keeps working "
                + "without the server. You are at zoom " + currentZoom + "; each extra zoom "
                + "level needs about 4× the tiles. Downloads go up to zoom " + maxZoom
                + (maxZoom < MAX_STANDARD_ZOOM ? " (street level; the same detail as zoom "
                + MAX_STANDARD_ZOOM + " on standard 256 px maps)" : " (street level)")
                + "; zoom in further and the map enlarges those tiles, offline too.");
        final EditText from = d.addField("From zoom", null, String.valueOf(source.minZoom),
                InputType.TYPE_CLASS_NUMBER);
        final EditText to = d.addField("To zoom", null,
                String.valueOf(Math.max(source.minZoom, Math.min(maxZoom, currentZoom + 2))),
                InputType.TYPE_CLASS_NUMBER);
        final TextView estimate = d.addLabel("");

        final TextView[] download = new TextView[1];
        final Runnable update = () -> {
            int[] range = readRange(from, to, source.minZoom, maxZoom);
            String error = range == null
                    ? "Zoom levels must be between " + source.minZoom + " and " + maxZoom
                    : null;
            long tiles = range == null ? 0 : countTiles(area, range[0], range[1]);
            if (error == null && tiles > MAX_TILES) {
                error = String.format(Locale.US, "%,d tiles is too many (limit %,d). Zoom in or lower "
                        + "\"To zoom\".", tiles, MAX_TILES);
            }
            if (error != null) {
                estimate.setTextColor(DarkDialog.COLOR_DANGER);
                estimate.setText(error);
                download[0].setEnabled(false);
                download[0].setAlpha(0.4f);
                return;
            }
            long bytes = tiles * (source.tileSize >= 512 ? BYTES_PER_512_TILE : BYTES_PER_256_TILE);
            String text = String.format(Locale.US, "%,d tiles, roughly %s (depends on the map style).",
                    tiles, ModUtil.formatSize(bytes));
            if (bytes > TileCache.maxBytes() - TileCache.usedBytes(context)) {
                text += "\nThat may not fit in the offline cache (" + ModUtil.formatSize(TileCache.maxBytes())
                        + "); older tiles would be removed. You can raise it in Settings → Map Sources.";
                estimate.setTextColor(0xFFFFC107);
            } else {
                estimate.setTextColor(DarkDialog.COLOR_LABEL);
            }
            estimate.setText(text);
            download[0].setEnabled(true);
            download[0].setAlpha(1f);
        };
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                update.run();
            }
        };

        d.addAction("CANCEL", DarkDialog.COLOR_LABEL, v -> d.dismiss());
        download[0] = d.addAction("DOWNLOAD", DarkDialog.COLOR_ACCENT, v -> {
            int[] range = readRange(from, to, source.minZoom, maxZoom);
            if (range == null) return;
            d.dismiss();
            start(context, mapView, source, area, range[0], range[1]);
        });
        from.addTextChangedListener(watcher);
        to.addTextChangedListener(watcher);
        update.run();
        d.show();
    }

    /** Returns {from, to} or null when outside {@code minZoom..maxZoom}. */
    private static int[] readRange(EditText from, EditText to, int minZoom, int maxZoom) {
        try {
            int a = Integer.parseInt(from.getText().toString().trim());
            int b = Integer.parseInt(to.getText().toString().trim());
            if (a < minZoom || b > maxZoom || a > b) return null;
            return new int[]{a, b};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static long countTiles(BoundingBox area, int fromZoom, int toZoom) {
        long total = 0;
        for (int z = fromZoom; z <= toZoom; z++) {
            Rect r = CacheManager.getTilesRect(area, z);
            total += (long) (r.width() + 1) * (r.height() + 1);
        }
        return total;
    }

    /** Hands out tile indexes zoom by zoom, row by row, to the worker threads. */
    private static final class TileQueue {
        private final BoundingBox area;
        private final int toZoom;
        private int zoom;
        private Rect rect;
        private int x;
        private int y;

        TileQueue(BoundingBox area, int fromZoom, int toZoom) {
            this.area = area;
            this.toZoom = toZoom;
            startZoom(fromZoom);
        }

        private void startZoom(int z) {
            zoom = z;
            rect = CacheManager.getTilesRect(area, z);
            x = rect.left;
            y = rect.top;
        }

        /** Next tile index, or -1 when done. */
        synchronized long next() {
            while (zoom <= toZoom) {
                if (y > rect.bottom) {
                    y = rect.top;
                    x++;
                }
                if (x > rect.right) {
                    if (zoom == toZoom) {
                        zoom++;
                        return -1;
                    }
                    startZoom(zoom + 1);
                    continue;
                }
                int n = 1 << zoom;
                long tile = MapTileIndex.getTileIndex(zoom, ((x % n) + n) % n, Math.min(Math.max(y, 0), n - 1));
                y++;
                return tile;
            }
            return -1;
        }

        synchronized int currentZoom() {
            return Math.min(zoom, toZoom);
        }
    }

    /**
     * Whether the tile server answers at all. Tiles outside a server's coverage come back
     * as 404 and count as failures, so 20 failures alone don't mean the server is down:
     * before giving up, one request is made and any HTTP answer counts as alive.
     */
    private static boolean serverAnswers(TemplateTileSource source, long tile, Boolean[] probe) {
        synchronized (probe) {
            if (probe[0] != null) return probe[0];
            boolean answers = false;
            java.net.HttpURLConnection conn = null;
            try {
                conn = (java.net.HttpURLConnection) new java.net.URL(source.getTileURLString(tile)).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("HEAD");
                int code = conn.getResponseCode();
                answers = code > 0;
                Log.i(TAG, "Server probe answered HTTP " + code + "; missing tiles are treated as not on the server");
            } catch (java.io.IOException | RuntimeException e) {
                Log.w(TAG, "Server probe failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
            probe[0] = answers;
            return answers;
        }
    }

    private static void start(final Context context, MapView mapView, final CustomMapSources.Source source,
                              BoundingBox area, final int fromZoom, final int toZoom) {
        final long total = countTiles(area, fromZoom, toZoom);
        final TemplateTileSource tileSource = source.toTileSource();
        final IFilesystemCache writer = mapView.getTileProvider().getTileWriter();
        final TileQueue queue = new TileQueue(area, fromZoom, toZoom);
        final AtomicInteger downloaded = new AtomicInteger();
        final AtomicInteger upToDate = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final boolean[] cancelled = new boolean[1];
        final boolean[] unreachable = new boolean[1];
        final Boolean[] probe = new Boolean[1];
        final long cacheBefore = TileCache.usedBytes(context);
        Log.i(TAG, String.format(Locale.US, "Downloading %,d tiles, zoom %d-%d (%d px tiles); cache %s in use, limit %s",
                total, fromZoom, toZoom, source.tileSize, ModUtil.formatSize(cacheBefore),
                ModUtil.formatSize(TileCache.maxBytes())));

        final DarkDialog d = new DarkDialog(context, "Downloading " + source.name);
        d.dialog.setCancelable(false);
        final ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(DarkDialog.COLOR_ACCENT));
        d.content.addView(bar, DarkDialog.matchWrap());
        final TextView status = d.addLabel("Starting…");
        status.setTextColor(android.graphics.Color.WHITE);
        d.addText("Keep TAK Lite open until it finishes; the screen stays on meanwhile.");

        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        final TextView cancel = d.addAction("CANCEL", DarkDialog.COLOR_DANGER, v -> {
            synchronized (cancelled) {
                cancelled[0] = true;
            }
        });
        final TextView close = d.addAction("CLOSE", DarkDialog.COLOR_LABEL, v -> d.dismiss());
        close.setVisibility(View.GONE);
        d.show();
        Window window = d.dialog.getWindow();
        if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        running = true;

        for (int i = 0; i < THREADS; i++) {
            pool.execute(() -> {
                TileDownloader downloader = new TileDownloader();
                while (true) {
                    synchronized (cancelled) {
                        if (cancelled[0]) return;
                    }
                    long tile = queue.next();
                    if (tile == -1) return;
                    try {
                        Long expires = writer.getExpirationTimestamp(tileSource, tile);
                        if (expires != null && expires > System.currentTimeMillis()) {
                            upToDate.incrementAndGet();
                        } else if (downloader.downloadTile(tile, writer, tileSource) != null) {
                            downloaded.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                        }
                    } catch (CantContinueException | RuntimeException e) {
                        Log.w(TAG, "Tile " + MapTileIndex.toString(tile) + " failed", e);
                        failed.incrementAndGet();
                    }
                    if (failed.get() >= FAILURES_BEFORE_GIVING_UP && downloaded.get() == 0 && upToDate.get() == 0
                            && !serverAnswers(tileSource, tile, probe)) {
                        synchronized (cancelled) {
                            unreachable[0] = true;
                            cancelled[0] = true;
                        }
                        return;
                    }
                }
            });
        }
        pool.shutdown();

        final Handler main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() {
            @Override
            public void run() {
                int done = downloaded.get() + upToDate.get() + failed.get();
                bar.setProgress(total == 0 ? 1000 : (int) (1000L * done / total));
                boolean finished = pool.isTerminated();
                String counts = String.format(Locale.US,
                        "%,d of %,d tiles · %,d downloaded, %,d already saved, %,d failed",
                        done, total, downloaded.get(), upToDate.get(), failed.get());
                if (!finished) {
                    status.setText("Zoom " + queue.currentZoom() + " of " + toZoom + "\n" + counts);
                    main.postDelayed(this, 300);
                    return;
                }
                running = false;
                Window w = d.dialog.getWindow();
                if (w != null) w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                cancel.setVisibility(View.GONE);
                close.setVisibility(View.VISIBLE);
                d.dialog.setCancelable(true);
                String headline;
                if (unreachable[0]) {
                    headline = "Stopped: the map server isn't answering. Check the connection and "
                            + "use TEST in the source's settings.";
                    status.setTextColor(DarkDialog.COLOR_DANGER);
                } else if (cancelled[0]) {
                    headline = "Cancelled. Tiles saved so far are kept.";
                } else {
                    headline = failed.get() == 0 ? "Done."
                            : "Done, but some tiles failed (not on the server, or errors). Run it again to retry them.";
                    status.setTextColor(failed.get() == 0 ? DarkDialog.COLOR_ACCENT : 0xFFFFC107);
                }
                long used = TileCache.freshUsedBytes(context);
                long grown = used - cacheBefore;
                status.setText(headline + "\n" + counts + "\nCache: " + ModUtil.formatSize(used)
                        + (grown > 0 ? " (+" + ModUtil.formatSize(grown) + ")" : ""));
                Log.i(TAG, headline + " " + counts + "; cache " + ModUtil.formatSize(used) + " in use (+"
                        + ModUtil.formatSize(Math.max(0, grown)) + "), limit " + ModUtil.formatSize(TileCache.maxBytes()));
                TileCache.trimSoon(context);
            }
        });
        // Belt and braces: never leave the "running" flag stuck if the UI goes away.
        new Thread(() -> {
            try {
                pool.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException ignored) {
            } finally {
                running = false;
            }
        }).start();
    }
}
