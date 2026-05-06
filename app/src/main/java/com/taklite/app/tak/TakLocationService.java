package com.taklite.app.tak;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.taklite.app.MainActivity;
import com.taklite.app.SettingsActivity;

public class TakLocationService extends Service {
    public static final String ACTION_START = "com.taklite.app.START_TAK_LOCATION";
    public static final String ACTION_STOP = "com.taklite.app.STOP_TAK_LOCATION";
    private static final String TAG = "TakLocationService";
    private static final String CHANNEL_ID = "TakLocationChannel";
    private static final int NOTIFICATION_ID = 10;
    private static final int PANIC_PRESS_COUNT = 5;
    private static final long PANIC_WINDOW_MS = 3000;
    private static final long DEBOUNCE_MS = 200;
    private static final int POWER_PRESS_COUNT = 5;
    private static final long POWER_WINDOW_MS = 15000;
    private static final long BATTERY_CACHE_MS = 60000; // 1 minute

    // Dynamic PLI reporting — matches ATAK dynamic reporting strategy
    private static final long PLI_STATIONARY_RATE_MS = 180000; // 180s when not moving
    private static final long PLI_FASTEST_MS = 2000;           // 2s — fastest while moving (high urgency)
    private static final long PLI_SLOWEST_MOVING_MS = 20000;   // 20s — slowest while moving (steady)
    private static final float STATIONARY_SPEED_THRESHOLD = 2.0f; // m/s — below this = stationary
    private static final float HEADING_CHANGE_FAST = 30.0f;    // degrees — triggers faster reporting

    private static TakLocationService instance;

    private TakManager takManager;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Location lastLocation;
    private long lastPliSendTime = 0;
    private float lastSentBearing = 0f;
    private String callsign;
    private String team;
    private String role;
    private int cachedBatteryLevel = 100;
    private long lastBatteryCheckTime = 0;
    private BroadcastReceiver volumeReceiver;
    private BroadcastReceiver screenOnReceiver;

