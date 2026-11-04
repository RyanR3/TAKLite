package com.taklite.client.mod;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.taklite.client.DroppedMarker;
import com.taklite.client.MainActivity;
import com.taklite.client.tak.TakManager;

/**
 * Settings → "Send Marker Upon Placement".
 *
 * Off (default, stock TAK Lite's behaviour): nothing goes out on its own. A placed,
 * edited or moved marker is sent only when the user asks: the radial menu's Send, or
 * Save & Send in the edit dialog. On: a marker is sent to the team as soon as it is
 * placed, and again when saved after an edit or moved.
 */
public final class MarkerSend {

    private static final String TAG = "MarkerSend";
    private static final String KEY_SEND_ON_PLACE = "mod_send_on_place";

    private MarkerSend() {
    }

    public static boolean autoSend(Context context) {
        return ModUtil.prefs(context).getBoolean(KEY_SEND_ON_PLACE, false);
    }

    static void setAutoSend(Context context, boolean on) {
        ModUtil.prefs(context).edit().putBoolean(KEY_SEND_ON_PLACE, on).apply();
    }

    /** Hook: end of MainActivity.placeMarker, with the new marker. */
    public static void onPlaced(MainActivity activity, DroppedMarker marker) {
        try {
            if (autoSend(activity)) send(activity, marker, "placed");
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not send the placed marker", e);
        }
    }

    /**
     * Hook: the edit dialog's plain Save, after the marker is updated (Save & Send is
     * untouched and always sends). With the setting on the change goes to the team at
     * once; off, a marker the team already has gets a reminder to send it.
     */
    public static void onEdited(MainActivity activity, DroppedMarker marker) {
        try {
            if (marker.remote) return;
            String message = sendIfAuto(activity, marker, "updated");
            if (message != null) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not send the edited marker", e);
        }
    }

    /**
     * Sends {@code marker} under its stable uid if the setting is on and TAK is connected.
     * Returns a short status for a toast: null when nothing needed saying.
     */
    static String sendIfAuto(MainActivity activity, DroppedMarker marker, String what) {
        if (!autoSend(activity)) {
            return marker.transmitted ? "Marker " + what + ". Send it to update the team." : null;
        }
        return send(activity, marker, what);
    }

    private static String send(MainActivity activity, DroppedMarker marker, String what) {
        TakManager tak;
        try {
            tak = TakManager.getInstance();
        } catch (RuntimeException e) {
            tak = null;
        }
        if (tak == null || !tak.isConnected()) {
            String message = "Marker " + what + ". Not connected: send it when TAK is back.";
            if ("placed".equals(what)) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            return message;
        }
        MarkerIds.beforeSend(marker);
        tak.sendMarker(marker.position.getLatitude(), marker.position.getLongitude(),
                marker.position.getAltitude(), marker.affiliation, marker.name, marker.remarks);
        marker.transmitted = true;
        activity.modSaveDroppedMarkers();
        return "Marker " + what + " and sent to the team.";
    }
}
