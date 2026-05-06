package com.taklite.app;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import com.taklite.app.tak.TakLocationService;
import com.taklite.app.tak.TakManager;
import com.taklite.app.tak.TakUser;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements TakManager.TakUserListener, TakManager.TakAlertListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 101;

    private MapView mapView;
    private Marker selfMarker;
    private FloatingActionButton alertButton;
    private View connectionStatusDot;
    private TextView connectionStatusText;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private TakManager takManager;

    private float lastBearing = 0f;
    private double lastSelfLat = Double.NaN;
    private double lastSelfLon = Double.NaN;
    private boolean hasCenteredOnSelf = false;
    private int currentMapType = 0;
    private final Map<String, Marker> takUserMarkers = new HashMap<>();
    private final Map<String, Marker> alertMarkers = new HashMap<>();
    private final Map<String, DroppedMarker> droppedMarkers = new HashMap<>();
    private String pendingMarkerAffiliation = null;
    private RadialMenuView activeRadialMenu = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        Configuration.getInstance().setUserAgentValue(getPackageName());
        setContentView(R.layout.activity_main);

        connectionStatusDot = findViewById(R.id.connectionStatusDot);
        connectionStatusText = findViewById(R.id.connectionStatusText);
        findViewById(R.id.settingsButton).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.shutdownButton).setOnClickListener(v -> confirmShutdown());

        mapView = findViewById(R.id.mapView);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getZoomController().setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER);
        mapView.getController().setZoom(15.0d);

        // Start map at last known location to avoid showing (0,0)
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            Location lastLoc = null;
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                lastLoc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                if (lastLoc == null) {
                    lastLoc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                }
            }
            if (lastLoc != null) {
                mapView.getController().setCenter(new GeoPoint(lastLoc.getLatitude(), lastLoc.getLongitude()));
                hasCenteredOnSelf = true;
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not get last known location: " + e.getMessage());
        }

        // Tap-to-place overlay for dropping markers
        MapEventsOverlay eventsOverlay = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (pendingMarkerAffiliation != null) {
                    placeMarker(p, pendingMarkerAffiliation);
                    pendingMarkerAffiliation = null;
                    return true;
                }
                return false;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        });
        mapView.getOverlays().add(0, eventsOverlay);

        // Drop Marker FAB
        FloatingActionButton dropMarkerButton = findViewById(R.id.dropMarkerButton);
        dropMarkerButton.setOnClickListener(v -> showDropMarkerPopup(v));

        FloatingActionButton centerOnMeButton = findViewById(R.id.centerOnMeButton);
        centerOnMeButton.setOnClickListener(v -> centerOnLastKnownLocation());

        FloatingActionButton mapTypeButton = findViewById(R.id.mapTypeButton);
        mapTypeButton.setOnClickListener(v -> cycleMapType());

        findViewById(R.id.zoomInButton).setOnClickListener(v -> mapView.getController().zoomIn());
        findViewById(R.id.zoomOutButton).setOnClickListener(v -> mapView.getController().zoomOut());

        alertButton = findViewById(R.id.alertButton);
        alertButton.setOnClickListener(v -> onAlertButtonClicked());

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        checkPermissions();
        loadDroppedMarkers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
        syncTakService();
        initTak();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (takManager != null) {
            takManager.removeListener(this);
            takManager.removeAlertListener(this);
        }
    }

    private void checkPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= 33) {
            permissions = new String[]{
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.POST_NOTIFICATIONS"
            };
        } else {
            permissions = new String[]{
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION"
            };
        }
        boolean needRequest = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != 0) {
                needRequest = true;
                break;
            }
        }
        if (needRequest) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        } else {
            startLocationUpdates();
            centerOnLastKnownLocation();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == 0) {
                startLocationUpdates();
                centerOnLastKnownLocation();
            } else {
                Toast.makeText(this, R.string.location_permission_required, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void centerOnLastKnownLocation() {
        if (ActivityCompat.checkSelfPermission(this, "android.permission.ACCESS_FINE_LOCATION") != 0) return;
        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            if (location != null && mapView != null) {
                GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());
                mapView.getController().animateTo(point);
            }
        });
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, "android.permission.ACCESS_FINE_LOCATION") != 0) return;
        LocationRequest locationRequest = new LocationRequest.Builder(100, 15000)
                .setMinUpdateIntervalMillis(5000)
                .build();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    float bearing = location.hasBearing() ? location.getBearing() : lastBearing;
                    if (location.hasBearing()) lastBearing = bearing;
                    updateSelfMarker(location.getLatitude(), location.getLongitude(), bearing);
                }
            }
        };
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, (Looper) null);
    }

    private void updateSelfMarker(double latitude, double longitude, float bearing) {
        if (mapView == null) return;

        boolean posChanged = Double.isNaN(lastSelfLat) || Double.isNaN(lastSelfLon)
                || Math.abs(latitude - lastSelfLat) > 0.000001
                || Math.abs(longitude - lastSelfLon) > 0.000001
                || Math.abs(bearing - lastBearing) > 1.0f;

        GeoPoint position = new GeoPoint(latitude, longitude);
        String team = SettingsActivity.getTakTeam(this);
        int teamColor = getTeamColor(team != null ? team : "Cyan");

        if (selfMarker == null) {
            selfMarker = new Marker(mapView);
            selfMarker.setAnchor(0.5f, 0.5f);
            selfMarker.setInfoWindow(null);
            selfMarker.setFlat(true);
            selfMarker.setOnMarkerClickListener((m, mv) -> {
                if (pendingMarkerAffiliation != null) {
                    placeMarker(m.getPosition(), pendingMarkerAffiliation);
                    pendingMarkerAffiliation = null;
                }
                return true;
            });
            mapView.getOverlays().add(selfMarker);
            posChanged = true;
        }
        selfMarker.setPosition(position);
        selfMarker.setIcon(createSelfArrowIcon(teamColor, bearing));
        lastSelfLat = latitude;
        lastSelfLon = longitude;
        if (!hasCenteredOnSelf) {
            mapView.getController().setCenter(position);
            hasCenteredOnSelf = true;
        }
        if (posChanged) {
            mapView.invalidate();
        }
    }

    private Drawable createSelfArrowIcon(int fillColor, float bearing) {
        float density = getResources().getDisplayMetrics().density;
        int size = (int) (30 * density);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.save();
        canvas.rotate(bearing, size / 2f, size / 2f);

        float cx = size / 2f;
        float cy = size / 2f;
        float h = size * 0.4f;
        float w = size * 0.28f;

        Path path = new Path();
        path.moveTo(cx, cy - h);
        path.lineTo(cx + w, cy + h);
        path.lineTo(cx, cy + 0.4f * h);
        path.lineTo(cx - w, cy + h);
        path.close();

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(fillColor);
        fill.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, fill);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * density);
        canvas.drawPath(path, stroke);

        canvas.restore();
        return new BitmapDrawable(getResources(), bmp);
    }

    private void syncTakService() {
        String address = SettingsActivity.getTakServerAddress(this);
        if (address != null && !address.isEmpty() && !TakLocationService.isRunning()) {
            startTakService();
        }
    }

    private void confirmShutdown() {
        View shutdownView = getLayoutInflater().inflate(R.layout.dialog_confirm_shutdown, null);
        AlertDialog shutdownDialog = new AlertDialog.Builder(this)
                .setView(shutdownView)
                .create();
        if (shutdownDialog.getWindow() != null) {
            shutdownDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        shutdownView.findViewById(R.id.btnConfirmShutdown).setOnClickListener(v -> {
            shutdownDialog.dismiss();
            Intent stopIntent = new Intent(this, TakLocationService.class);
            stopIntent.setAction(TakLocationService.ACTION_STOP);
            startService(stopIntent);
            stopService(new Intent(this, TakLocationService.class));
            finishAffinity();
            System.exit(0);
        });
        shutdownView.findViewById(R.id.btnCancelShutdown).setOnClickListener(v -> shutdownDialog.dismiss());
        shutdownDialog.show();
        if (shutdownDialog.getWindow() != null) {
            shutdownDialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.85),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void startTakService() {
        Intent intent = new Intent(this, TakLocationService.class);
        intent.setAction(TakLocationService.ACTION_START);
        startForegroundService(intent);
    }

    private void initTak() {
        String address = SettingsActivity.getTakServerAddress(this);
        if (address == null || address.isEmpty()) {
            updateConnectionStatus(false);
            return;
        }
        takManager = TakManager.getInstance();
        takManager.addListener(this);
        takManager.addAlertListener(this);
        updateConnectionStatus(takManager.isConnected());
        for (TakUser user : takManager.getTakUsers()) {
            onTakUserUpdated(user);
        }
        Log.d(TAG, "TAK map listener registered");
    }

    private void updateConnectionStatus(boolean connected) {
        if (connectionStatusDot != null) {
            connectionStatusDot.setBackgroundResource(connected ? R.drawable.status_dot_green : R.drawable.status_dot_red);
        }
        if (connectionStatusText != null) {
            connectionStatusText.setText(connected ? R.string.connected : R.string.disconnected);
        }
    }

    // ==================== TAK User Callbacks ====================

    @Override
    public void onTakUserUpdated(TakUser user) {
        if (mapView == null) return;
        GeoPoint position = new GeoPoint(user.getLat(), user.getLon());
        String uid = user.getUid();
        boolean isStale = user.isStale();

        if (takUserMarkers.containsKey(uid)) {
            Marker marker = takUserMarkers.get(uid);
            if (marker != null) {
                marker.setPosition(position);
                marker.setIcon(createTakMarkerIcon(user.getCallsign(), user.getTeam(), isStale));
                mapView.invalidate();
            }
        } else {
            Marker marker = new Marker(mapView);
            marker.setPosition(position);
            marker.setAnchor(0.5f, 0.5f);
            marker.setIcon(createTakMarkerIcon(user.getCallsign(), user.getTeam(), isStale));
            marker.setInfoWindow(null);
            marker.setOnMarkerClickListener((m, mv) -> {
                if (pendingMarkerAffiliation != null) {
                    placeMarker(m.getPosition(), pendingMarkerAffiliation);
                    pendingMarkerAffiliation = null;
                }
                return true;
            });
            mapView.getOverlays().add(marker);
            takUserMarkers.put(uid, marker);
            mapView.invalidate();
            Log.d(TAG, "TAK user added: " + user.getCallsign() + " (" + uid + ")");
        }
    }

    @Override
    public void onTakUserRemoved(String uid) {
        Marker marker = takUserMarkers.remove(uid);
        if (marker != null && mapView != null) {
            mapView.getOverlays().remove(marker);
            mapView.invalidate();
            Log.d(TAG, "TAK user removed: " + uid);
        }
    }

    @Override
    public void onTakConnectionChanged(boolean connected) {
        Log.d(TAG, "TAK connection: " + (connected ? "connected" : "disconnected"));
        updateConnectionStatus(connected);
        if (connected && takManager != null) {
            for (TakUser user : takManager.getTakUsers()) {
                onTakUserUpdated(user);
            }
        }
    }

    private Drawable createTakMarkerIcon(String callsign, String team, boolean isStale) {
        int color = isStale ? Color.GRAY : getTeamColor(team);
        float density = getResources().getDisplayMetrics().density;
        int iconSize = (int) (14 * density);
        float r = iconSize / 2f;

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(10 * density);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        float textWidth = textPaint.measureText(callsign);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;
        int gap = (int) (density * 3);
        int textPadH = (int) (density * 3);
        int textPadV = (int) (density * 1.5f);
        int labelW = (int) textWidth + textPadH * 2;
        int labelH = (int) textHeight + textPadV * 2;
        int bmpWidth = Math.max(iconSize, labelW);
        int bmpHeight = iconSize + gap + labelH;

        Bitmap bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float cx = bmpWidth / 2f;
        float cr = r - 1;

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, r, cr, fill);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(density * 1.5f);
        canvas.drawCircle(cx, r, cr, stroke);

        float labelLeft = (bmpWidth - labelW) / 2f;
        float labelTop = iconSize + gap;

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(140, 0, 0, 0));
        canvas.drawRoundRect(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH, density * 3, density * 3, bgPaint);

        float textX = labelLeft + textPadH;
        float textY = labelTop + textPadV - fm.ascent;
        canvas.drawText(callsign, textX, textY, textPaint);

        return new BitmapDrawable(getResources(), bmp);
    }

    private int getTeamColor(String team) {
        if (team == null) return Color.GREEN;
        switch (team.toLowerCase()) {
            case "cyan":       return Color.parseColor("#00BCD4");
            case "red":        return Color.parseColor("#F44336");
            case "blue":       return Color.parseColor("#2196F3");
            case "green":      return Color.parseColor("#4CAF50");
            case "yellow":     return Color.parseColor("#FFEB3B");
            case "white":      return Color.WHITE;
            case "orange":     return Color.parseColor("#FF9800");
            case "magenta":    return Color.parseColor("#E91E63");
            case "maroon":     return Color.parseColor("#880E4F");
            case "purple":     return Color.parseColor("#9C27B0");
            case "dark green": return Color.parseColor("#2E7D32");
            case "teal":       return Color.parseColor("#009688");
            case "dark blue":  return Color.parseColor("#1565C0");
            case "brown":      return Color.parseColor("#795548");
            default:           return Color.GREEN;
        }
    }

    // ==================== Alert Handling ====================

    private void onAlertButtonClicked() {
        if (takManager == null || !takManager.isConnected()) {
            Toast.makeText(this, "Not connected to TAK server", Toast.LENGTH_SHORT).show();
            return;
        }

        View alertView = getLayoutInflater().inflate(R.layout.dialog_alert, null);
        AlertDialog alertDialog = new AlertDialog.Builder(this)
                .setView(alertView)
                .create();
        if (alertDialog.getWindow() != null) {
            alertDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        alertView.findViewById(R.id.btnAlertCancel).setOnClickListener(v -> alertDialog.dismiss());

        if (takManager.hasActiveAlert()) {
            ((TextView) alertView.findViewById(R.id.alertDialogTitle)).setText("Cancel Alert");
            alertView.findViewById(R.id.alertOptionsContainer).setVisibility(View.GONE);
            alertView.findViewById(R.id.alertCancelMessage).setVisibility(View.VISIBLE);
            TextView confirmBtn = alertView.findViewById(R.id.btnAlertConfirm);
            confirmBtn.setVisibility(View.VISIBLE);
            confirmBtn.setOnClickListener(v -> {
                takManager.cancelAlert();
                alertButton.setBackgroundTintList(ColorStateList.valueOf(0xFFCC0000));
                Toast.makeText(this, "Alert cancelled", Toast.LENGTH_SHORT).show();
                alertDialog.dismiss();
            });
        } else {
            final String[] alertTypes = {"911 Alert", "Ring The Bell", "In Contact", "Geo-fence Breached"};
            int[] optionIds = {R.id.alertOption911, R.id.alertOptionBell, R.id.alertOptionContact, R.id.alertOptionGeofence};
            for (int i = 0; i < optionIds.length; i++) {
                final int index = i;
                alertView.findViewById(optionIds[i]).setOnClickListener(v -> {
                    if (ActivityCompat.checkSelfPermission(this, "android.permission.ACCESS_FINE_LOCATION") != 0) {
                        Toast.makeText(this, "No location permission", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
                        if (location == null) {
                            Toast.makeText(this, "No location available", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        takManager.sendAlert(location, alertTypes[index]);
                        alertButton.setBackgroundTintList(ColorStateList.valueOf(0xFFFF6600));
                        Toast.makeText(this, "Alert sent: " + alertTypes[index], Toast.LENGTH_SHORT).show();
                    });
                    alertDialog.dismiss();
                });
            }
        }

        alertDialog.show();
        if (alertDialog.getWindow() != null) {
            alertDialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.85),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    public void onAlertReceived(String senderUid, String senderCallsign, String alertType, double lat, double lon) {
        runOnUiThread(() -> {
            Toast.makeText(this, senderCallsign + ": " + alertType, Toast.LENGTH_LONG).show();
            if (mapView == null) return;
            GeoPoint position = new GeoPoint(lat, lon);
            Marker existing = alertMarkers.get(senderUid);
            if (existing != null) mapView.getOverlays().remove(existing);

            Marker marker = new Marker(mapView);
            marker.setPosition(position);
            marker.setAnchor(0.5f, 0.5f);
            marker.setIcon(createAlertMarkerIcon(senderCallsign, alertType));
            marker.setInfoWindow(null);
            marker.setOnMarkerClickListener((m, mv) -> {
                if (pendingMarkerAffiliation != null) {
                    placeMarker(m.getPosition(), pendingMarkerAffiliation);
                    pendingMarkerAffiliation = null;
                }
                return true;
            });
            mapView.getOverlays().add(marker);
            alertMarkers.put(senderUid, marker);
            mapView.invalidate();
            Log.d(TAG, "Alert marker added: " + senderCallsign + " - " + alertType);
        });
    }

    @Override
    public void onAlertCancelled(String senderUid) {
        runOnUiThread(() -> {
            Marker marker = alertMarkers.remove(senderUid);
            if (marker != null && mapView != null) {
                mapView.getOverlays().remove(marker);
                mapView.invalidate();
                Log.d(TAG, "Alert marker removed: " + senderUid);
            }
        });
    }

    private Drawable createAlertMarkerIcon(String callsign, String alertType) {
        float density = getResources().getDisplayMetrics().density;
        int iconSize = (int) (24 * density);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(10 * density);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        String label = callsign + " - " + alertType;
        float textWidth = textPaint.measureText(label);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;
        int gap = (int) (density * 3);
        int textPadH = (int) (4 * density);
        int textPadV = (int) (density * 2);
        int labelW = (int) textWidth + textPadH * 2;
        int labelH = (int) textHeight + textPadV * 2;
        int bmpWidth = Math.max(iconSize, labelW);
        int bmpHeight = iconSize + gap + labelH;

        Bitmap bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float cx = bmpWidth / 2f;
        float cy = iconSize / 2f;
        float r = iconSize / 2f - 2;

        Path diamond = new Path();
        diamond.moveTo(cx, cy - r);
        diamond.lineTo(cx + r, cy);
        diamond.lineTo(cx, cy + r);
        diamond.lineTo(cx - r, cy);
        diamond.close();

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.RED);
        fill.setStyle(Paint.Style.FILL);
        canvas.drawPath(diamond, fill);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.WHITE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(density * 2);
        canvas.drawPath(diamond, stroke);

        Paint bangPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bangPaint.setColor(Color.WHITE);
        bangPaint.setTextSize(14 * density);
        bangPaint.setTypeface(Typeface.DEFAULT_BOLD);
        bangPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics bfm = bangPaint.getFontMetrics();
        canvas.drawText("!", cx, cy - (bfm.ascent + bfm.descent) / 2, bangPaint);

        float labelLeft = (bmpWidth - labelW) / 2f;
        float labelTop = iconSize + gap;
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(200, 200, 0, 0));
        canvas.drawRoundRect(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH, density * 3, density * 3, bgPaint);

        float textX = labelLeft + textPadH;
        float textY = labelTop + textPadV - fm.ascent;
        canvas.drawText(label, textX, textY, textPaint);

        return new BitmapDrawable(getResources(), bmp);
    }

    // ==================== Drop Marker (Hostile/Friendly/Unknown/Neutral) ====================

    private void showDropMarkerPopup(View anchor) {
        View popupView = getLayoutInflater().inflate(R.layout.popup_drop_marker, null);
        PopupWindow popup = new PopupWindow(popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(12f);
        popup.setOutsideTouchable(true);

        View.OnClickListener markerClickListener = v -> {
            String affiliation;
            int id = v.getId();
            if (id == R.id.markerFriendly) affiliation = "Friendly";
            else if (id == R.id.markerHostile) affiliation = "Hostile";
            else if (id == R.id.markerUnknown) affiliation = "Unknown";
            else if (id == R.id.markerNeutral) affiliation = "Neutral";
            else return;

            pendingMarkerAffiliation = affiliation;
            popup.dismiss();
        };

        popupView.findViewById(R.id.markerFriendly).setOnClickListener(markerClickListener);
        popupView.findViewById(R.id.markerHostile).setOnClickListener(markerClickListener);
        popupView.findViewById(R.id.markerUnknown).setOnClickListener(markerClickListener);
        popupView.findViewById(R.id.markerNeutral).setOnClickListener(markerClickListener);

        // Show popup to the left of the FAB, vertically centered
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupWidth = popupView.getMeasuredWidth();
        int popupHeight = popupView.getMeasuredHeight();
        int xOff = -(popupWidth + (int)(8 * getResources().getDisplayMetrics().density));
        int yOff = -(popupHeight / 2) - (anchor.getHeight() / 2);
        popup.showAsDropDown(anchor, xOff, yOff);
    }

    private void placeMarker(GeoPoint point, String affiliation) {
        String markerKey = affiliation + "-" + System.currentTimeMillis();
        Marker marker = new Marker(mapView);
        marker.setPosition(point);
        marker.setAnchor(0.5f, 0.5f);
        marker.setIcon(createDroppedMarkerIcon(affiliation));
        marker.setInfoWindow(null);

        DroppedMarker dm = new DroppedMarker(markerKey, marker, point, affiliation);
        droppedMarkers.put(markerKey, dm);

        // Tap on marker opens radial menu
        marker.setOnMarkerClickListener((m, mv) -> {
            showRadialMenu(dm);
            return true;
        });

        mapView.getOverlays().add(marker);
        mapView.invalidate();

        Log.d(TAG, "Placed " + affiliation + " marker at " + point.getLatitude() + "," + point.getLongitude());
        saveDroppedMarkers();
    }

    private void showRadialMenu(DroppedMarker dm) {
        dismissRadialMenu();

        // Convert marker geo position to pixel position within the content view
        android.graphics.Point screenPt = new android.graphics.Point();
        mapView.getProjection().toPixels(dm.position, screenPt);

        // toPixels gives coordinates relative to the MapView.
        // We need coords relative to the content root (where addContentView places the overlay).
        View contentRoot = findViewById(android.R.id.content);
        int[] contentLocation = new int[2];
        contentRoot.getLocationOnScreen(contentLocation);
        int[] mapLocation = new int[2];
        mapView.getLocationOnScreen(mapLocation);
        float cx = screenPt.x + (mapLocation[0] - contentLocation[0]);
        float cy = screenPt.y + (mapLocation[1] - contentLocation[1]);

        activeRadialMenu = new RadialMenuView(this, cx, cy, new RadialMenuView.RadialMenuListener() {
            @Override
            public void onDelete() {
                dismissRadialMenu();
                View deleteView = getLayoutInflater().inflate(R.layout.dialog_confirm_delete, null);
                AlertDialog deleteDialog = new AlertDialog.Builder(MainActivity.this)
                        .setView(deleteView)
                        .create();
                if (deleteDialog.getWindow() != null) {
                    deleteDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                }
                deleteView.findViewById(R.id.btnConfirmDelete).setOnClickListener(v -> {
                    mapView.getOverlays().remove(dm.marker);
                    droppedMarkers.remove(dm.key);
                    mapView.invalidate();
                    saveDroppedMarkers();
                    deleteDialog.dismiss();
                });
                deleteView.findViewById(R.id.btnCancelDelete).setOnClickListener(v -> deleteDialog.dismiss());
                deleteDialog.show();
                if (deleteDialog.getWindow() != null) {
                    deleteDialog.getWindow().setLayout(
                            (int) (getResources().getDisplayMetrics().widthPixels * 0.85),
                            android.view.WindowManager.LayoutParams.WRAP_CONTENT);
                }
            }

            @Override
            public void onSend() {
                dismissRadialMenu();
                if (takManager == null || !takManager.isConnected()) {
                    Toast.makeText(MainActivity.this, "Not connected to TAK server", Toast.LENGTH_SHORT).show();
                    return;
                }
                takManager.sendMarker(dm.position.getLatitude(), dm.position.getLongitude(),
                        dm.position.getAltitude(), dm.affiliation, dm.name, dm.remarks);
                dm.transmitted = true;
            }

            @Override
            public void onEdit() {
                dismissRadialMenu();
                showEditMarkerDialog(dm);
            }

            @Override
            public void onDismiss() {
                dismissRadialMenu();
            }
        });

        // Add radial menu as a full-screen overlay on top of everything
        getWindow().addContentView(activeRadialMenu,
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void dismissRadialMenu() {
        if (activeRadialMenu != null) {
            ((android.view.ViewGroup) activeRadialMenu.getParent()).removeView(activeRadialMenu);
            activeRadialMenu = null;
        }
    }

    private void showEditMarkerDialog(DroppedMarker dm) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_marker, null);
        EditText nameField = dialogView.findViewById(R.id.editMarkerName);
        EditText remarksField = dialogView.findViewById(R.id.editMarkerRemarks);
        TextView coordsText = dialogView.findViewById(R.id.editMarkerCoords);
        TextView titleText = dialogView.findViewById(R.id.editMarkerTitle);

        titleText.setText("Edit " + dm.affiliation + " Marker");
        nameField.setText(dm.name);
        remarksField.setText(dm.remarks);
        coordsText.setText(String.format("%.6f, %.6f", dm.position.getLatitude(), dm.position.getLongitude()));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        dialogView.findViewById(R.id.btnSave).setOnClickListener(v -> {
            dm.name = nameField.getText().toString().trim();
            dm.remarks = remarksField.getText().toString().trim();

            String label = dm.name.isEmpty() ? dm.affiliation : dm.name;
            dm.marker.setIcon(createDroppedMarkerIcon(dm.affiliation, label));
            mapView.invalidate();
            saveDroppedMarkers();

            dialog.dismiss();
        });

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());

        dialogView.findViewById(R.id.btnSaveAndSend).setOnClickListener(v -> {
            dm.name = nameField.getText().toString().trim();
            dm.remarks = remarksField.getText().toString().trim();

            String label = dm.name.isEmpty() ? dm.affiliation : dm.name;
            dm.marker.setIcon(createDroppedMarkerIcon(dm.affiliation, label));
            mapView.invalidate();
            saveDroppedMarkers();

            if (takManager != null && takManager.isConnected()) {
                takManager.sendMarker(dm.position.getLatitude(), dm.position.getLongitude(),
                        dm.position.getAltitude(), dm.affiliation, dm.name, dm.remarks);
                dm.transmitted = true;
                saveDroppedMarkers();
            } else {
                Toast.makeText(this, "Not connected to TAK server", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.85),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private Drawable createDroppedMarkerIcon(String affiliation) {
        return createDroppedMarkerIcon(affiliation, affiliation);
    }

    private Drawable createDroppedMarkerIcon(String affiliation, String label) {
        float density = getResources().getDisplayMetrics().density;
        int targetSize = (int) (32 * density);

        // Get the correct ATAK MIL-STD-2525 marker icon
        int resId;
        switch (affiliation.toLowerCase()) {
            case "hostile":  resId = R.drawable.marker_hostile; break;
            case "unknown":  resId = R.drawable.marker_unknown; break;
            case "neutral":  resId = R.drawable.marker_neutral; break;
            case "friendly":
            default:         resId = R.drawable.marker_friendly; break;
        }

        Bitmap srcBmp = BitmapFactory.decodeResource(getResources(), resId);
        Bitmap scaledIcon = Bitmap.createScaledBitmap(srcBmp, targetSize, targetSize, true);

        // Label below
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(10 * density);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        float textWidth = textPaint.measureText(label);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;
        int gap = (int) (density * 3);
        int textPadH = (int) (4 * density);
        int textPadV = (int) (density * 2);
        int labelW = (int) textWidth + textPadH * 2;
        int labelH = (int) textHeight + textPadV * 2;
        int bmpWidth = Math.max(targetSize, labelW);
        int bmpHeight = targetSize + gap + labelH;

        Bitmap bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Draw the scaled ATAK icon centered
        float iconLeft = (bmpWidth - targetSize) / 2f;
        canvas.drawBitmap(scaledIcon, iconLeft, 0, null);

        // Draw label background
        float labelLeft = (bmpWidth - labelW) / 2f;
        float labelTop = targetSize + gap;
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(160, 0, 0, 0));
        canvas.drawRoundRect(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH, density * 3, density * 3, bgPaint);

        // Draw label text
        float lx = labelLeft + textPadH;
        float ly = labelTop + textPadV - fm.ascent;
        canvas.drawText(label, lx, ly, textPaint);

        if (srcBmp != scaledIcon) srcBmp.recycle();

        return new BitmapDrawable(getResources(), bmp);
    }

    // ==================== Map Type ====================

    private void cycleMapType() {
        currentMapType = (currentMapType + 1) % 3;
        String mapTypeName;
        switch (currentMapType) {
            case 1:
                mapView.setTileSource(TileSourceFactory.USGS_SAT);
                mapTypeName = "Satellite";
                break;
            case 2:
                mapView.setTileSource(TileSourceFactory.USGS_TOPO);
                mapTypeName = "Topographical";
                break;
            default:
                mapView.setTileSource(TileSourceFactory.MAPNIK);
                mapTypeName = "Street Map";
                break;
        }
        Toast.makeText(this, "Map: " + mapTypeName, Toast.LENGTH_SHORT).show();
    }

    private void saveDroppedMarkers() {
        try {
            JSONArray arr = new JSONArray();
            for (DroppedMarker dm : droppedMarkers.values()) {
                JSONObject obj = new JSONObject();
                obj.put("key", dm.key);
                obj.put("lat", dm.position.getLatitude());
                obj.put("lon", dm.position.getLongitude());
                obj.put("alt", dm.position.getAltitude());
                obj.put("affiliation", dm.affiliation);
                obj.put("name", dm.name);
                obj.put("remarks", dm.remarks);
                obj.put("transmitted", dm.transmitted);
                arr.put(obj);
            }
            getSharedPreferences("taklite_markers", MODE_PRIVATE)
                    .edit().putString("dropped_markers", arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving markers", e);
        }
    }

    private void loadDroppedMarkers() {
        try {
            String json = getSharedPreferences("taklite_markers", MODE_PRIVATE)
                    .getString("dropped_markers", null);
            if (json == null) return;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String key = obj.getString("key");
                double lat = obj.getDouble("lat");
                double lon = obj.getDouble("lon");
                double alt = obj.optDouble("alt", 0);
                String affiliation = obj.getString("affiliation");
                String name = obj.optString("name", affiliation + " Marker");
                String remarks = obj.optString("remarks", "");
                boolean transmitted = obj.optBoolean("transmitted", false);

                GeoPoint point = new GeoPoint(lat, lon, alt);
                Marker marker = new Marker(mapView);
                marker.setPosition(point);
                marker.setAnchor(0.5f, 0.5f);
                String label = name.isEmpty() ? affiliation : name;
                marker.setIcon(createDroppedMarkerIcon(affiliation, label));
                marker.setInfoWindow(null);

                DroppedMarker dm = new DroppedMarker(key, marker, point, affiliation);
                dm.name = name;
                dm.remarks = remarks;
                dm.transmitted = transmitted;
                droppedMarkers.put(key, dm);

                marker.setOnMarkerClickListener((m, mv) -> {
                    showRadialMenu(dm);
                    return true;
                });

                mapView.getOverlays().add(marker);
            }
            mapView.invalidate();
            Log.d(TAG, "Loaded " + arr.length() + " saved markers");
        } catch (Exception e) {
            Log.e(TAG, "Error loading markers", e);
        }
    }
}