    private long[] pressTimes = new long[PANIC_PRESS_COUNT];
    private int pressIndex = 0;
    private long lastPressTime = 0;
    private long[] powerPressTimes = new long[POWER_PRESS_COUNT];
    private int powerPressIndex = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.d(TAG, "Stopping TAK location service");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, createNotification("TAK: Connecting..."));

        String address = SettingsActivity.getTakServerAddress(this);
        int port = SettingsActivity.getTakServerPort(this);
        String trustStore = SettingsActivity.getTakTrustStorePath(this);
        String clientCert = SettingsActivity.getTakClientCertPath(this);
        String certPw = SettingsActivity.getTakCertPassword(this);
        String uid = SettingsActivity.getTakUid(this);
        callsign = SettingsActivity.getTakCallsign(this);
        team = SettingsActivity.getTakTeam(this);
        role = SettingsActivity.getTakRole(this);

        if (address == null || address.isEmpty()) {
            Log.w(TAG, "No TAK server address, stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        takManager = TakManager.getInstance();
        takManager.connect(uid, callsign, team, role, address, port, trustStore, certPw, clientCert, certPw);

        startLocationUpdates();
        setupVolumeBroadcast();
        setupPowerButtonDetection();

        Log.d(TAG, "TAK location service started, connecting to " + address + ":" + port);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "App swiped away — service continues running");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (volumeReceiver != null) {
            try { unregisterReceiver(volumeReceiver); } catch (Exception e) { Log.w(TAG, "Failed to unregister volume receiver", e); }
            volumeReceiver = null;
        }
        if (screenOnReceiver != null) {
            try { unregisterReceiver(screenOnReceiver); } catch (Exception e) { Log.w(TAG, "Failed to unregister screen receiver", e); }
            screenOnReceiver = null;
        }
        if (takManager != null) takManager.disconnect();
        Log.d(TAG, "TAK location service destroyed");
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static void onVolumeKeyFromAccessibility() {
        if (instance != null) {
            instance.onVolumePressDetected("accessibility");
        }
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, "android.permission.ACCESS_FINE_LOCATION") != 0) {
            Log.w(TAG, "No location permission, cannot send PLI");
            return;
        }
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        // Request GPS at 2s so we always have fresh data for dynamic rate decisions
        LocationRequest locationRequest = new LocationRequest.Builder(100, 2000)
                .setMinUpdateIntervalMillis(1000)
                .build();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null && takManager != null) {
                    lastLocation = location;
                    long now = System.currentTimeMillis();
                    long elapsed = now - lastPliSendTime;
                    long requiredInterval = computeDynamicPliInterval(location);

                    if (lastPliSendTime == 0 || elapsed >= requiredInterval) {
                        lastPliSendTime = now;
                        lastSentBearing = location.hasBearing() ? location.getBearing() : lastSentBearing;
                        takManager.sendPLI(location, callsign, team, role, getCachedBatteryLevel());
                    }
                }
            }
        };
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, getMainLooper());
    }

    /**
     * ATAK-style dynamic reporting: evaluates speed and heading change to pick
     * an interval between FASTEST (2s) and SLOWEST_MOVING (20s),
     * or STATIONARY_RATE (180s) when not moving.
     */
    private long computeDynamicPliInterval(Location location) {
        float speed = location.hasSpeed() ? location.getSpeed() : 0f;

        // Stationary — below speed threshold
        if (speed < STATIONARY_SPEED_THRESHOLD) {
            return PLI_STATIONARY_RATE_MS;
        }

        // Moving — check heading delta for sharp turns
        float currentBearing = location.hasBearing() ? location.getBearing() : lastSentBearing;
        float headingDelta = Math.abs(currentBearing - lastSentBearing);
        if (headingDelta > 180f) headingDelta = 360f - headingDelta;

        // urgency: 0.0 = steady movement → slow rate, 1.0 = sharp turn/high speed → fast rate
        float headingUrgency = Math.min(headingDelta / HEADING_CHANGE_FAST, 1.0f);
        float speedUrgency = Math.min((speed - STATIONARY_SPEED_THRESHOLD) / 28.0f, 1.0f);
        float urgency = Math.max(headingUrgency, speedUrgency);

        // Interpolate: urgency 0 → SLOWEST_MOVING (20s), urgency 1 → FASTEST (2s)
        long interval = PLI_SLOWEST_MOVING_MS - (long) (urgency * (PLI_SLOWEST_MOVING_MS - PLI_FASTEST_MS));

        return Math.max(PLI_FASTEST_MS, Math.min(interval, PLI_SLOWEST_MOVING_MS));
    }

    private int getCachedBatteryLevel() {
        long now = System.currentTimeMillis();
        if (now - lastBatteryCheckTime >= BATTERY_CACHE_MS) {
            lastBatteryCheckTime = now;
            try {
                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = registerReceiver(null, ifilter);
                if (batteryStatus != null) {
                    int level = batteryStatus.getIntExtra("level", -1);
                    int scale = batteryStatus.getIntExtra("scale", -1);
                    cachedBatteryLevel = (int) ((level * 100) / scale);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to get battery level", e);
            }
        }
        return cachedBatteryLevel;
    }

    private void setupVolumeBroadcast() {
        volumeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) {
                    Log.d(TAG, "Panic: volume broadcast");
                    onVolumePressDetected("broadcast");
                }
            }
        };
        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(volumeReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(volumeReceiver, filter);
        }
        Log.d(TAG, "Volume broadcast receiver registered");
    }

    private synchronized void onVolumePressDetected(String source) {
        long now = System.currentTimeMillis();
        if (now - lastPressTime < DEBOUNCE_MS) return;
        lastPressTime = now;
        pressTimes[pressIndex % PANIC_PRESS_COUNT] = now;
        pressIndex++;
        Log.d(TAG, "Panic: press #" + pressIndex + " (" + source + ")");
        if (pressIndex >= PANIC_PRESS_COUNT) {
            long oldest = pressTimes[pressIndex % PANIC_PRESS_COUNT];
            if (now - oldest <= PANIC_WINDOW_MS) {
                new Handler(Looper.getMainLooper()).post(() -> triggerPanicAlert());
                pressIndex = 0;
                lastPressTime = 0;
                for (int i = 0; i < PANIC_PRESS_COUNT; i++) pressTimes[i] = 0;
            }
        }
    }

    private void setupPowerButtonDetection() {
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "Panic: screen broadcast: " + action);
                if (Intent.ACTION_SCREEN_OFF.equals(action) || Intent.ACTION_SCREEN_ON.equals(action)) {
                    onPowerButtonPress();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOnReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOnReceiver, filter);
        }
        Log.d(TAG, "Power button detection registered (5 events in 15s)");
    }

    private synchronized void onPowerButtonPress() {
        long now = System.currentTimeMillis();
        powerPressTimes[powerPressIndex % POWER_PRESS_COUNT] = now;
        powerPressIndex++;
        Log.d(TAG, "Panic: power press #" + powerPressIndex);
        if (powerPressIndex >= POWER_PRESS_COUNT) {
            long oldest = powerPressTimes[powerPressIndex % POWER_PRESS_COUNT];
            if (now - oldest <= POWER_WINDOW_MS) {
                Log.d(TAG, "Panic: power button pattern detected!");
                new Handler(Looper.getMainLooper()).post(() -> triggerPanicAlert());
                powerPressIndex = 0;
                for (int i = 0; i < POWER_PRESS_COUNT; i++) powerPressTimes[i] = 0;
            }
        }
    }

    private void triggerPanicAlert() {
        if (takManager == null || !takManager.isConnected()) {
            Log.w(TAG, "Panic triggered but not connected");
            return;
        }
        if (takManager.hasActiveAlert()) {
            takManager.cancelAlert();
            updateNotification("TAK: Alert cancelled");
            Log.d(TAG, "Panic button: alert cancelled");
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(DEBOUNCE_MS);
            return;
        }
        if (lastLocation == null) {
            Log.w(TAG, "Panic triggered but no location");
            return;
        }
        takManager.sendAlert(lastLocation, "911 Alert");
        updateNotification("TAK: 911 ALERT ACTIVE");
        Log.d(TAG, "Panic button: 911 alert sent");
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(new long[]{0, DEBOUNCE_MS, 100, DEBOUNCE_MS, 100, DEBOUNCE_MS}, -1);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "TAK Location Reporting", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Reports location to TAK server");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, TakLocationService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TAK Lite")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
                .setNumber(0)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, createNotification(text));
    }

}
