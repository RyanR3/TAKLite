package com.taklite.client.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.modules.SqlTileWriter;

import java.io.File;

/**
 * Size limit of osmdroid's on-phone tile cache (one SQLite file shared by all maps).
 *
 * osmdroid keeps that limit only in memory and trims the cache when the map
 * starts, so {@link #apply} has to run before MainActivity creates its MapView.
 */
final class TileCache {

    private static final String KEY_LIMIT_MB = "custom_map_cache_mb";
    /** osmdroid's own default. */
    static final int DEFAULT_LIMIT_MB = 600;
    private static final int[] LIMIT_CHOICES_MB = {300, 600, 1024, 2048, 4096, 8192, 16384};
    private static final long MB = 1024L * 1024L;

    private static final String TAG = "TileCache";

    private TileCache() {
    }

    static int limitMb(Context context) {
        return ModUtil.prefs(context).getInt(KEY_LIMIT_MB, DEFAULT_LIMIT_MB);
    }

    /**
     * Pushes the chosen limit into osmdroid. Like osmdroid itself, never lets the cache
     * grow past 95% of the space it could have, and trims it to 5/6 of the limit
     * (osmdroid's 500 MB / 600 MB ratio) when it overflows.
     *
     * Trimming is done by {@link #trimSoon} rather than osmdroid: osmdroid compares the
     * cache FILE size with the limit, but SQLite never shrinks the file when tiles are
     * deleted. Once the file had grown past the limit, osmdroid deleted another batch of
     * the oldest tiles every 5 minutes of map use, eating into downloaded offline areas.
     */
    static void apply(Context context) {
        SqlTileWriter.setCleanupOnStart(false);
        // osmdroid runs its cleanup when now > lastCheck + frequency, with lastCheck starting
        // at 0, so a frequency of "a year" still fires on the first tile saved after every
        // start. Half of Long.MAX_VALUE can't be reached and can't overflow the addition.
        Configuration.getInstance().setTileGCFrequencyInMillis(Long.MAX_VALUE / 2);
        long max = limitMb(context) * MB;
        long available = availableBytes(context);
        // 0 means the free space couldn't be read; keep the chosen limit rather than none.
        if (available > 0) max = Math.min(max, (long) (available * 0.95));
        IConfigurationProvider config = Configuration.getInstance();
        config.setTileFileSystemCacheMaxBytes(max);
        config.setTileFileSystemCacheTrimBytes(max / 6 * 5);
    }

