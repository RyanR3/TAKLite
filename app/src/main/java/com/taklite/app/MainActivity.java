package com.taklite.app;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import com.taklite.app.tak.TakLocationService;
import com.taklite.app.tak.TakManager;
import com.taklite.app.tak.TakUser;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements TakManager.TakUserListener, TakManager.TakAlertListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 101;

    private MapView mapView;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Marker selfMarker;
    private float lastBearing = 0;
    private boolean hasCenteredOnSelf = false;

    // TAK
    private Map<String, Marker> takUserMarkers = new HashMap<>();
    private TakManager takManager;
    private View connectionStatusDot;
    private TextView connectionStatusText;

    private int currentMapType = 0; // 0 = Street, 1 = Satellite, 2 = Topo

    // Alerts
    private FloatingActionButton alertButton;
    private Map<String, Marker> alertMarkers = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize osmdroid configuration
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_main);

        // Toolbar
        connectionStatusDot = findViewById(R.id.connectionStatusDot);
        connectionStatusText = findViewById(R.id.connectionStatusText);
        findViewById(R.id.settingsButton).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.shutdownButton).setOnClickListener(v -> confirmShutdown());

        // Map setup
        mapView = findViewById(R.id.mapView);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.0);

        // FABs
        FloatingActionButton centerOnMeButton = findViewById(R.id.centerOnMeButton);
        centerOnMeButton.setOnClickListener(v -> centerOnLastKnownLocation());

        FloatingActionButton mapTypeButton = findViewById(R.id.mapTypeButton);
        mapTypeButton.setOnClickListener(v -> cycleMapType());

        alertButton = findViewById(R.id.alertButton);
        alertButton.setOnClickListener(v -> onAlertButtonClicked());

        // Location
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Request permissions
        checkPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }

        // Start or sync TAK service
        syncTakService();

        // Register as TAK listener
        initTak();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) {
            mapView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Stop location updates
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        // Remove TAK listeners (service keeps the connection alive)
        if (takManager != null) {
            takManager.removeListener(this);
            takManager.removeAlertListener(this);
        }
    }

    // --- Permissions ---

    private void checkPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }

        boolean needRequest = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
                centerOnLastKnownLocation();
            } else {
                Toast.makeText(this, R.string.location_permission_required, Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- Location ---

    private void centerOnLastKnownLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            if (location != null && mapView != null) {
                GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());
                mapView.getController().animateTo(point);
            }
        });
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    float bearing = location.hasBearing() ? location.getBearing() : lastBearing;
                    if (location.hasBearing()) lastBearing = bearing;
                    updateSelfMarker(location.getLatitude(), location.getLongitude(), bearing);
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
    }

    private void updateSelfMarker(double latitude, double longitude, float bearing) {
        if (mapView == null) return;

        GeoPoint position = new GeoPoint(latitude, longitude);

        // Get team color from settings
        String team = SettingsActivity.getTakTeam(this);
        int teamColor = getTeamColor(team != null ? team : "Cyan");

        if (selfMarker == null) {
            selfMarker = new Marker(mapView);
            selfMarker.setAnchor(0.5f, 0.5f);
            selfMarker.setInfoWindow(null);
            selfMarker.setFlat(true);
            mapView.getOverlays().add(selfMarker);
        }

        selfMarker.setPosition(position);
        selfMarker.setIcon(createSelfArrowIcon(teamColor, bearing));

        // Center map on first update
        if (!hasCenteredOnSelf) {
            mapView.getController().animateTo(position);
            hasCenteredOnSelf = true;
        }

        mapView.invalidate();
    }

    private Drawable createSelfArrowIcon(int fillColor, float bearing) {
        float density = getResources().getDisplayMetrics().density;
        int size = (int) (30 * density);

        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Rotate canvas to match bearing
        canvas.save();
        canvas.rotate(bearing, size / 2f, size / 2f);

        float cx = size / 2f;
        float cy = size / 2f;
        float h = size * 0.4f;
        float w = size * 0.28f;

        // Arrow pointing up (north), then rotated by bearing
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(cx, cy - h);          // top point
        path.lineTo(cx + w, cy + h);      // bottom right
        path.lineTo(cx, cy + h * 0.4f);   // notch center
        path.lineTo(cx - w, cy + h);      // bottom left
        path.close();

        // Fill
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(fillColor);
        fill.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, fill);

        // Stroke
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * density);
        canvas.drawPath(path, stroke);

        canvas.restore();

        return new BitmapDrawable(getResources(), bmp);
    }

    // --- TAK ---

    private void syncTakService() {
        String address = SettingsActivity.getTakServerAddress(this);
        if (address != null && !address.isEmpty()) {
            if (!TakLocationService.isRunning()) {
                startTakService();
            }
        }
    }

    private void confirmShutdown() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Shutdown TAK Lite")
                .setMessage("Stop tracking and close the app?")
                .setPositiveButton("Shutdown", (dialog, which) -> {
                    Intent stopIntent = new Intent(this, TakLocationService.class);
                    stopIntent.setAction(TakLocationService.ACTION_STOP);
                    startService(stopIntent);
                    stopService(new Intent(this, TakLocationService.class));
                    finishAffinity();
                    System.exit(0);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startTakService() {
        Intent intent = new Intent(this, TakLocationService.class);
        intent.setAction(TakLocationService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
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

        // Update connection status
        updateConnectionStatus(takManager.isConnected());

        // Show existing TAK users on the map
        for (TakUser user : takManager.getTakUsers()) {
            onTakUserUpdated(user);
        }

        Log.d(TAG, "TAK map listener registered");
    }

    private void updateConnectionStatus(boolean connected) {
        if (connectionStatusDot != null) {
            connectionStatusDot.setBackgroundResource(
                    connected ? R.drawable.status_dot_green : R.drawable.status_dot_red);
        }
        if (connectionStatusText != null) {
            connectionStatusText.setText(connected ? R.string.connected : R.string.disconnected);
        }
    }

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

        // When TAK connects, populate any existing users
        if (connected && takManager != null) {
            for (TakUser user : takManager.getTakUsers()) {
                onTakUserUpdated(user);
            }
        }
    }

    // --- Map Markers ---

    private Drawable createTakMarkerIcon(String callsign, String team, boolean isStale) {
        int color = isStale ? Color.GRAY : getTeamColor(team);
        float density = getResources().getDisplayMetrics().density;

        int iconSize = (int) (14 * density);
        float r = iconSize / 2f;

        // Measure callsign text
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(10 * density);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        float textWidth = textPaint.measureText(callsign);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;

        int gap = (int) (3 * density);
        int textPadH = (int) (3 * density);
        int textPadV = (int) (1.5f * density);
        int labelW = (int) textWidth + textPadH * 2;
        int labelH = (int) textHeight + textPadV * 2;

        int bmpWidth = Math.max(iconSize, labelW);
        int bmpHeight = iconSize + gap + labelH;

        Bitmap bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Draw team-colored circle
        float cx = bmpWidth / 2f;
        float cy = r;
        float cr = r - 1;

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, cr, fill);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * density);
        canvas.drawCircle(cx, cy, cr, stroke);

        // Draw semi-transparent black box behind callsign
        float labelLeft = (bmpWidth - labelW) / 2f;
        float labelTop = iconSize + gap;
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(140, 0, 0, 0));
        canvas.drawRoundRect(labelLeft, labelTop,
                labelLeft + labelW, labelTop + labelH,
                3 * density, 3 * density, bgPaint);

        // Draw callsign text centered in the label box
        float textX = labelLeft + textPadH;
        float textY = labelTop + textPadV - fm.ascent;
        canvas.drawText(callsign, textX, textY, textPaint);

        return new BitmapDrawable(getResources(), bmp);
    }

    private int getTeamColor(String team) {
        if (team == null) return Color.GREEN;
        switch (team.toLowerCase()) {
            case "cyan":        return Color.CYAN;
            case "red":         return Color.RED;
            case "blue":        return Color.BLUE;
            case "green":       return Color.GREEN;
            case "yellow":      return Color.YELLOW;
            case "white":       return Color.WHITE;
            case "orange":      return Color.rgb(255, 165, 0);
            case "magenta":     return Color.MAGENTA;
            case "maroon":      return Color.rgb(128, 0, 0);
            case "purple":      return Color.rgb(128, 0, 128);
            case "dark green":  return Color.rgb(0, 100, 0);
            case "teal":        return Color.rgb(0, 128, 128);
            case "dark blue":   return Color.rgb(0, 0, 139);
            case "brown":       return Color.rgb(139, 69, 19);
            default:            return Color.GREEN;
        }
    }

    // --- Alerts ---

    private void onAlertButtonClicked() {
        if (takManager == null || !takManager.isConnected()) {
            Toast.makeText(this, "Not connected to TAK server", Toast.LENGTH_SHORT).show();
            return;
        }

        if (takManager.hasActiveAlert()) {
            // Already alerting — offer to cancel
            new AlertDialog.Builder(this)
                    .setTitle("Cancel Alert")
                    .setMessage("Cancel your active emergency alert?")
                    .setPositiveButton("Cancel Alert", (dialog, which) -> {
                        takManager.cancelAlert();
                        alertButton.setBackgroundTintList(ColorStateList.valueOf(0xFFCC0000));
                        Toast.makeText(this, "Alert cancelled", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Keep Active", null)
                    .show();
        } else {
            // Show alert type picker
            String[] alertTypes = {"911 Alert", "Ring The Bell", "In Contact", "Geo-fence Breached"};
            new AlertDialog.Builder(this)
                    .setTitle("Send Emergency Alert")
                    .setItems(alertTypes, (dialog, which) -> {
                        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                                != PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(this, "No location permission", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
                            if (location != null) {
                                takManager.sendAlert(location, alertTypes[which]);
                                alertButton.setBackgroundTintList(ColorStateList.valueOf(0xFFFF6600));
                                Toast.makeText(this, "Alert sent: " + alertTypes[which], Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "No location available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    @Override
    public void onAlertReceived(String senderUid, String senderCallsign, String alertType, double lat, double lon) {
        runOnUiThread(() -> {
            Toast.makeText(this, senderCallsign + ": " + alertType, Toast.LENGTH_LONG).show();

            if (mapView == null) return;

            GeoPoint position = new GeoPoint(lat, lon);

            // Remove existing alert marker for this sender if any
            Marker existing = alertMarkers.get(senderUid);
            if (existing != null) {
                mapView.getOverlays().remove(existing);
            }

            Marker marker = new Marker(mapView);
            marker.setPosition(position);
            marker.setAnchor(0.5f, 0.5f);
            marker.setIcon(createAlertMarkerIcon(senderCallsign, alertType));
            marker.setInfoWindow(null);

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

        // Measure label text
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(10 * density);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        String label = callsign + " - " + alertType;
        float textWidth = textPaint.measureText(label);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;

        int gap = (int) (3 * density);
        int textPadH = (int) (4 * density);
        int textPadV = (int) (2 * density);
        int labelW = (int) textWidth + textPadH * 2;
        int labelH = (int) textHeight + textPadV * 2;

        int bmpWidth = Math.max(iconSize, labelW);
        int bmpHeight = iconSize + gap + labelH;

        Bitmap bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Draw red diamond shape
        float cx = bmpWidth / 2f;
        float cy = iconSize / 2f;
        float r = iconSize / 2f - 2;

        android.graphics.Path diamond = new android.graphics.Path();
        diamond.moveTo(cx, cy - r);       // top
        diamond.lineTo(cx + r, cy);       // right
        diamond.lineTo(cx, cy + r);       // bottom
        diamond.lineTo(cx - r, cy);       // left
        diamond.close();

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.RED);
        fill.setStyle(Paint.Style.FILL);
        canvas.drawPath(diamond, fill);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.WHITE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * density);
        canvas.drawPath(diamond, stroke);

        // Draw "!" in center
        Paint bangPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bangPaint.setColor(Color.WHITE);
        bangPaint.setTextSize(14 * density);
        bangPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bangPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics bfm = bangPaint.getFontMetrics();
        canvas.drawText("!", cx, cy - (bfm.ascent + bfm.descent) / 2, bangPaint);

        // Draw label below
        float labelLeft = (bmpWidth - labelW) / 2f;
        float labelTop = iconSize + gap;
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(200, 200, 0, 0));
        canvas.drawRoundRect(labelLeft, labelTop,
                labelLeft + labelW, labelTop + labelH,
                3 * density, 3 * density, bgPaint);

        float textX = labelLeft + textPadH;
        float textY = labelTop + textPadV - fm.ascent;
        canvas.drawText(label, textX, textY, textPaint);

        return new BitmapDrawable(getResources(), bmp);
    }

    // --- Map Type ---

    private void cycleMapType() {
        currentMapType = (currentMapType + 1) % 3;
        ITileSource tileSource;
        String mapTypeName;

        switch (currentMapType) {
            case 0:
                tileSource = TileSourceFactory.MAPNIK;
                mapTypeName = "Street Map";
                break;
            case 1:
                tileSource = TileSourceFactory.USGS_SAT;
                mapTypeName = "Satellite";
                break;
            case 2:
                tileSource = TileSourceFactory.USGS_TOPO;
                mapTypeName = "Topographical";
                break;
            default:
                tileSource = TileSourceFactory.MAPNIK;
                mapTypeName = "Street Map";
        }

        mapView.setTileSource(tileSource);
        Toast.makeText(this, "Map: " + mapTypeName, Toast.LENGTH_SHORT).show();
    }
}
