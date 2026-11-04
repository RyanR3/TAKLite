package com.taklite.client.mod.imports;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import com.taklite.client.MainActivity;
import com.taklite.client.mod.ModUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

/**
 * Imports a KML, KMZ, GPX, CoT or data package file. Started three ways:
 * from Imported Data's "Import File" (shows the system file picker), and by
 * other apps through "Open with" (ACTION_VIEW) or "Share" (ACTION_SEND).
 * It has no screen of its own; progress and results are shown as toasts.
 */
public class ImportActivity extends Activity {

    private static final String TAG = "ImportActivity";
    private static final int PICK = 1;
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    private static final String STATE_PICKING = "picking";
    private static final String STATE_FROM_OTHER_APP = "fromOtherApp";

    /**
     * The import running on its worker thread, and the instance now on screen. The
     * activity can be recreated (rotation) while it works; the worker must then close
     * the new instance, not the one it started from. Main thread.
     */
    private static boolean importing;
    private static WeakReference<ImportActivity> current = new WeakReference<>(null);

    private boolean fromOtherApp;
    private boolean picking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Imports.init(this);
        current = new WeakReference<>(this);
        if (savedInstanceState != null) {
            fromOtherApp = savedInstanceState.getBoolean(STATE_FROM_OTHER_APP);
            picking = savedInstanceState.getBoolean(STATE_PICKING);
            // Recreated: either the picker result is still coming, or the import's worker
            // will close this instance. If neither, the import already finished.
            if (!picking && !importing) finish();
            return;
        }
        Intent intent = getIntent();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) {
            fromOtherApp = true;
            importUri(uri);
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(pick, PICK);
            picking = true;
        } catch (RuntimeException e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_PICKING, picking);
        outState.putBoolean(STATE_FROM_OTHER_APP, fromOtherApp);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        picking = false;
        if (requestCode == PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importUri(data.getData());
        } else {
            finish();
        }
    }

    private void importUri(final Uri uri) {
        final String name = displayName(uri);
        Toast.makeText(this, "Importing " + name + "…", Toast.LENGTH_SHORT).show();
        importing = true;
        final android.content.Context app = getApplicationContext();
        final File cacheDir = getCacheDir();
        new Thread(() -> {
            String message;
            File tmp = new File(cacheDir, "import-" + System.nanoTime());
            try {
                copy(app, uri, tmp);
                ImportStore.Entry entry = Imports.importFile(tmp, name, "File");
                message = "Imported \"" + entry.name + "\": " + entry.addedText();
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Import of " + name + " failed", e);
                message = "Could not import " + name + ": " + e.getMessage();
            } catch (OutOfMemoryError | StackOverflowError e) {
                Log.e(TAG, "Import of " + name + " failed", e);
                message = "Could not import " + name + ": too large or too complex to import on this phone";
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            final String result = message;
            new Handler(Looper.getMainLooper()).post(() -> {
                importing = false;
                Toast.makeText(app, result, Toast.LENGTH_LONG).show();
                ImportActivity shown = current.get();
                boolean openMap = shown != null ? shown.fromOtherApp : fromOtherApp;
                if (openMap) {
                    app.startActivity(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                if (shown != null) shown.finish();
                if (shown != this) finish();
            });
        }, "FileImport").start();
    }

    private static void copy(android.content.Context context, Uri uri, File dest) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("could not open file");
            ModUtil.copy(in, out, MAX_FILE_BYTES, "file is larger than 200 MB");
        } catch (SecurityException e) {
            throw new IOException("no permission to read the file", e);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException e) {
            // Fall through to the URI's last segment.
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "file";
    }
}
