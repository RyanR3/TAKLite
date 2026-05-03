package com.taklite.app.tak;

import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class TakManager implements TakClient.TakClientListener {

    private static final String TAG = "TakManager";
    private static final long STALE_CHECK_INTERVAL_MS = 30000; // 30 seconds

    private static TakManager instance;

    private TakClient client;
    private String uid;
    private String callsign;
    private String team;
    private String role;
    private boolean connected = false;
    private double lastLat = 0, lastLon = 0;
    private boolean initialPliSent = false;

    private final ConcurrentHashMap<String, TakUser> takUsers = new ConcurrentHashMap<>();
    private final List<TakUserListener> listeners = new ArrayList<>();
    private final List<TakAlertListener> alertListeners = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String activeAlertId; // our own active alert ID for cancellation

    private final Runnable staleCheckRunnable = new Runnable() {
        @Override
        public void run() {
            removeStaleUsers();
            mainHandler.postDelayed(this, STALE_CHECK_INTERVAL_MS);
        }
    };

    public interface TakUserListener {
        void onTakUserUpdated(TakUser user);
        void onTakUserRemoved(String uid);
        void onTakConnectionChanged(boolean connected);
    }

    public interface TakAlertListener {
        void onAlertReceived(String senderUid, String senderCallsign, String alertType, double lat, double lon);
        void onAlertCancelled(String senderUid);
    }

    private TakManager() {}

    public static synchronized TakManager getInstance() {
        if (instance == null) {
            instance = new TakManager();
        }
        return instance;
    }

    public void addListener(TakUserListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(TakUserListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public void addAlertListener(TakAlertListener listener) {
        synchronized (alertListeners) {
            if (!alertListeners.contains(listener)) {
                alertListeners.add(listener);
            }
        }
    }

    public void removeAlertListener(TakAlertListener listener) {
        synchronized (alertListeners) {
            alertListeners.remove(listener);
        }
    }

    public String getUid() {
        return uid;
    }

    public TakUser findUserByUid(String uid) {
        return takUsers.get(uid);
    }

    public TakUser findUserByCallsign(String callsign) {
        for (TakUser user : takUsers.values()) {
            if (callsign.equals(user.getCallsign())) {
                return user;
            }
        }
        return null;
    }

    public void connect(String uid, String callsign, String team, String role,
                        String address, int port,
                        String trustStorePath, String trustStorePassword,
                        String clientCertPath, String clientCertPassword) {
        disconnect();
        this.uid = uid;
        this.callsign = callsign;
        this.team = team != null ? team : "Cyan";
        this.role = role != null ? role : "Team Member";

        client = new TakClient(address, port,
                trustStorePath, trustStorePassword,
                clientCertPath, clientCertPassword,
                this);
        client.start();
        mainHandler.postDelayed(staleCheckRunnable, STALE_CHECK_INTERVAL_MS);
    }

    public void disconnect() {
        mainHandler.removeCallbacks(staleCheckRunnable);
        if (client != null) {
            client.stopClient();
            client = null;
        }
        takUsers.clear();
        connected = false;
        initialPliSent = false;
    }

    public void sendPLI(Location location, String callsign, String team, String role, int battery) {
        if (client == null || !connected) return;

        lastLat = location.getLatitude();
        lastLon = location.getLongitude();

        String xml = CotBuilder.buildPLI(
                uid, callsign, team, role,
                location.getLatitude(), location.getLongitude(), location.getAltitude(),
                location.getBearing(), location.getSpeed(), battery);

        client.sendMessage(xml);
        Log.d(TAG, "PLI sent: " + callsign + " @ " + lastLat + "," + lastLon);
        Log.d(TAG, "PLI XML: " + xml);

        if (!initialPliSent) {
            initialPliSent = true;
            Log.d(TAG, "First real PLI sent to TAK server");
        }
    }

    public void sendAlert(Location location, String alertType) {
        if (client == null || !connected) return;

        String xml = CotBuilder.buildAlert(
                uid, callsign, team, role,
                location.getLatitude(), location.getLongitude(), location.getAltitude(),
                alertType);
        client.sendMessage(xml);

        // Extract alert ID from the XML we just built
        int uidStart = xml.indexOf("uid=\"") + 5;
        int uidEnd = xml.indexOf("\"", uidStart);
        activeAlertId = xml.substring(uidStart, uidEnd);

        Log.d(TAG, "Alert sent: " + alertType + " id=" + activeAlertId);
    }

    public void cancelAlert() {
        if (client == null || !connected || activeAlertId == null) return;

        String xml = CotBuilder.buildAlertCancel(uid, callsign, activeAlertId);
        client.sendMessage(xml);
        Log.d(TAG, "Alert cancelled: " + activeAlertId);
        activeAlertId = null;
    }

    public boolean hasActiveAlert() {
        return activeAlertId != null;
    }

    public boolean isConnected() {
        return connected;
    }

    public Collection<TakUser> getTakUsers() {
        return takUsers.values();
    }

    // --- TakClient.TakClientListener callbacks (called from socket thread) ---

    @Override
    public void onCotReceived(String xml) {
        Log.d(TAG, "CoT received: " + xml.substring(0, Math.min(xml.length(), 200)));
        processCoT(xml);
    }

    @Override
    public void onConnected() {
        connected = true;
        Log.d(TAG, "Connected to TAK server");

        // Send initial PLI with dummy coords (required by some TAK servers like FTS)
        if (uid != null) {
            String cs = callsign != null ? callsign : uid;
            String initCot = CotBuilder.buildPLI(uid, cs, team, role,
                    0, 0, 0, 0, 0, 100);
            client.sendMessage(initCot);
            Log.d(TAG, "Initial PLI sent to register with server");
        }

        mainHandler.post(() -> {
            synchronized (listeners) {
                for (TakUserListener l : listeners) {
                    l.onTakConnectionChanged(true);
                }
            }
        });
    }

    @Override
    public void onDisconnected() {
        connected = false;
        Log.d(TAG, "Disconnected from TAK server");
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (TakUserListener l : listeners) {
                    l.onTakConnectionChanged(false);
                }
            }
        });
    }

    // --- Internal ---

    private void processCoT(String xml) {
        // Try parsing as disconnect first
        String disconnectedUid = CotParser.parseDisconnect(xml);
        if (disconnectedUid != null) {
            Log.d(TAG, "User disconnected: " + disconnectedUid);
            TakUser user = takUsers.get(disconnectedUid);
            if (user != null) {
                user.setStaleTime(System.currentTimeMillis() - 1);
                mainHandler.post(() -> {
                    synchronized (listeners) {
                        for (TakUserListener l : listeners) {
                            l.onTakUserUpdated(user);
                        }
                    }
                });
            }
            return;
        }

        // Try parsing as alert
        CotParser.AlertMessage alert = CotParser.parseAlert(xml);
        if (alert != null) {
            String senderUid = alert.linkedUid != null ? alert.linkedUid : "";
            // Don't process our own alerts
            if (senderUid.equals(uid)) return;

            if (alert.isCancellation) {
                // Update user emergency state
                TakUser sender = findUserByUid(senderUid);
                if (sender != null) {
                    sender.setEmergencyActive(false);
                    sender.setEmergencyType(null);
                }
                mainHandler.post(() -> {
                    synchronized (alertListeners) {
                        for (TakAlertListener l : alertListeners) {
                            l.onAlertCancelled(senderUid);
                        }
                    }
                });
            } else {
                // Update user emergency state
                TakUser sender = findUserByUid(senderUid);
                if (sender != null) {
                    sender.setEmergencyActive(true);
                    sender.setEmergencyType(alert.alertType);
                }
                mainHandler.post(() -> {
                    synchronized (alertListeners) {
                        for (TakAlertListener l : alertListeners) {
                            l.onAlertReceived(senderUid,
                                    alert.senderCallsign != null ? alert.senderCallsign : senderUid,
                                    alert.alertType, alert.lat, alert.lon);
                        }
                    }
                });
            }
            return;
        }

        TakUser user = CotParser.parse(xml);
        if (user == null) return;

        // Don't track our own position
        if (user.getUid().equals(uid)) return;

        takUsers.put(user.getUid(), user);

        mainHandler.post(() -> {
            synchronized (listeners) {
                for (TakUserListener l : listeners) {
                    l.onTakUserUpdated(user);
                }
            }
        });
    }

    private void removeStaleUsers() {
        for (String key : takUsers.keySet()) {
            TakUser user = takUsers.get(key);
            if (user == null) continue;

            if (user.isExpired()) {
                // Remove entirely (5 min past stale)
                takUsers.remove(key);
                final String removedUid = key;
                mainHandler.post(() -> {
                    synchronized (listeners) {
                        for (TakUserListener l : listeners) {
                            l.onTakUserRemoved(removedUid);
                        }
                    }
                });
            } else if (user.isStale()) {
                // Notify listeners so UI can gray out the marker
                mainHandler.post(() -> {
                    synchronized (listeners) {
                        for (TakUserListener l : listeners) {
                            l.onTakUserUpdated(user);
                        }
                    }
                });
            }
        }
    }
}