    /**
     * Trims the cache to 5/6 of the limit, oldest tiles first, if the tiles in it take up more
     * than the limit. On a background thread; called when the map opens or comes back, and
     * after an area download.
     */
    static void trimSoon(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                trim(app);
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not trim the map cache", e);
            }
        }, "TileCacheTrim").start();
    }

    private static synchronized void trim(Context context) {
        if (AreaDownloader.isRunning()) return; // runs again when the download ends
        long used = readUsedBytes(context);
        IConfigurationProvider config = Configuration.getInstance();
        long max = config.getTileFileSystemCacheMaxBytes();
        Log.i(TAG, "Offline cache: " + ModUtil.formatSize(used) + " in use of " + ModUtil.formatSize(max)
                + " (chosen " + limitMb(context) + " MB, file " + ModUtil.formatSize(fileBytes(context)) + ", "
                + ModUtil.formatSize(freeBytes(cacheDir(context))) + " free)");
        if (max <= 0 || used <= max) return;
        long target = config.getTileFileSystemCacheTrimBytes();
        Log.i(TAG, "Trimming the map cache: " + ModUtil.formatSize(used) + " in use, limit "
                + ModUtil.formatSize(max) + ", trimming to " + ModUtil.formatSize(target));
        new Cleaner().runCleanupOperation(used - target, config.getTileGCBulkSize(),
                config.getTileGCBulkPauseInMillis(), true);
        forgetUsedBytes();
        Log.i(TAG, "Map cache trimmed: " + ModUtil.formatSize(readUsedBytes(context)) + " in use");
    }

    /** Size of the cache file. SQLite keeps it at its largest and reuses the space of removed tiles. */
    static long fileBytes(Context context) {
        return new File(cacheDir(context), SqlTileWriter.DATABASE_FILENAME).length();
    }

    private static long cachedUsed = -1;
    private static long cachedUsedAt;
    private static final long USED_CACHE_MS = 5000;

    /**
     * Space the saved tiles take up inside the cache file (what the limit is about).
     * Reading it opens the database, so dialogs get a value at most 5 s old rather
     * than a fresh read on every call from the main thread.
     */
    static long usedBytes(Context context) {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (TileCache.class) {
            if (cachedUsed >= 0 && now - cachedUsedAt < USED_CACHE_MS) return cachedUsed;
        }
        long used = readUsedBytes(context);
        synchronized (TileCache.class) {
            cachedUsed = used;
            cachedUsedAt = now;
        }
        return used;
    }

    /** A just-measured figure, for right after a download or clear. */
    static long freshUsedBytes(Context context) {
        forgetUsedBytes();
        return usedBytes(context);
    }

    private static void forgetUsedBytes() {
        synchronized (TileCache.class) {
            cachedUsed = -1;
        }
    }

    private static long readUsedBytes(Context context) {
        File file = new File(cacheDir(context), SqlTileWriter.DATABASE_FILENAME);
        if (!file.exists()) return 0;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            return (pragma(db, "page_count") - pragma(db, "freelist_count")) * pragma(db, "page_size");
        } catch (RuntimeException e) {
            return file.length();
        }
    }

    private static long pragma(SQLiteDatabase db, String name) {
        try (android.database.Cursor c = db.rawQuery("PRAGMA " + name, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    static long maxBytes() {
        return Configuration.getInstance().getTileFileSystemCacheMaxBytes();
    }

    /** Free space plus what the cache already uses: the most the cache could grow to. */
    private static long availableBytes(Context context) {
        return freeBytes(cacheDir(context)) + fileBytes(context);
    }

    /**
     * Free space where the cache lives. On a fresh install osmdroid hasn't created the
     * folder yet, and getUsableSpace() is 0 for a missing folder, so ask its nearest parent.
     */
    private static long freeBytes(File dir) {
        File f = dir;
        while (f != null && !f.exists()) f = f.getParentFile();
        return f == null ? 0 : f.getUsableSpace();
    }

    private static File cacheDir(Context context) {
        // Same lookup as osmdroid's Configuration.load(), which stores the path it picked.
        SharedPreferences osmPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        String path = osmPrefs.getString("osmdroid.cachePath", null);
        return path != null ? new File(path) : Configuration.getInstance().getOsmdroidTileCache(context);
    }

    static String summary(Context context) {
        return "Using " + ModUtil.formatSize(usedBytes(context)) + " of " + ModUtil.formatSize(maxBytes())
                + " · " + ModUtil.formatSize(freeBytes(cacheDir(context))) + " free on this phone. "
                + "Shared by all maps; when it fills up, the oldest tiles are removed.";
    }

    /** Confirms, then deletes every cached tile (all maps) and reopens via {@code next}. */
    static void confirmClear(final Context context, final Runnable next) {
        if (AreaDownloader.isRunning()) {
            Toast.makeText(context, "Wait for the map download to finish, or cancel it, first.",
                    Toast.LENGTH_LONG).show();
            next.run();
            return;
        }
        final DarkDialog d = new DarkDialog(context, "Clear Offline Cache?");
        d.addText("Deletes all " + ModUtil.formatSize(usedBytes(context)) + " of saved map tiles, for every "
                + "map. Areas you downloaded for offline use will need downloading again. Tiles are "
                + "saved again as you use the map.");
        d.addAction("CANCEL", DarkDialog.COLOR_LABEL, v -> {
            d.dismiss();
            next.run();
        });
        d.addAction("CLEAR", DarkDialog.COLOR_DANGER, v -> {
            d.dismiss();
            final Context app = context.getApplicationContext();
            final long before = fileBytes(context);
            Toast.makeText(context, "Clearing the map cache\u2026", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String message;
                try {
                    new Cleaner().clearAll();
                    forgetUsedBytes();
                    message = "Map cache cleared: freed " + ModUtil.formatSize(Math.max(0, before - fileBytes(app))) + ".";
                } catch (RuntimeException e) {
                    Log.e(TAG, "Could not clear the map cache", e);
                    message = "Could not clear the map cache: " + e.getMessage();
                }
                final String result = message;
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(app, result, Toast.LENGTH_LONG).show();
                    next.run();
                });
            }, "ClearTileCache").start();
        });
        d.show();
    }

    /**
     * osmdroid's purgeCache() only deletes rows, so the file keeps its size. VACUUM
     * afterwards gives the space back. Uses osmdroid's own (shared) database connection.
     */
    private static final class Cleaner extends SqlTileWriter {
        void clearAll() {
            if (!purgeCache()) throw new IllegalStateException("tile database is not available");
            SQLiteDatabase db = getDb();
            if (db != null && db.isOpen()) db.execSQL("VACUUM");
        }
    }

    static void showLimitPicker(final Context context, final Runnable next) {
        final DarkDialog d = new DarkDialog(context, "Offline Cache Size");
        d.dialog.setOnDismissListener(dialog -> next.run());
        d.addText("How much space map tiles may use on this phone. Lowering it removes "
                + "tiles the next time the map opens.");
        long available = availableBytes(context);
        int current = limitMb(context);
        for (final int mb : LIMIT_CHOICES_MB) {
            String title = ModUtil.formatSize(mb * MB) + (mb == DEFAULT_LIMIT_MB ? " (default)" : "");
            String subtitle = available > 0 && mb * MB > available * 0.95
                    ? "More than this phone has free; limited to " + ModUtil.formatSize((long) (available * 0.95))
                    : null;
            d.addRow(mb == current ? title + "  ✓" : title, subtitle,
                    mb == current ? DarkDialog.COLOR_ACCENT : Color.WHITE, v -> {
                        ModUtil.prefs(context)
                                .edit().putInt(KEY_LIMIT_MB, mb).apply();
                        apply(context);
                        d.dismiss();
                    });
        }
        d.addAction("CANCEL", DarkDialog.COLOR_LABEL, v -> d.dismiss());
        d.show();
    }
}
