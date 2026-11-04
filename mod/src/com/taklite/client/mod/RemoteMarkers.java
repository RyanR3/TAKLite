package com.taklite.client.mod;

import android.util.Log;

import com.taklite.client.tak.TakUser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells markers from people in CoT received from other TAK clients.
 *
 * Stock TAK Lite files an incoming item as a map marker only if its FIRST message
 * carries a {@code <usericon>}; everything else becomes a person (team list, no
 * delete). TAK Aware sends a new marker without one, so its markers became people
 * and were drawn as plain team dots. This classifies the raw CoT instead, then
 * gives a marker without an icon path a stand-in one, which makes TAK Lite's own
 * code file it as a marker and draw its type's MIL-STD-2525 symbol.
 */
public final class RemoteMarkers {

    private static final String TAG = "RemoteMarkers";
    private static final int REMEMBER = 2000;
    private static final Pattern EVENT_TAG = Pattern.compile("<event\\b[^>]*>", Pattern.DOTALL);
    // Runs for every message received, so the patterns are compiled once.
    private static final Pattern UID = attrPattern("uid");
    private static final Pattern TYPE = attrPattern("type");
    private static final Pattern HOW = attrPattern("how");

    /**
     * Icon path given to markers that have none. Without a '/' TAK Lite doesn't look for
     * an iconset file at all and goes straight to the CoT type's MIL-STD-2525 symbol,
     * which avoids a failed asset lookup on every update.
     */
    public static final String STAND_IN_ICONSET = "mod-2525";

