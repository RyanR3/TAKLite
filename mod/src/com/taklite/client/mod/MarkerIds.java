package com.taklite.client.mod;

import com.taklite.client.DroppedMarker;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stable CoT uids for dropped markers.
 *
 * Stock TAK Lite gives a marker a new random uid every time it is sent, so each
 * re-send (after an edit or a move) adds another copy on teammates' maps. The
 * patched send paths call {@link #beforeSend} with the marker, and
 * TakManager.sendMarker asks {@link #uidForSend} for the uid: the same marker
 * then always goes out under the same uid, which TAK clients treat as an update.
 * {@link #outgoing} also makes the marker one that other clients keep until deleted.
 */
public final class MarkerIds {

    /**
     * Key of the marker about to be sent. sendMarker returns early when TAK is offline,
     * leaving it unused, so it only counts for the very next send on the same thread.
     */
    private static String pendingKey;
    private static Thread pendingThread;
    private static long pendingAt;
    private static final long PENDING_MS = 2000;

    private MarkerIds() {
    }

    /** Hook: just before TakManager.sendMarker for this marker. */
    public static synchronized void beforeSend(DroppedMarker marker) {
        // sendMarker returns at once when not connected, which would leave the key armed.
        if (marker == null || !connected()) {
            pendingKey = null;
            return;
        }
        pendingKey = marker.key;
        pendingThread = Thread.currentThread();
        pendingAt = android.os.SystemClock.elapsedRealtime();
    }

    /**
     * Hook: inside TakManager.sendMarker. Returns this marker's stable uid, or
     * {@code fallback} (the stock random uid) for a send that didn't name a marker.
     */
    public static synchronized String uidForSend(String deviceUid, String fallback) {
        String key = pendingKey;
        boolean fresh = pendingThread == Thread.currentThread()
                && android.os.SystemClock.elapsedRealtime() - pendingAt < PENDING_MS;
        pendingKey = null;
        pendingThread = null;
        if (key == null || !fresh) return fallback;
        return stableUid(deviceUid, key);
    }

    /** How long a sent marker is valid for: a year, so other clients keep it until it's deleted. */
    static final long MARKER_STALE_MS = 365L * 24 * 60 * 60 * 1000;
    private static final Pattern EVENT_TAG = Pattern.compile("<event\\b[^>]*>");
    private static final Pattern STALE = Pattern.compile("(\\sstale\\s*=\\s*)(['\"])[^'\"]*\\2");
    private static final String DETAIL = "<detail>";

    /**
     * Hook: TakManager.sendMarker, with the CoT about to go out; returns the CoT to send.
     * Stock TAK Lite sends markers with a 10-minute stale time and no {@code <archive/>}, so
     * TAK Aware and ATAK treat them as transient and drop them after a few minutes. This
     * moves the stale time a year out and adds {@code <archive/>}, as ATAK's own markers
     * carry, so they stay on teammates' maps until deleted. Never throws; anything it
     * doesn't recognise goes out unchanged.
     */
    public static String outgoing(String cot) {
        String out = cot;
        try {
            out = keepOnOtherMaps(cot, System.currentTimeMillis());
        } catch (RuntimeException e) {
            android.util.Log.w("MarkerIds", "Could not mark the marker as kept; sending it as is", e);
        }
        // Logged only when turned on with adb shell setprop log.tag.MarkerIds DEBUG.
        if (android.util.Log.isLoggable("MarkerIds", android.util.Log.DEBUG)) {
            android.util.Log.d("MarkerIds", "Marker CoT: " + out);
        }
        return out;
    }

    /** The stale="..." of the event tag set to {@code now} + a year, and {@code <archive/>} added. */
    static String keepOnOtherMaps(String cot, long now) {
        if (cot == null) return null;
        String out = cot;
        Matcher event = EVENT_TAG.matcher(out);
        if (event.find()) {
            Matcher stale = STALE.matcher(event.group());
            if (stale.find()) {
                String tag = event.group().substring(0, stale.start())
                        + stale.group(1) + stale.group(2) + cotTime(now + MARKER_STALE_MS) + stale.group(2)
                        + event.group().substring(stale.end());
                out = out.substring(0, event.start()) + tag + out.substring(event.end());
            }
        }
        int detail = out.indexOf(DETAIL);
        if (detail >= 0 && !out.contains("<archive")) {
            int at = detail + DETAIL.length();
            out = out.substring(0, at) + "<archive />" + out.substring(at);
        }
        return out;
    }

    /** CoT time format, as TAK Lite's CotBuilder writes it. */
    private static String cotTime(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    private static boolean connected() {
        try {
            return com.taklite.client.tak.TakManager.getInstance().isConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Same device and marker key, same uid; different devices never collide. */
    static String stableUid(String deviceUid, String markerKey) {
        String seed = (deviceUid == null ? "" : deviceUid) + "|" + markerKey;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
