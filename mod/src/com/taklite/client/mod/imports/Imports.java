package com.taklite.client.mod.imports;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.taklite.client.MainActivity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Entry points for imported map data (KML, KMZ, GPX, CoT and TAK data packages).
 * The patched app calls the public methods; everything else lives in this package.
 */
public final class Imports {

    private static final String TAG = "Imports";
    private static final String CHANNEL_ID = "mod_data_packages";
    private static final String TOOLBAR_BUTTON_TAG = "data_packages_button";

    // Read from the network thread; store is set before app, so app != null means both are ready.
    private static volatile Context app;
    private static volatile ImportStore store;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Bumped on every change, so the map knows to redraw. */
    private static volatile int version;
    /** TAK Lite's map screen while it is in the foreground. Main thread. */
    private static MainActivity resumed;
    /** Waiting for the map screen to come to the foreground. Main thread. */
    private static final List<Consumer<MainActivity>> waitingForScreen = new ArrayList<>();

    private Imports() {
    }

    // ==================== Hooks ====================

    /** MainActivity.onCreate and TakLocationService.onCreate: remember the app context. */
    public static synchronized void init(Context context) {
        if (app != null) return;
        Context appContext = context.getApplicationContext();
        final ImportStore s = new ImportStore(new File(appContext.getFilesDir(), "mod_imports"));
        store = s;
        app = appContext;
        new Thread(s::cleanStaging, "ImportCleanup").start();
        if (appContext instanceof Application) {
            ((Application) appContext).registerActivityLifecycleCallbacks(new ScreenTracker());
        }
    }

    /** TakManager.onCotReceived, on the network thread: picks out shared data packages. */
    public static void onCotReceived(String cot) {
        if (app == null || cot == null || cot.indexOf("b-f-t-r") < 0) return;
        try {
            DataPackages.onFileShare(app, cot);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not handle file share", e);
        }
    }

    /** End of MainActivity.onCreate: draw imported data and wire the toolbar's Data Packages button. */
    public static void onMainActivityCreated(MainActivity activity) {
        init(activity);
        ImportedLayers.attach(activity);
        android.view.View button = activity.getWindow().getDecorView().findViewWithTag(TOOLBAR_BUTTON_TAG);
        if (button != null) {
            button.setOnClickListener(v -> ImportedLayers.showManager(activity));
        } else {
            Log.w(TAG, "Data Packages toolbar button not found");
        }
    }

    /** MainActivity.onResume. */
    public static void onMainActivityResumed(MainActivity activity) {
        ImportedLayers.onResume(activity);
    }

    // ==================== Internal API ====================

    static ImportStore store() {
        return store;
    }

    static int version() {
        return version;
    }

    /**
     * Reads {@code file} and stores what it contains. Blocking; call off the main thread.
     * Replaces an earlier import of the same package or file.
     */
    static ImportStore.Entry importFile(File file, String displayName, String origin) throws IOException {
        File staging = store.newStagingDir();
        try {
            Importer.Result result = Importer.read(file, displayName, staging);
            for (String note : result.skippedNotes) Log.i(TAG, displayName + ": skipped " + note);
            if (!result.mapSources.isEmpty()) {
                com.taklite.client.mod.CustomMapSources.addImported(app, result.mapSources);
            }
            ImportStore.Entry entry = store.add(result, origin, staging);
            changed();
            return entry;
        } finally {
            if (staging.exists()) ImportStore.deleteTree(staging);
        }
    }

    static void setVisible(String id, boolean visible) throws IOException {
        store.setVisible(id, visible);
        changed();
    }

    static void delete(String id) throws IOException {
        store.delete(id);
        changed();
    }

    static List<ImportStore.Entry> list() {
        return store.list();
    }

    private static void changed() {
        version++;
        MAIN.post(ImportedLayers::reload);
    }

    /**
     * Runs {@code action} on the main thread with the map screen: now if TAK Lite is in the
     * foreground, otherwise (after running {@code queued}) the next time it comes back.
     */
    static void whenOnScreen(Consumer<MainActivity> action, Runnable queued) {
        MAIN.post(() -> {
            if (resumed != null && !resumed.isFinishing()) {
                action.accept(resumed);
            } else {
                waitingForScreen.add(action);
                queued.run();
            }
        });
    }

    private static final class ScreenTracker implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity activity) {
            if (!(activity instanceof MainActivity)) return;
            resumed = (MainActivity) activity;
            List<Consumer<MainActivity>> waiting = new ArrayList<>(waitingForScreen);
            waitingForScreen.clear();
            for (Consumer<MainActivity> action : waiting) action.accept(resumed);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (activity == resumed) resumed = null;
            // A half-done marker move would otherwise keep the whole map screen alive.
            if (activity instanceof MainActivity) com.taklite.client.mod.MarkerMove.cancel();
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    /** Shows a toast if TAK Lite is on screen and always posts a notification. */
    static void announce(String title, String text) {
        MAIN.post(() -> {
            Activity visible = ImportedLayers.visibleActivity();
            if (visible != null) Toast.makeText(visible, title + "\n" + text, Toast.LENGTH_LONG).show();
        });
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Data packages",
                    NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(app, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.Notification n = new android.app.Notification.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify((int) (System.currentTimeMillis() & 0x7FFFFFFF), n);
        } catch (RuntimeException e) {
            // No notification permission: the toast (if visible) is all the user gets.
            Log.w(TAG, "Could not post notification", e);
        }
    }

    /** The application context once {@link #init} has run, else null. */
    public static Context app() {
        return app;
    }
}
