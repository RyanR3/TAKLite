package com.taklite.client.mod;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Toast;

import com.taklite.client.DroppedMarker;
import com.taklite.client.MainActivity;
import com.taklite.client.tak.TakManager;

import org.osmdroid.views.MapView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;

/**
 * Settings → Map: show or hide the Emergency button, and clear all markers at once.
 */
public final class MapOptions {

    private static final String TAG = "MapOptions";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String KEY_SHOW_EMERGENCY = "mod_show_emergency_button";
    /** Set when markers are cleared from Settings while the map screen isn't around. */
    private static final String KEY_CLEAR_PENDING = "mod_clear_markers_pending";
    private static final String TOGGLE_TAG = "mod_emergency_toggle";
    private static final String SEND_TOGGLE_TAG = "mod_send_on_place_toggle";
    private static final String CLEAR_TAG = "mod_clear_markers_button";

    private static WeakReference<MainActivity> mainRef = new WeakReference<>(null);

    private MapOptions() {
    }

    /** MainActivity.onResume. */
    static void onMainActivityResumed(MainActivity activity) {
        mainRef = new WeakReference<>(activity);
        applyEmergencyButton(activity);
        if (prefs(activity).getBoolean(KEY_CLEAR_PENDING, false)) {
            prefs(activity).edit().putBoolean(KEY_CLEAR_PENDING, false).apply();
            int[] cleared = clearAll(activity);
            Toast.makeText(activity, clearedText(cleared), Toast.LENGTH_SHORT).show();
        }
    }

    /** End of SettingsActivity.onCreate. */
    static void onSettingsCreated(final Activity activity) {
        View root = activity.getWindow().getDecorView();
        View toggle = root.findViewWithTag(TOGGLE_TAG);
        if (toggle instanceof CompoundButton) {
            CompoundButton box = (CompoundButton) toggle;
            box.setChecked(showEmergency(activity));
            box.setOnCheckedChangeListener((b, checked) -> {
                prefs(activity).edit().putBoolean(KEY_SHOW_EMERGENCY, checked).apply();
                MainActivity main = mainRef.get();
                if (main != null) applyEmergencyButton(main);
            });
        } else {
            Log.w(TAG, "Emergency button toggle not found");
        }
        View sendToggle = root.findViewWithTag(SEND_TOGGLE_TAG);
        if (sendToggle instanceof CompoundButton) {
            CompoundButton box = (CompoundButton) sendToggle;
            box.setChecked(MarkerSend.autoSend(activity));
            box.setOnCheckedChangeListener((b, checked) -> MarkerSend.setAutoSend(activity, checked));
        } else {
            Log.w(TAG, "Send-on-placement toggle not found");
        }
        View clear = root.findViewWithTag(CLEAR_TAG);
        if (clear != null) {
            clear.setOnClickListener(v -> confirmClear(activity));
        } else {
            Log.w(TAG, "Clear All Markers button not found");
        }
    }

    /**
     * Hook: start of TakManager.sendAlert and cancelAlert (any thread). The alert state
     * changes at the end of those methods, so the button is re-checked now and again a
     * second later; a hidden button must come back while an alert is active.
     */
    public static void onAlertChanged() {
        Runnable apply = () -> {
            MainActivity main = mainRef.get();
            if (main != null && !main.isFinishing()) applyEmergencyButton(main);
        };
        MAIN.post(apply);
        MAIN.postDelayed(apply, 1000);
    }

    private static boolean showEmergency(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_EMERGENCY, true);
    }

    /** Hidden only if the user turned it off and no alert of ours is active (it must stay cancellable). */
    private static void applyEmergencyButton(MainActivity activity) {
        int id = activity.getResources().getIdentifier("alertButton", "id", activity.getPackageName());
        View button = id == 0 ? null : activity.findViewById(id);
        if (button == null) return;
        boolean alertActive;
        try {
            alertActive = TakManager.getInstance().hasActiveAlert();
        } catch (RuntimeException e) {
            alertActive = false;
        }
        button.setVisibility(showEmergency(activity) || alertActive ? View.VISIBLE : View.GONE);
    }

    private static void confirmClear(final Activity activity) {
        final MainActivity main = mainRef.get();
        String text;
        if (main != null && !main.isFinishing()) {
            int own = 0;
            int received = 0;
            for (DroppedMarker m : main.modDroppedMarkers().values()) {
                if (m.remote) received++;
                else own++;
            }
            if (own + received == 0) {
                Toast.makeText(activity, "There are no markers on the map.", Toast.LENGTH_SHORT).show();
                return;
            }
            text = "Removes your " + count(own, "markers", "marker") + " and "
                    + count(received, "received markers", "received marker") + " from this phone. ";
        } else {
            text = "Removes every marker from this phone's map: yours and those received from others. ";
        }
        text += "Markers you already sent stay on teammates' maps. This can't be undone.";
        final DarkDialog d = new DarkDialog(activity, "Clear All Markers?");
        d.addText(text);
        d.addAction("CANCEL", DarkDialog.COLOR_LABEL, v -> d.dismiss());
        d.addAction("CLEAR", DarkDialog.COLOR_DANGER, v -> {
            d.dismiss();
            MainActivity target = mainRef.get();
            if (target != null && !target.isFinishing()) {
                Toast.makeText(activity, clearedText(clearAll(target)), Toast.LENGTH_SHORT).show();
            } else {
                prefs(activity).edit().putBoolean(KEY_CLEAR_PENDING, true).apply();
                Toast.makeText(activity, "Markers will be cleared when the map opens.", Toast.LENGTH_SHORT).show();
            }
        });
        d.show();
    }

    /** Removes every dropped and received marker. Main thread. Returns {own, received}. */
    private static int[] clearAll(MainActivity activity) {
        MarkerMove.cancel();
        activity.modDismissRadialMenu();
        Map<String, DroppedMarker> dropped = activity.modDroppedMarkers();
        Map<String, org.osmdroid.views.overlay.Marker> onMap = activity.modTakUserMarkers();
        MapView map = ModUtil.mapView(activity);
        int own = 0;
        int received = 0;
        for (DroppedMarker m : new ArrayList<>(dropped.values())) {
            if (map != null && m.marker != null) map.getOverlays().remove(m.marker);
            if (m.remote) {
                String uid = m.key.replace("remote-", "");
                onMap.remove(uid);
                RemoteMarkers.onDeleted(uid);
                received++;
            } else {
                own++;
            }
        }
        dropped.clear();
        // Received markers TAK Lite holds but hasn't drawn yet.
        RemoteMarkers.forgetAll();
        activity.modSaveDroppedMarkers();
        if (map != null) map.invalidate();
        Log.i(TAG, "Cleared " + own + " own and " + received + " received markers");
        return new int[]{own, received};
    }

    private static String clearedText(int[] cleared) {
        int total = cleared[0] + cleared[1];
        return total == 0 ? "No markers to clear." : "Cleared " + count(total, "markers", "marker") + ".";
    }

    private static String count(int n, String plural, String singular) {
        return n + " " + (n == 1 ? singular : plural);
    }

    private static SharedPreferences prefs(Context context) {
        return ModUtil.prefs(context);
    }
}
