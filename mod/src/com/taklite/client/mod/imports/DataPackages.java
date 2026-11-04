package com.taklite.client.mod.imports;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.taklite.client.SettingsActivity;

import com.taklite.client.MainActivity;
import com.taklite.client.mod.DarkDialog;
import com.taklite.client.mod.ModUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Data packages from the TAK server, downloaded with the device's TAK client
 * certificate, checked against their SHA-256 and imported.
 *
 * <ul>
 *   <li>Packages sent to this device: ATAK/iTAK/WinTAK upload the package to the
 *       server and send a CoT "file transfer request" (type b-f-t-r) whose
 *       {@code <fileshare>} gives the download URL (usually
 *       https://server:8443/Marti/sync/content?hash=...), size and SHA-256.</li>
 *   <li>Packages stored on the server: listed with the same query ATAK's
 *       "Data Package server query" uses, /Marti/sync/search?keywords=missionpackage.</li>
 * </ul>
 *
 * A package sent to this device downloads on its own over https. Plain http is only
 * used for addresses on the local network (ATAK sending straight from its own phone)
 * and only after the user agrees; http from anywhere else is refused.
 */
final class DataPackages {

    private static final String TAG = "DataPackages";
    private static final long MAX_PACKAGE_BYTES = 200L * 1024 * 1024;
    /**
     * "hash\timport id" lines for packages already received, so a re-send is skipped
     * while that import still exists (deleting it lets the package come in again).
     */
    private static final String KEY_RECEIVED = "mod_received_packages";
    private static final int REMEMBER_RECEIVED = 200;
    private static final int TIMEOUT_MS = 20000;
    /** TAK Server's HTTPS API port (TAK Lite's own server calls use it too). */
    private static final int API_PORT = 8443;

    /** Downloads in progress, by package key, with everyone waiting for each one's result. */
    private static final Map<String, List<Done>> inFlight = new HashMap<>();
    /** Local http shares the user said no to; not asked about again until TAK Lite restarts. */
    private static final Set<String> declined = new HashSet<>();
    /** Last "refused / failed / waiting" notice per package, so a misbehaving sender can't flood the shade. */
    private static final Map<String, Long> lastNotice = new HashMap<>();
    private static final long NOTICE_INTERVAL_MS = 60_000;

    private DataPackages() {
    }

    /** Called on a worker thread when a download+import ends: entry on success, else error. */
    interface Done {
        void finished(ImportStore.Entry entry, String error);
    }

    /** A package listed on the TAK server. */
    static final class ServerPackage {
        String name;
        String hash;
        String submitter;
        String submitted;
        long size;
    }

    static void onFileShare(final Context context, String cot) {
        final XmlNode event;
        try {
            event = XmlNode.parse(new java.io.ByteArrayInputStream(cot.getBytes("UTF-8")));
        } catch (IOException e) {
            return;
        }
        if (!"event".equals(event.name) || !"b-f-t-r".equals(event.attr("type"))) return;
        XmlNode detail = event.child("detail");
        final XmlNode share = detail == null ? null : detail.child("fileshare");
        if (share == null || share.attr("senderUrl") == null) return;

        final String url = share.attr("senderUrl");
        final String sha256 = lower(share.attr("sha256"));
        final String filename = share.attr("filename") != null ? share.attr("filename") : "package.zip";
        final String name = share.attr("name") != null && !share.attr("name").isEmpty()
                ? share.attr("name") : Importer.stripExtension(filename);
        final String sender = share.attr("senderCallsign") != null ? share.attr("senderCallsign") : "a TAK user";

        final long size = parseLong(share.attr("sizeInBytes"));

        final String dedupeKey = sha256 != null ? sha256 : url;
        final Done announce = (entry, error) -> {
            if (entry != null) {
                Imports.announce("Data package received",
                        "\"" + entry.name + "\" from " + sender + ": " + entry.addedText());
            } else {
                notice(dedupeKey, "Data package failed", "\"" + name + "\" from " + sender + ": " + error);
            }
        };
        synchronized (inFlight) {
            if (inFlight.containsKey(dedupeKey) || declined.contains(dedupeKey) || alreadyReceived(context, dedupeKey)) {
                Log.d(TAG, "Ignoring repeat of " + name);
                return;
            }
            inFlight.put(dedupeKey, new ArrayList<>());
        }
        final Runnable fetch = () -> {
            // The notice listener joins only once the download really starts, so a refused
            // or declined share doesn't also report "failed".
            synchronized (inFlight) {
                List<Done> waiting = inFlight.get(dedupeKey);
                if (waiting != null) waiting.add(announce);
            }
            fetchAndImport(context, url, sha256, filename, name, "Data package from " + sender, size, dedupeKey);
        };

        final URL link;
        try {
            link = new URL(url);
        } catch (MalformedURLException e) {
            refuse(dedupeKey, name, sender, "the download link is not valid");
            return;
        }
        String protocol = link.getProtocol().toLowerCase(Locale.US);
        if (protocol.equals("https")) {
            fetch.run();
        } else if (protocol.equals("http")) {
            // Looking up a host name can take a while; keep it off the network thread.
            new Thread(() -> {
                if (isLocalNetwork(link.getHost())) {
                    askBeforeHttp(dedupeKey, name, sender, link.getHost(), size, fetch);
                } else {
                    refuse(dedupeKey, name, sender, "it was offered over an unencrypted (http) link "
                            + "outside the local network");
                }
            }, "DataPackageShare").start();
        } else {
            refuse(dedupeKey, name, sender, "unsupported download link (" + protocol + ")");
        }
    }

    private static void refuse(String key, String name, String sender, String reason) {
        Log.w(TAG, "Refused data package " + name + ": " + reason);
        // Whoever else was waiting (a server-list tap on the same package) hears the same.
        finish(key, null, "refused: " + reason, true);
        notice(key, "Data package refused", "\"" + name + "\" from " + sender + ": " + reason + ".");
    }

    /** A notification for {@code key}, at most once a minute (the log always gets it). */
    private static void notice(String key, String title, String text) {
        synchronized (lastNotice) {
            long now = android.os.SystemClock.elapsedRealtime();
            Long last = lastNotice.get(key);
            if (last != null && now - last < NOTICE_INTERVAL_MS) {
                Log.i(TAG, title + " (notification suppressed, repeat): " + text);
                return;
            }
            if (lastNotice.size() > 200) lastNotice.clear();
            lastNotice.put(key, now);
        }
        Imports.announce(title, text);
    }

    /** Asks on the map screen, now or when TAK Lite is next opened, before an http download. */
    private static void askBeforeHttp(final String key, final String name, final String sender,
                                      final String host, final long size, final Runnable fetch) {
        Imports.whenOnScreen((MainActivity activity) -> {
            final boolean[] answered = new boolean[1];
            final DarkDialog d = new DarkDialog(activity, "Data Package");
            d.addText(sender + " sent \"" + name + "\"" + (size > 0 ? " (" + ModUtil.formatSize(size) + ")" : "")
                    + " from " + host + " on your local network. The link is not encrypted. Download it?");
            d.dialog.setOnDismissListener(dialog -> {
                if (answered[0]) return;
                synchronized (inFlight) {
                    declined.add(key);
                }
                finish(key, null, "declined on this phone", true);
            });
            d.addAction("IGNORE", DarkDialog.COLOR_LABEL, v -> d.dismiss());
            d.addAction("DOWNLOAD", DarkDialog.COLOR_ACCENT, v -> {
                answered[0] = true;
                d.dismiss();
                fetch.run();
            });
            d.show();
        }, () -> notice(key, "Data package waiting",
                "\"" + name + "\" from " + sender + ": open TAK Lite to download it."));
    }

    /**
     * True if every address of {@code host} is private, link-local or loopback: the local
     * network (or, for a VPN using private addresses, a network you chose to join).
     */
    static boolean isLocalNetwork(String host) {
        if (host == null || host.isEmpty()) return false;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;
            for (InetAddress a : addresses) {
                boolean uniqueLocal = a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC;
                if (!(a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isLoopbackAddress() || uniqueLocal)) {
                    return false;
                }
            }
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /**
     * Downloads a package listed on the server and imports it (replacing an earlier copy).
     * If that package is already downloading (a repeat tap, or the same package arriving
     * over TAK), {@code done} simply joins the waiting list and hears the same result.
     */
    static void importFromServer(Context context, ServerPackage p, Done done) {
        String url = apiBase(context) + "/Marti/sync/content?hash=" + p.hash;
        String key = p.hash.toLowerCase(Locale.US);
        synchronized (inFlight) {
            List<Done> waiting = inFlight.get(key);
            if (waiting != null) {
                waiting.add(done);
                return;
            }
            inFlight.put(key, new ArrayList<>(java.util.Collections.singletonList(done)));
        }
        String origin = "Server" + (p.submitter.isEmpty() ? "" : ", uploaded by " + p.submitter);
        // Server names usually already end in .zip ("Trails.zip"); don't make it "Trails.zip.zip".
        String filename = p.name.toLowerCase(Locale.US).endsWith(".zip") ? p.name : p.name + ".zip";
        fetchAndImport(context, url, key, filename, Importer.stripExtension(p.name), origin, p.size, key);
    }

    /**
     * Downloads, verifies and imports on a background thread. {@code key} must already be
     * in {@link #inFlight}; everyone waiting on it is told the result and it is removed.
     */
    private static void fetchAndImport(final Context context, final String url, final String sha256,
                                       final String filename, final String name, final String origin,
                                       long size, final String key) {
        if (size > MAX_PACKAGE_BYTES) {
            finish(key, null, "too large (" + (size >> 20) + " MB, limit " + (MAX_PACKAGE_BYTES >> 20) + " MB)", true);
            return;
        }
        new Thread(() -> {
            File tmp = new File(context.getCacheDir(), "package-" + System.nanoTime() + ".zip");
            ImportStore.Entry entry = null;
            String error = null;
            try {
                download(context, url, tmp);
                Log.i(TAG, "Downloaded " + filename + ": " + tmp.length() + " bytes");
                if (sha256 != null && !sha256.equals(Importer.sha256(tmp))) {
                    throw new IOException("download is corrupt (SHA-256 mismatch)");
                }
                entry = Imports.importFile(tmp, filename, origin);
                Log.i(TAG, "Imported " + filename + " as \"" + entry.name + "\": " + entry.contents());
                remember(context, key, entry.id);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Data package " + name + " failed", e);
                error = e.getMessage();
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "Data package " + name + " failed", e);
                error = "too large to import on this phone";
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            finish(key, entry, error, true);
        }, "DataPackageDownload").start();
    }

    /** Ends {@code key}'s download: forgets it and tells everyone waiting (if {@code tell}). */
    private static void finish(String key, ImportStore.Entry entry, String error, boolean tell) {
        List<Done> waiting;
        synchronized (inFlight) {
            waiting = inFlight.remove(key);
        }
        if (!tell || waiting == null) return;
        for (Done d : waiting) {
            try {
                d.finished(entry, error);
            } catch (RuntimeException e) {
                Log.w(TAG, "Download listener failed", e);
            }
        }
    }

    /** Lists the data packages on the TAK server. Blocking; call off the main thread. */
    static List<ServerPackage> queryServer(Context context) throws IOException {
        String url = apiBase(context) + "/Marti/sync/search?keywords=missionpackage";
        HttpURLConnection conn = open(context, url);
        String body;
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("server answered HTTP " + code);
            body = new String(ModUtil.readAll(conn.getInputStream(), 10_000_000, "server list too large"), "UTF-8");
        } finally {
            conn.disconnect();
        }
        return parseServerList(body);
    }

    /** Parses /Marti/sync/search JSON: {"resultCount":n,"results":[{"Name","Hash",...}]}. */
    static List<ServerPackage> parseServerList(String json) throws IOException {
        List<ServerPackage> out = new ArrayList<>();
        try {
            org.json.JSONArray results = new org.json.JSONObject(json).optJSONArray("results");
            if (results == null) return out;
            for (int i = 0; i < results.length(); i++) {
                org.json.JSONObject r = results.optJSONObject(i);
                if (r == null) continue;
                ServerPackage p = new ServerPackage();
                p.hash = r.optString("Hash", r.optString("hash", ""));
                if (p.hash.isEmpty()) continue;
                p.name = r.optString("Name", r.optString("name", p.hash.substring(0, Math.min(8, p.hash.length()))));
                p.submitter = r.optString("SubmissionUser", r.optString("CreatorUid", ""));
                p.submitted = r.optString("SubmissionDateTime", "");
                p.size = r.optLong("Size", r.optLong("size", 0));
                out.add(p);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer from server: " + e.getMessage(), e);
        }
        return out;
    }

    /** True if this package (by SHA-256) was imported and that import still exists. */
    static boolean isImported(Context context, String hash) {
        return hash != null && alreadyReceived(context, hash.toLowerCase(Locale.US));
    }

    private static String apiBase(Context context) throws IllegalStateException {
        String server = SettingsActivity.getTakServerAddress(context);
        if (server == null || server.isEmpty()) throw new IllegalStateException("No TAK server configured");
        return "https://" + server + ":" + API_PORT;
    }

    /**
     * Downloads with the TAK client certificate. If the URL names a host the phone
     * can't reach (servers often advertise an internal name), retries against the
     * server address configured in TAK Lite.
     */
    private static void download(Context context, String url, File dest) throws IOException {
        try {
            downloadOnce(context, url, dest);
        } catch (IOException first) {
            String server = SettingsActivity.getTakServerAddress(context);
            URL u = new URL(url);
            // An http link comes straight from the sender's phone, not the server.
            if (!"https".equalsIgnoreCase(u.getProtocol())) throw first;
            if (server == null || server.isEmpty() || server.equalsIgnoreCase(u.getHost())) throw first;
            String retry = new URL(u.getProtocol(), server, u.getPort(), u.getFile()).toString();
            Log.w(TAG, "Download from " + u.getHost() + " failed (" + first.getMessage() + "), trying " + server);
            downloadOnce(context, retry, dest);
        }
    }

    /** A connection that presents the TAK client certificate for https URLs. */
    private static HttpURLConnection open(Context context, String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            https.setSSLSocketFactory(mutualTls(context).getSocketFactory());
            // Same as TAK Lite's own server API calls: the server certificate must chain
            // to the TAK trust store, but the name it was issued for isn't checked.
            https.setHostnameVerifier((host, session) -> true);
        } else {
            // A local http link must not bounce the download somewhere else.
            conn.setInstanceFollowRedirects(false);
        }
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        return conn;
    }

    private static void downloadOnce(Context context, String url, File dest) throws IOException {
        HttpURLConnection conn = open(context, url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("server answered HTTP " + code);
            long length = conn.getContentLengthLong();
            if (length > MAX_PACKAGE_BYTES) throw new IOException("package is too large");
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                ModUtil.copy(in, out, MAX_PACKAGE_BYTES, "package is too large");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static SSLContext mutualTls(Context context) throws IOException {
        String trustPath = SettingsActivity.getTakTrustStorePath(context);
        String certPath = SettingsActivity.getTakClientCertPath(context);
        String password = SettingsActivity.getTakCertPassword(context);
        if (trustPath == null || trustPath.isEmpty() || certPath == null || certPath.isEmpty()) {
            throw new IOException("TAK Lite is not enrolled with a TAK server");
        }
        char[] pw = (password == null ? "" : password).toCharArray();
        try {
            KeyStore trust = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(trustPath)) {
                trust.load(in, pw);
            }
            KeyStore client = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(certPath)) {
                client.load(in, pw);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(client, pw);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return ssl;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("could not load TAK certificates: " + e.getMessage(), e);
        }
    }

    private static boolean alreadyReceived(Context context, String key) {
        for (String line : prefs(context).getString(KEY_RECEIVED, "").split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length == 2 && parts[0].equals(key)) return Imports.store().get(parts[1]) != null;
        }
        return false;
    }

    private static synchronized void remember(Context context, String key, String importId) {
        List<String> lines = new ArrayList<>(Arrays.asList(prefs(context).getString(KEY_RECEIVED, "").split("\n")));
        lines.removeIf(line -> line.isEmpty() || line.startsWith(key + "\t"));
        lines.add(key + "\t" + importId);
        while (lines.size() > REMEMBER_RECEIVED) lines.remove(0);
        prefs(context).edit().putString(KEY_RECEIVED, String.join("\n", lines)).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return ModUtil.prefs(context);
    }

    private static String lower(String s) {
        return s == null || s.isEmpty() ? null : s.toLowerCase(Locale.US);
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