    /** uid -> whether its latest CoT described a marker. */
    private static final Map<String, Boolean> markers = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > REMEMBER;
                }
            });

    /**
     * Received markers, kept on the map until deleted like ATAK does. TAK Lite otherwise
     * greys a marker out when it goes stale and removes it 5 minutes later.
     */
    private static final java.util.Set<String> kept = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Oldest first, so the set above can be capped. */
    private static final java.util.Deque<String> keptOrder = new java.util.ArrayDeque<>();
    /** Beyond this many kept markers, the oldest go back to expiring normally. */
    private static final int MAX_KEPT = 5000;

    private static void keepUid(String uid) {
        if (!kept.add(uid)) return;
        synchronized (keptOrder) {
            keptOrder.addLast(uid);
            while (keptOrder.size() > MAX_KEPT) kept.remove(keptOrder.removeFirst());
        }
    }

    private RemoteMarkers() {
    }

    /**
     * Hook: start of TakManager.onCotReceived, with the raw CoT. Runs inside TAK Lite's
     * socket read loop, where an exception closes the connection, so it never throws.
     */
    public static void observe(String cot) {
        try {
            classify(cot);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not classify a message", e);
        }
    }

    private static void classify(String cot) {
        if (cot == null) return;
        Matcher m = EVENT_TAG.matcher(cot);
        if (!m.find()) return;
        String tag = m.group();
        String uid = attr(tag, UID);
        String type = attr(tag, TYPE);
        if (uid == null || type == null) return;
        String how = type.startsWith("a-") ? attr(tag, HOW) : null;
        boolean marker = isMarker(type, how, cot);
        if (marker) {
            keepUid(uid);
        } else {
            kept.remove(uid);
        }
        Boolean before = markers.put(uid, marker);
        if (before == null || before != marker) {
            Log.d(TAG, uid + " type=" + type + (how != null ? " how=" + how : "") + " -> "
                    + (marker ? "marker" : "not a marker"));
        }
    }

    /**
     * A hand-placed map item rather than a person: an atom type ("a-..."), no
     * client identity ({@code <takv>} / {@code <__group>}, which PLIs carry), and
     * placed by hand ({@code how="h-..."}) or flagged as kept ({@code <archive/>})
     * or given a symbol ({@code <usericon>}).
     */
    static boolean isMarker(String type, String how, String cot) {
        if (!type.startsWith("a-")) return false;
        if (cot.contains("<takv") || cot.contains("<__group")) return false;
        return (how != null && how.startsWith("h-")) || cot.contains("<archive") || cot.contains("<usericon");
    }

    /**
     * Hook: start of TakUser.isStale() and isExpired(); true makes both answer false, so a
     * received marker is neither greyed out nor removed when its stale time passes.
     */
    public static boolean keep(TakUser user) {
        String uid = user.getUid();
        return uid != null && !user.isDrone() && kept.contains(uid);
    }

    /**
     * Hook: a received marker was deleted from the radial menu. TAK Lite only took it off
     * the map; drop it from TakManager too, or it would be drawn again when the map comes
     * back. If the sender sends it again, it reappears, as in ATAK.
     */
    public static void onDeleted(String uid) {
        if (uid == null) return;
        kept.remove(uid);
        try {
            com.taklite.client.tak.TakManager.getInstance().modForgetUser(uid);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not forget deleted marker " + uid, e);
        }
    }

    /** Drops every kept received marker from TakManager (for Clear All Markers). */
    static void forgetAll() {
        for (String uid : new java.util.ArrayList<>(kept)) onDeleted(uid);
    }

    /** Hook: TakManager.processCoT, right after CotParser.parse. Never throws (see {@link #observe}). */
    public static void adjust(TakUser user) {
        try {
            if (user != null && !user.isDrone()) adjustIcon(user);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not adjust a received item", e);
        }
    }

    private static void adjustIcon(TakUser user) {
        String path = user.getIconsetPath();
        if (path != null && !path.isEmpty()) {
            // Already filed as a marker by TAK Lite; just spare it a lookup that can't succeed.
            if (!path.equals(STAND_IN_ICONSET) && !iconsetExists(path)) user.setIconsetPath(STAND_IN_ICONSET);
            return;
        }
        String uid = user.getUid();
        if (uid != null && Boolean.TRUE.equals(markers.get(uid))) user.setIconsetPath(STAND_IN_ICONSET);
    }

    /**
     * {@code path}, or the stand-in if it names an icon TAK Lite doesn't ship. TAK Lite
     * redraws markers every 30 s, and a missing icon is looked for (and logged) each time;
     * TAK Aware, for one, sends "/Ground track/a-h-G", which has no file. Both end up with
     * the CoT type's MIL-STD-2525 symbol.
     */
    public static String iconsetOrStandIn(String path) {
        return path == null || path.isEmpty() || iconsetExists(path) ? path : STAND_IN_ICONSET;
    }

    /** iconset path -> whether TAK Lite has that icon. */
    private static final Map<String, Boolean> iconsets = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 500;
                }
            });

    /**
     * Whether TAK Lite's icon lookup would find a file for {@code path}. Only paths of the
     * form "group/folder/icon" are checked: for those TAK Lite opens assets/iconsets/folder/icon.
     * Shorter paths go through TAK Lite's own group table and are assumed to exist.
     */
    private static boolean iconsetExists(String path) {
        int slash = path.indexOf('/');
        if (slash < 0) return true;
        String rest = path.substring(slash + 1);
        if (!rest.contains("/")) return true;
        Boolean known = iconsets.get(path);
        if (known != null) return known;
        android.content.Context app = com.taklite.client.mod.imports.Imports.app();
        if (app == null) return true;
        boolean exists;
        try (java.io.InputStream in = app.getAssets().open("iconsets/" + rest)) {
            exists = true;
        } catch (java.io.IOException e) {
            exists = false;
        }
        iconsets.put(path, exists);
        if (!exists) Log.d(TAG, "No icon for \"" + path + "\"; using the CoT type's symbol");
        return exists;
    }

    /** Attribute from a start tag; TAK clients use either quote style. */
    static String attr(String tag, Pattern attribute) {
        Matcher m = attribute.matcher(tag);
        return m.find() ? m.group(2) : null;
    }

    private static Pattern attrPattern(String name) {
        return Pattern.compile("\\s" + name + "\\s*=\\s*(['\"])(.*?)\\1", Pattern.DOTALL);
    }
}
