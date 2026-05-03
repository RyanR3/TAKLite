package com.taklite.app.tak;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import com.taklite.app.MainActivity;
import com.taklite.app.SettingsActivity;

public class TakLocationService extends Service {

    private static final String TAG = "TakLocationService";
    private static final String CHANNEL_ID = "TakLocationChannel";
    private static final int NOTIFICATION_ID = 10;

    public static final String ACTION_START = "com.taklite.app.START_TAK_LOCATION";
    public static final String ACTION_STOP = "com.taklite.app.STOP_TAK_LOCATION";

    private static TakLocationService instance;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private TakManager takManager;
    private PowerManager.WakeLock wakeLock;

    private String callsign;
    private String team;
    private String role;

    // Panic button — 5 rapid volume presses in 3 seconds (screen on)
    private static final int PANIC_PRESS_COUNT = 5;
    private static final long PANIC_WINDOW_MS = 3000;
    private static final long DEBOUNCE_MS = 200;
    private long[] pressTimes = new long[PANIC_PRESS_COUNT];
    private int pressIndex = 0;
    private long lastPressTime = 0;
    private Location lastLocation;

    // Volume detection (screen on: accessibility + broadcast)
    private BroadcastReceiver volumeReceiver;

    // Power button detection — 5 screen events (on+off) in 15 seconds
    private BroadcastReceiver screenOnReceiver;
    private static final int POWER_PRESS_COUNT = 5;
    private static final long POWER_WINDOW_MS = 15000;
    private long[] powerPressTimes = new long[POWER_PRESS_COUNT];
    private int powerPressIndex = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        acquireWakeLock();
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

        // Read TAK settings
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

        // Connect TakManager
        takManager = TakManager.getInstance();
        takManager.connect(uid, callsign, team, role, address, port, trustStore, certPw, clientCert, certPw);

        // Start location updates
        startLocationUpdates();

        // Setup panic button detection
        setupVolumeBroadcast();     // screen-on: volume broadcast + accessibility
        setupPowerButtonDetection(); // screen-off: 3 power presses in 2 seconds

        Log.d(TAG, "TAK location service started, connecting to " + address + ":" + port);
        return START_STICKY;
    }

    @Nullable
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
            try {
                unregisterReceiver(volumeReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister volume receiver", e);
            }
            volumeReceiver = null;
        }

        if (screenOnReceiver != null) {
            try {
                unregisterReceiver(screenOnReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister screen receiver", e);
            }
            screenOnReceiver = null;
        }

        if (takManager != null) {
            takManager.disconnect();
        }

        releaseWakeLock();
        Log.d(TAG, "TAK location service destroyed");
    }

    public static boolean isRunning() {
        return instance != null;
    }

    // --- Called by PanicAccessibilityService (works with screen on/lock screen) ---

    public static void onVolumeKeyFromAccessibility() {
        if (instance != null) {
            instance.onVolumePressDetected("accessibility");
        }
    }

    // --- Location ---

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No location permission, cannot send PLI");
            return;
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null && takManager != null) {
                    lastLocation = location;
                    takManager.sendPLI(location, callsign, team, role, getBatteryLevel());
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, getMainLooper());
    }

    private int getBatteryLevel() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                return (int) (level * 100 / (float) scale);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get battery level", e);
        }
        return 100;
    }

    // --- Panic Button: Volume (screen on) ---

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(volumeReceiver, filter);
        }
        Log.d(TAG, "Volume broadcast receiver registered");
    }

    private synchronized void onVolumePressDetected(String source) {
        long now = System.currentTimeMillis();

        // Debounce rapid duplicate events
        if (now - lastPressTime < DEBOUNCE_MS) {
            return;
        }
        lastPressTime = now;

        pressTimes[pressIndex % PANIC_PRESS_COUNT] = now;
        pressIndex++;
        Log.d(TAG, "Panic: press #" + pressIndex + " (" + source + ")");

        if (pressIndex >= PANIC_PRESS_COUNT) {
            long oldest = pressTimes[(pressIndex) % PANIC_PRESS_COUNT];
            if (now - oldest <= PANIC_WINDOW_MS) {
                new Handler(Looper.getMainLooper()).post(this::triggerPanicAlert);
                pressIndex = 0;
                lastPressTime = 0;
                for (int i = 0; i < PANIC_PRESS_COUNT; i++) pressTimes[i] = 0;
            }
        }
    }

    // --- Panic Button: Power button — 3 SCREEN_OFF events in 5 seconds ---

    private void setupPowerButtonDetection() {
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "Panic: screen broadcast: " + action);
                // Count both SCREEN_ON and SCREEN_OFF — every power press counts
                if (Intent.ACTION_SCREEN_OFF.equals(action) || Intent.ACTION_SCREEN_ON.equals(action)) {
                    onPowerButtonPress();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOnReceiver, filter, Context.RECEIVER_EXPORTED);
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
            long oldest = powerPressTimes[(powerPressIndex) % POWER_PRESS_COUNT];
            if (now - oldest <= POWER_WINDOW_MS) {
                Log.d(TAG, "Panic: power button pattern detected!");
                new Handler(Looper.getMainLooper()).post(this::triggerPanicAlert);
                powerPressIndex = 0;
                for (int i = 0; i < POWER_PRESS_COUNT; i++) powerPressTimes[i] = 0;
            }
        }
    }

    // --- Alert ---

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
            if (v != null) v.vibrate(200);
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
            v.vibrate(new long[]{0, 200, 100, 200, 100, 200}, -1);
        }
    }

    // --- Notification ---

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "TAK Location Reporting",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Reports location to TAK server");
            channel.setShowBadge(false);

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, TakLocationService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        );

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
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, createNotification(text));
        }
    }

    // --- Wake Lock ---

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TAKLite:TakLocation");
            wakeLock.acquire();
            Log.d(TAG, "Wake lock acquired");
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "Wake lock released");
        }
    }
}
