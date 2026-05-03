package com.taklite.app;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import com.taklite.app.tak.CotBuilder;
import com.taklite.app.tak.TakCertEnroller;
import com.taklite.app.tak.TakClient;
import com.taklite.app.tak.TakManager;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.HashMap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.json.JSONArray;
import org.json.JSONObject;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";
    private static final String PREFS_NAME = "TAKLitePrefs";

    // TAK Settings keys
    private static final String KEY_TAK_SERVER_ADDRESS = "tak_server_address";
    private static final String KEY_TAK_SERVER_PORT = "tak_server_port";
    private static final String KEY_TAK_CALLSIGN = "tak_callsign";
    private static final String KEY_TAK_TEAM = "tak_team";
    private static final String KEY_TAK_ROLE = "tak_role";
    private static final String KEY_TAK_TRUSTSTORE_PATH = "tak_truststore_path";
    private static final String KEY_TAK_CLIENT_CERT_PATH = "tak_client_cert_path";
    private static final String KEY_TAK_CERT_PASSWORD = "tak_cert_password";
    private static final String KEY_TAK_UID = "tak_uid";
    private static final String KEY_TAK_ENROLL_USERNAME = "tak_enroll_username";
    private static final String KEY_TAK_ENROLL_PASSWORD = "tak_enroll_password";

    private static final String[] TAK_TEAMS = {
            "Cyan", "Red", "Blue", "Green", "Yellow", "White",
            "Orange", "Magenta", "Maroon", "Purple", "Dark Green",
            "Teal", "Dark Blue", "Brown"
    };

    private static final String[] TAK_ROLES = {
            "Team Member", "Team Lead", "Squad Leader", "Medic",
            "Forward Observer", "RTO"
    };

    private static final HashMap<String, Integer> TEAM_COLORS = new HashMap<>();
    static {
        TEAM_COLORS.put("Cyan", Color.parseColor("#00BCD4"));
        TEAM_COLORS.put("Red", Color.parseColor("#F44336"));
        TEAM_COLORS.put("Blue", Color.parseColor("#2196F3"));
        TEAM_COLORS.put("Green", Color.parseColor("#4CAF50"));
        TEAM_COLORS.put("Yellow", Color.parseColor("#FFEB3B"));
        TEAM_COLORS.put("White", Color.parseColor("#FFFFFF"));
        TEAM_COLORS.put("Orange", Color.parseColor("#FF9800"));
        TEAM_COLORS.put("Magenta", Color.parseColor("#E91E63"));
        TEAM_COLORS.put("Maroon", Color.parseColor("#880E4F"));
        TEAM_COLORS.put("Purple", Color.parseColor("#9C27B0"));
        TEAM_COLORS.put("Dark Green", Color.parseColor("#2E7D32"));
        TEAM_COLORS.put("Teal", Color.parseColor("#009688"));
        TEAM_COLORS.put("Dark Blue", Color.parseColor("#1565C0"));
        TEAM_COLORS.put("Brown", Color.parseColor("#795548"));
    }

    // UI fields
    private EditText takServerAddress;
    private EditText takServerPort;
    private EditText takCallsign;
    private EditText takCertPassword;
    private Spinner takTeamSpinner;
    private Spinner takRoleSpinner;
    private Button takTrustStoreButton;
    private Button takClientCertButton;
    private TextView takTrustStoreLabel;
    private TextView takClientCertLabel;
    private Button takTestConnectionButton;

    private boolean pickingTrustStore = true;
    private TextView enrollStatusView;

    // QR code scanner for TAK enrollment
    private final ActivityResultLauncher<ScanOptions> takEnrollQrLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    String scanned = result.getContents();
                    Log.d(TAG, "TAK enrollment QR scanned: " + scanned);
                    parseTakEnrollQrCode(scanned);
                }
            });

    private final ActivityResultLauncher<String> takFilePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    if (pickingTrustStore) {
                        copyFileToInternal(uri, "tak_truststore.p12",
                                KEY_TAK_TRUSTSTORE_PATH, takTrustStoreLabel);
                    } else {
                        copyFileToInternal(uri, "tak_clientcert.p12",
                                KEY_TAK_CLIENT_CERT_PATH, takClientCertLabel);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Back button
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        setupTakSettings();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveTakSettings();
    }

    // Group assignment state
    private final List<String> availableGroups = new ArrayList<>();
    private LinearLayout groupCheckboxContainer;
    private Button assignGroupsButton;
    private TextView groupStatus;

    private void setupTakSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        takServerAddress = findViewById(R.id.takServerAddress);
        takServerPort = findViewById(R.id.takServerPort);
        takCallsign = findViewById(R.id.takCallsign);
        takCertPassword = findViewById(R.id.takCertPassword);
        takTeamSpinner = findViewById(R.id.takTeamSpinner);
        takTrustStoreButton = findViewById(R.id.takTrustStoreButton);
        takClientCertButton = findViewById(R.id.takClientCertButton);
        takTrustStoreLabel = findViewById(R.id.takTrustStoreLabel);
        takClientCertLabel = findViewById(R.id.takClientCertLabel);
        takTestConnectionButton = findViewById(R.id.takTestConnectionButton);
        enrollStatusView = findViewById(R.id.takEnrollStatus);

        // Advanced settings toggle
        LinearLayout advancedHeader = findViewById(R.id.advancedHeader);
        LinearLayout advancedContent = findViewById(R.id.advancedContent);
        ImageView advancedArrow = findViewById(R.id.advancedArrow);
        advancedHeader.setOnClickListener(v -> {
            if (advancedContent.getVisibility() == View.GONE) {
                advancedContent.setVisibility(View.VISIBLE);
                advancedArrow.setRotation(180);
            } else {
                advancedContent.setVisibility(View.GONE);
                advancedArrow.setRotation(0);
            }
        });

        // Group assignment
        groupCheckboxContainer = findViewById(R.id.takGroupCheckboxContainer);
        assignGroupsButton = findViewById(R.id.takAssignGroupsButton);
        groupStatus = findViewById(R.id.takGroupStatus);
        setupGroupAssignment(prefs);

        // Load saved values
        takServerAddress.setText(prefs.getString(KEY_TAK_SERVER_ADDRESS, ""));
        takServerPort.setText(String.valueOf(prefs.getInt(KEY_TAK_SERVER_PORT, 8089)));
        takCallsign.setText(prefs.getString(KEY_TAK_CALLSIGN, ""));
        takCertPassword.setText(prefs.getString(KEY_TAK_CERT_PASSWORD, "atakatak"));

        // Team spinner with colored items
        BaseAdapter teamAdapter = new BaseAdapter() {
            @Override public int getCount() { return TAK_TEAMS.length; }
            @Override public Object getItem(int pos) { return TAK_TEAMS[pos]; }
            @Override public long getItemId(int pos) { return pos; }
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                return createTeamView(pos, convertView, parent, R.layout.spinner_item_team);
            }
            @Override
            public View getDropDownView(int pos, View convertView, ViewGroup parent) {
                return createTeamView(pos, convertView, parent, R.layout.spinner_dropdown_team);
            }
            private View createTeamView(int pos, View convertView, ViewGroup parent, int layoutRes) {
                View view = convertView;
                if (view == null) {
                    view = LayoutInflater.from(SettingsActivity.this).inflate(layoutRes, parent, false);
                }
                String team = TAK_TEAMS[pos];
                int color = TEAM_COLORS.containsKey(team) ? TEAM_COLORS.get(team) : Color.WHITE;
                View dot = view.findViewById(R.id.colorDot);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(color);
                dot.setBackground(circle);
                TextView name = view.findViewById(R.id.teamName);
                name.setText(team);
                name.setTextColor(color);
                return view;
            }
        };
        takTeamSpinner.setAdapter(teamAdapter);

        String savedTeam = prefs.getString(KEY_TAK_TEAM, "Cyan");
        for (int i = 0; i < TAK_TEAMS.length; i++) {
            if (TAK_TEAMS[i].equals(savedTeam)) {
                takTeamSpinner.setSelection(i);
                break;
            }
        }

        takTeamSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString(KEY_TAK_TEAM, TAK_TEAMS[position]).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Role spinner
        takRoleSpinner = findViewById(R.id.takRoleSpinner);
        ArrayAdapter<String> roleAdapter = new ArrayAdapter<>(this,
                R.layout.spinner_item_role, R.id.roleName, TAK_ROLES);
        roleAdapter.setDropDownViewResource(R.layout.spinner_dropdown_role);
        takRoleSpinner.setAdapter(roleAdapter);

        String savedRole = prefs.getString(KEY_TAK_ROLE, "Team Member");
        for (int i = 0; i < TAK_ROLES.length; i++) {
            if (TAK_ROLES[i].equals(savedRole)) {
                takRoleSpinner.setSelection(i);
                break;
            }
        }

        takRoleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString(KEY_TAK_ROLE, TAK_ROLES[position]).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Trust store file labels
        String trustPath = prefs.getString(KEY_TAK_TRUSTSTORE_PATH, "");
        if (!trustPath.isEmpty()) {
            takTrustStoreLabel.setText(new File(trustPath).getName());
        }
        String certPath = prefs.getString(KEY_TAK_CLIENT_CERT_PATH, "");
        if (!certPath.isEmpty()) {
            takClientCertLabel.setText(new File(certPath).getName());
        }

        // File picker buttons
        takTrustStoreButton.setOnClickListener(v -> {
            pickingTrustStore = true;
            takFilePicker.launch("*/*");
        });
        takClientCertButton.setOnClickListener(v -> {
            pickingTrustStore = false;
            takFilePicker.launch("*/*");
        });

        // Test connection button
        takTestConnectionButton.setOnClickListener(v -> testTakConnection());

        // QR scan button
        Button enrollQrButton = findViewById(R.id.takEnrollQrButton);
        enrollQrButton.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("Scan TAK enrollment QR code");
            options.setBeepEnabled(false);
            options.setOrientationLocked(false);
            takEnrollQrLauncher.launch(options);
        });

        // Show enrollment status if certs are already installed
        String existingTrust = prefs.getString(KEY_TAK_TRUSTSTORE_PATH, "");
        String existingClient = prefs.getString(KEY_TAK_CLIENT_CERT_PATH, "");
        String existingServer = prefs.getString(KEY_TAK_SERVER_ADDRESS, "");
        if (!existingTrust.isEmpty() && !existingClient.isEmpty()
                && new File(existingTrust).exists() && new File(existingClient).exists()) {
            enrollStatusView.setVisibility(View.VISIBLE);
            enrollStatusView.setText("Connected to: " + existingServer);
            enrollStatusView.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        }

        // Certificate enrollment
        EditText enrollUsername = findViewById(R.id.takEnrollUsername);
        EditText enrollPassword = findViewById(R.id.takEnrollPassword);
        Button enrollButton = findViewById(R.id.takEnrollButton);
        TextView enrollStatus = findViewById(R.id.takEnrollStatus);

        EditText enrollPortInput = findViewById(R.id.takEnrollPort);
        enrollPortInput.setText("8446");

        enrollButton.setOnClickListener(v -> {
            String user = enrollUsername.getText().toString().trim();
            String pass = enrollPassword.getText().toString().trim();
            String addr = takServerAddress.getText().toString().trim();

            if (addr.isEmpty()) {
                Toast.makeText(this, "Enter a server address first", Toast.LENGTH_SHORT).show();
                return;
            }
            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Enter username and password", Toast.LENGTH_SHORT).show();
                return;
            }

            // Save server address/port before enrollment
            saveTakSettings();

            // Save enrollment credentials for group assignment
            prefs.edit()
                    .putString(KEY_TAK_ENROLL_USERNAME, user)
                    .putString(KEY_TAK_ENROLL_PASSWORD, pass)
                    .apply();

            enrollButton.setEnabled(false);
            enrollButton.setText("Enrolling...");
            enrollStatus.setVisibility(View.VISIBLE);
            enrollStatus.setText("Connecting to server...");
            enrollStatus.setTextColor(getResources().getColor(R.color.text_secondary));

            String enrollUid = prefs.getString(KEY_TAK_UID, "");
            int parsedPort = 8446;
            try {
                String portStr = enrollPortInput.getText().toString().trim();
                if (!portStr.isEmpty()) parsedPort = Integer.parseInt(portStr);
            } catch (NumberFormatException ignored) {}
            final int enrollPort = parsedPort;

            new Thread(() -> {
                TakCertEnroller.enroll(
                        addr, enrollPort, user, pass, enrollUid, getFilesDir(),
                        new TakCertEnroller.EnrollmentCallback() {
                            @Override
                            public void onSuccess(String trustStorePath, String clientCertPath) {
                                // Save cert paths
                                prefs.edit()
                                        .putString(KEY_TAK_TRUSTSTORE_PATH, trustStorePath)
                                        .putString(KEY_TAK_CLIENT_CERT_PATH, clientCertPath)
                                        .putString(KEY_TAK_CERT_PASSWORD, "atakatak")
                                        .apply();

                                // Also set callsign from username if empty
                                String cs = prefs.getString(KEY_TAK_CALLSIGN, "");
                                if (cs.isEmpty()) {
                                    prefs.edit().putString(KEY_TAK_CALLSIGN, user).apply();
                                }

                                runOnUiThread(() -> {
                                    enrollButton.setEnabled(true);
                                    enrollButton.setText("Enroll & Download Certs");
                                    enrollStatus.setText("Enrollment successful! Certs installed.");
                                    enrollStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                                    takTrustStoreLabel.setText(new File(trustStorePath).getName());
                                    takClientCertLabel.setText(new File(clientCertPath).getName());
                                    takCertPassword.setText("atakatak");
                                    if (cs.isEmpty()) {
                                        takCallsign.setText(user);
                                    }
                                    Toast.makeText(SettingsActivity.this,
                                            "Certificates enrolled successfully!", Toast.LENGTH_LONG).show();

                                    // Auto-reconnect TakManager with new certs
                                    reconnectTakManager();
                                });
                            }

                            @Override
                            public void onError(String message) {
                                runOnUiThread(() -> {
                                    enrollButton.setEnabled(true);
                                    enrollButton.setText("Enroll & Download Certs");
                                    enrollStatus.setText("Enrollment failed: " + message);
                                    enrollStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                                    Toast.makeText(SettingsActivity.this,
                                            "Enrollment failed: " + message, Toast.LENGTH_LONG).show();
                                });
                            }
                        });
            }).start();
        });

        // Generate/load UID
        String takUid = prefs.getString(KEY_TAK_UID, "");
        if (takUid.isEmpty()) {
            takUid = CotBuilder.generateUid();
            prefs.edit().putString(KEY_TAK_UID, takUid).apply();
        }
    }

    /**
     * Parse a scanned QR code for TAK certificate enrollment.
     * Supported formats:
     *   ATAK:  tak://com.atakmap.app/enroll?host=SERVER&username=USER&token=PASSWORD
     *   iTAK:  Name,ServerAddress,Port,Protocol
     */
    private void parseTakEnrollQrCode(String scanned) {
        String host = null, username = null, password = null;
        int port = 8089;
        int enrollPort = 8446;

        try {
            String trimmed = scanned.trim();

            if (trimmed.startsWith("tak://")) {
                // ATAK format: tak://com.atakmap.app/enroll?host=X&username=Y&token=Z
                Uri uri = Uri.parse(trimmed);
                host = uri.getQueryParameter("host");
                username = uri.getQueryParameter("username");
                password = uri.getQueryParameter("token");
                String portStr = uri.getQueryParameter("port");
                if (portStr != null) {
                    try { port = Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}
                }
                String enrollPortStr = uri.getQueryParameter("enrollPort");
                if (enrollPortStr != null) {
                    try { enrollPort = Integer.parseInt(enrollPortStr); } catch (NumberFormatException ignored) {}
                }
            } else if (trimmed.contains(",")) {
                // iTAK format: Name,ServerAddress,Port,Protocol
                String[] parts = trimmed.split(",");
                if (parts.length >= 2) {
                    host = parts[1].trim();
                }
                if (parts.length >= 3) {
                    try { port = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException ignored) {}
                }
            } else {
                Log.w(TAG, "Unknown TAK QR format: " + trimmed);
                Toast.makeText(this, "Unrecognized QR format: " + trimmed, Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse TAK enrollment QR: " + scanned, e);
            Toast.makeText(this, "Invalid TAK QR code", Toast.LENGTH_LONG).show();
            return;
        }

        if (host == null || host.isEmpty()) {
            Toast.makeText(this, "QR code missing server address", Toast.LENGTH_LONG).show();
            return;
        }

        // Auto-fill TAK server fields
        if (takServerAddress != null) takServerAddress.setText(host);
        if (takServerPort != null) takServerPort.setText(String.valueOf(port));

        // Save server settings immediately
        saveTakSettings();

        // Auto-fill enrollment fields
        EditText enrollUsername = findViewById(R.id.takEnrollUsername);
        EditText enrollPassword = findViewById(R.id.takEnrollPassword);
        EditText enrollPortInput = findViewById(R.id.takEnrollPort);

        if (username != null && enrollUsername != null) enrollUsername.setText(username);
        if (password != null && enrollPassword != null) enrollPassword.setText(password);
        if (enrollPortInput != null) enrollPortInput.setText(String.valueOf(enrollPort));

        // Set callsign from username if callsign is empty
        if (username != null && takCallsign != null) {
            String cs = takCallsign.getText().toString().trim();
            if (cs.isEmpty()) {
                takCallsign.setText(username);
            }
        }

        saveTakSettings();
        Log.d(TAG, "TAK QR applied: host=" + host + ", port=" + port + ", user=" + username);

        // Save enrollment credentials for group assignment
        if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_TAK_ENROLL_USERNAME, username)
                    .putString(KEY_TAK_ENROLL_PASSWORD, password)
                    .apply();
        }

        // Auto-enroll if we have all the credentials
        if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
            autoEnroll(host, enrollPort, username, password);
        } else {
            Toast.makeText(this, "TAK server loaded — enter credentials in Advanced Settings to enroll",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Automatically enroll with TAK server after QR scan.
     */
    private void autoEnroll(String serverAddress, int enrollPort, String username, String password) {
        Button enrollQrButton = findViewById(R.id.takEnrollQrButton);
        enrollQrButton.setEnabled(false);
        enrollQrButton.setText("Enrolling...");

        enrollStatusView.setVisibility(View.VISIBLE);
        enrollStatusView.setText("Connecting to " + serverAddress + "...");
        enrollStatusView.setTextColor(getResources().getColor(R.color.text_secondary));

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String enrollUid = prefs.getString(KEY_TAK_UID, "");

        new Thread(() -> {
            TakCertEnroller.enroll(
                    serverAddress, enrollPort, username, password, enrollUid, getFilesDir(),
                    new TakCertEnroller.EnrollmentCallback() {
                        @Override
                        public void onSuccess(String trustStorePath, String clientCertPath) {
                            prefs.edit()
                                    .putString(KEY_TAK_TRUSTSTORE_PATH, trustStorePath)
                                    .putString(KEY_TAK_CLIENT_CERT_PATH, clientCertPath)
                                    .putString(KEY_TAK_CERT_PASSWORD, "atakatak")
                                    .apply();

                            String cs = prefs.getString(KEY_TAK_CALLSIGN, "");
                            if (cs.isEmpty()) {
                                prefs.edit().putString(KEY_TAK_CALLSIGN, username).apply();
                            }

                            runOnUiThread(() -> {
                                enrollQrButton.setEnabled(true);
                                enrollQrButton.setText("Scan Enrollment QR Code");
                                enrollStatusView.setText("Connected to: " + serverAddress);
                                enrollStatusView.setTextColor(getResources().getColor(android.R.color.holo_green_dark));

                                if (takTrustStoreLabel != null)
                                    takTrustStoreLabel.setText(new File(trustStorePath).getName());
                                if (takClientCertLabel != null)
                                    takClientCertLabel.setText(new File(clientCertPath).getName());
                                if (takCertPassword != null)
                                    takCertPassword.setText("atakatak");
                                if (cs.isEmpty() && takCallsign != null)
                                    takCallsign.setText(username);

                                Toast.makeText(SettingsActivity.this,
                                        "Enrolled successfully!", Toast.LENGTH_LONG).show();

                                // Auto-reconnect TakManager with new certs
                                reconnectTakManager();
                            });
                        }

                        @Override
                        public void onError(String message) {
                            runOnUiThread(() -> {
                                enrollQrButton.setEnabled(true);
                                enrollQrButton.setText("Scan Enrollment QR Code");
                                enrollStatusView.setText("Enrollment failed: " + message);
                                enrollStatusView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                                Toast.makeText(SettingsActivity.this,
                                        "Enrollment failed: " + message, Toast.LENGTH_LONG).show();
                            });
                        }
                    });
        }).start();
    }

    private void saveTakSettings() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        if (takServerAddress != null) {
            editor.putString(KEY_TAK_SERVER_ADDRESS, takServerAddress.getText().toString());
        }
        if (takServerPort != null) {
            String portStr = takServerPort.getText().toString();
            if (!portStr.isEmpty()) {
                try {
                    editor.putInt(KEY_TAK_SERVER_PORT, Integer.parseInt(portStr));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (takCallsign != null) {
            editor.putString(KEY_TAK_CALLSIGN, takCallsign.getText().toString());
        }
        if (takCertPassword != null) {
            editor.putString(KEY_TAK_CERT_PASSWORD, takCertPassword.getText().toString());
        }
        editor.apply();
    }

    private void copyFileToInternal(Uri uri, String destName, String key, TextView label) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return;
            File dest = new File(getFilesDir(), destName);
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
            fos.close();
            is.close();

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(key, dest.getAbsolutePath()).apply();
            label.setText(dest.getName());
            Toast.makeText(this, "File imported: " + dest.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy file", e);
            Toast.makeText(this, "Failed to import file", Toast.LENGTH_SHORT).show();
        }
    }

    private void testTakConnection() {
        saveTakSettings();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String address = prefs.getString(KEY_TAK_SERVER_ADDRESS, "");
        int port = prefs.getInt(KEY_TAK_SERVER_PORT, 8089);
        String trustStore = prefs.getString(KEY_TAK_TRUSTSTORE_PATH, "");
        String clientCert = prefs.getString(KEY_TAK_CLIENT_CERT_PATH, "");
        String certPw = prefs.getString(KEY_TAK_CERT_PASSWORD, "atakatak");

        if (address.isEmpty()) {
            Toast.makeText(this, "Enter a server address first", Toast.LENGTH_SHORT).show();
            return;
        }

        takTestConnectionButton.setEnabled(false);
        takTestConnectionButton.setText("Testing...");

        new Thread(() -> {
            try {
                TakClient testClient = new TakClient(
                        address, port, trustStore, certPw, clientCert, certPw,
                        new TakClient.TakClientListener() {
                            @Override public void onCotReceived(String xml) {}
                            @Override public void onConnected() {}
                            @Override public void onDisconnected() {}
                        });
                testClient.start();
                Thread.sleep(3000);
                boolean success = testClient.isConnected();
                testClient.stopClient();

                runOnUiThread(() -> {
                    takTestConnectionButton.setEnabled(true);
                    takTestConnectionButton.setText("Test Connection");
                    if (success) {
                        Toast.makeText(this, "TAK Server: Connected!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "TAK Server: Connection failed", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "TAK test connection failed", e);
                runOnUiThread(() -> {
                    takTestConnectionButton.setEnabled(true);
                    takTestConnectionButton.setText("Test Connection");
                    Toast.makeText(this, "TAK Server: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // --- Group Assignment ---

    private void setupGroupAssignment(SharedPreferences prefs) {
        Button fetchGroupsButton = findViewById(R.id.takFetchGroupsButton);

        fetchGroupsButton.setOnClickListener(v -> {
            String address = takServerAddress.getText().toString().trim();
            String trustStorePath = prefs.getString(KEY_TAK_TRUSTSTORE_PATH, "");
            String clientCertPath = prefs.getString(KEY_TAK_CLIENT_CERT_PATH, "");
            String certPw = prefs.getString(KEY_TAK_CERT_PASSWORD, "atakatak");

            // Get enrollment credentials for admin-level API access
            // Try UI fields first, then fall back to saved prefs (from QR enrollment)
            EditText enrollUsername = findViewById(R.id.takEnrollUsername);
            EditText enrollPassword = findViewById(R.id.takEnrollPassword);
            String user = enrollUsername != null ? enrollUsername.getText().toString().trim() : "";
            String pass = enrollPassword != null ? enrollPassword.getText().toString().trim() : "";
            if (user.isEmpty()) user = prefs.getString(KEY_TAK_ENROLL_USERNAME, "");
            if (pass.isEmpty()) pass = prefs.getString(KEY_TAK_ENROLL_PASSWORD, "");

            if (address.isEmpty()) {
                Toast.makeText(this, "Enter a server address first", Toast.LENGTH_SHORT).show();
                return;
            }
            if (trustStorePath.isEmpty() || clientCertPath.isEmpty()) {
                Toast.makeText(this, "Enroll certificates first", Toast.LENGTH_SHORT).show();
                return;
            }

            fetchGroupsButton.setEnabled(false);
            fetchGroupsButton.setText("Fetching...");
            groupStatus.setVisibility(View.VISIBLE);
            groupStatus.setText("Fetching groups from server...");
            groupStatus.setTextColor(getResources().getColor(R.color.text_secondary));

            final String basicAuth = (!user.isEmpty() && !pass.isEmpty()) ?
                    "Basic " + android.util.Base64.encodeToString(
                            (user + ":" + pass).getBytes(), android.util.Base64.NO_WRAP) : null;

            new Thread(() -> {
                try {
                    SSLContext sslCtx = createMutualTlsContext(trustStorePath, clientCertPath, certPw);

                    List<String> groups = new ArrayList<>();

                    // Try with Basic Auth first (admin access sees all groups)
                    if (basicAuth != null) {
                        try {
                            String groupsJson = takApiGet("https://" + address + ":8443/Marti/api/groups/all", sslCtx, basicAuth);
                            Log.d(TAG, "Groups API response (auth): " + groupsJson.substring(0, Math.min(groupsJson.length(), 500)));
                            groups = parseGroups(groupsJson);
                            Log.d(TAG, "Parsed " + groups.size() + " groups with auth");
                        } catch (Exception e1) {
                            Log.w(TAG, "Groups with auth failed: " + e1.getMessage());
                        }
                    }

                    // Fallback: try with just client cert
                    if (groups.isEmpty()) {
                        try {
                            String groupsJson = takApiGet("https://" + address + ":8443/Marti/api/groups/all", sslCtx, null);
                            Log.d(TAG, "Groups API response (cert only): " + groupsJson.substring(0, Math.min(groupsJson.length(), 500)));
                            groups = parseGroups(groupsJson);
                            Log.d(TAG, "Parsed " + groups.size() + " groups with cert only");
                        } catch (Exception e2) {
                            Log.w(TAG, "Groups with cert only failed: " + e2.getMessage());
                        }
                    }

                    final List<String> foundGroups = groups;

                    runOnUiThread(() -> {
                        fetchGroupsButton.setEnabled(true);
                        fetchGroupsButton.setText("Fetch Groups");

                        if (foundGroups.isEmpty()) {
                            groupStatus.setText("No groups found on server");
                            groupStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                            return;
                        }

                        availableGroups.clear();
                        availableGroups.addAll(foundGroups);
                        groupCheckboxContainer.removeAllViews();

                        for (String group : foundGroups) {
                            CheckBox cb = new CheckBox(this);
                            cb.setText(group);
                            cb.setTextColor(getResources().getColor(R.color.text_primary));
                            cb.setTextSize(14);
                            cb.setChecked(true); // Default to all selected
                            cb.setPadding(0, 4, 0, 4);
                            groupCheckboxContainer.addView(cb);
                        }

                        assignGroupsButton.setVisibility(View.VISIBLE);
                        groupStatus.setText("Select groups to join, then tap Assign");
                        groupStatus.setTextColor(getResources().getColor(R.color.text_secondary));
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Failed to fetch groups", e);
                    runOnUiThread(() -> {
                        fetchGroupsButton.setEnabled(true);
                        fetchGroupsButton.setText("Fetch Groups");
                        groupStatus.setText("Error: " + e.getMessage());
                        groupStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    });
                }
            }).start();
        });

        assignGroupsButton.setOnClickListener(v -> {
            String address = takServerAddress.getText().toString().trim();
            String trustStorePath = prefs.getString(KEY_TAK_TRUSTSTORE_PATH, "");
            String clientCertPath = prefs.getString(KEY_TAK_CLIENT_CERT_PATH, "");
            String certPw = prefs.getString(KEY_TAK_CERT_PASSWORD, "atakatak");

            // Get enrollment credentials for API auth
            // Try UI fields first, then fall back to saved prefs (from QR enrollment)
            EditText enrollUser2 = findViewById(R.id.takEnrollUsername);
            EditText enrollPass2 = findViewById(R.id.takEnrollPassword);
            String u2 = enrollUser2 != null ? enrollUser2.getText().toString().trim() : "";
            String p2 = enrollPass2 != null ? enrollPass2.getText().toString().trim() : "";
            if (u2.isEmpty()) u2 = prefs.getString(KEY_TAK_ENROLL_USERNAME, "");
            if (p2.isEmpty()) p2 = prefs.getString(KEY_TAK_ENROLL_PASSWORD, "");
            final String authHeader = (!u2.isEmpty() && !p2.isEmpty()) ?
                    "Basic " + android.util.Base64.encodeToString(
                            (u2 + ":" + p2).getBytes(), android.util.Base64.NO_WRAP) : null;

            // Collect selected groups
            List<String> selectedGroups = new ArrayList<>();
            for (int i = 0; i < groupCheckboxContainer.getChildCount(); i++) {
                View child = groupCheckboxContainer.getChildAt(i);
                if (child instanceof CheckBox) {
                    CheckBox cb = (CheckBox) child;
                    if (cb.isChecked()) {
                        selectedGroups.add(cb.getText().toString());
                    }
                }
            }

            if (selectedGroups.isEmpty()) {
                Toast.makeText(this, "Select at least one group", Toast.LENGTH_SHORT).show();
                return;
            }

            assignGroupsButton.setEnabled(false);
            assignGroupsButton.setText("Assigning...");
            groupStatus.setVisibility(View.VISIBLE);
            groupStatus.setText("Assigning groups...");

            new Thread(() -> {
                try {
                    SSLContext sslCtx = createMutualTlsContext(trustStorePath, clientCertPath, certPw);

                    // Get the certificate CN (username) from the client cert
                    String certCN = getCertificateCN(clientCertPath, certPw);
                    if (certCN == null || certCN.isEmpty()) {
                        throw new Exception("Could not determine certificate CN");
                    }

                    // Build the group assignment request
                    // TAK Server API: PUT /Marti/api/groups/{groupName}/users
                    // or PUT /user-management/api/update-groups
                    boolean success = false;
                    String lastError = "";

                    // Try assigning each group individually
                    for (String group : selectedGroups) {
                        try {
                            String url = "https://" + address + ":8443/Marti/api/groups/"
                                    + java.net.URLEncoder.encode(group, "UTF-8")
                                    + "/users";
                            takApiPut(url, "[\"" + certCN + "\"]", sslCtx, authHeader);
                            success = true;
                            Log.d(TAG, "Assigned group: " + group);
                        } catch (Exception e1) {
                            Log.w(TAG, "Group assign method 1 failed for " + group + ": " + e1.getMessage());
                            lastError = e1.getMessage();
                        }
                    }

                    // If individual assignment didn't work, try bulk update
                    if (!success) {
                        try {
                            JSONObject body = new JSONObject();
                            body.put("username", certCN);
                            JSONArray groupArray = new JSONArray();
                            for (String g : selectedGroups) {
                                JSONObject gObj = new JSONObject();
                                gObj.put("name", g);
                                gObj.put("direction", "BOTH");
                                groupArray.put(gObj);
                            }
                            body.put("groups", groupArray);

                            String url = "https://" + address + ":8443/user-management/api/update-groups";
                            takApiPut(url, body.toString(), sslCtx, authHeader);
                            success = true;
                            Log.d(TAG, "Bulk group assignment succeeded");
                        } catch (Exception e2) {
                            Log.w(TAG, "Bulk group assign failed: " + e2.getMessage());
                            lastError = e2.getMessage();
                        }
                    }

                    final boolean finalSuccess = success;
                    final String finalError = lastError;

                    runOnUiThread(() -> {
                        assignGroupsButton.setEnabled(true);
                        assignGroupsButton.setText("Assign Selected Groups");
                        if (finalSuccess) {
                            groupStatus.setText("Groups assigned! Reconnecting...");
                            groupStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                            Toast.makeText(this, "Groups assigned successfully!", Toast.LENGTH_LONG).show();
                        } else {
                            // Assignment API failed (405/403 is common on many TAK servers)
                            // Groups are often auto-assigned via certificate — reconnect anyway
                            groupStatus.setText("Reconnecting with server groups...");
                            groupStatus.setTextColor(getResources().getColor(R.color.text_secondary));
                            Log.w(TAG, "Group assignment API failed, reconnecting anyway: " + finalError);
                        }
                        // Always reconnect so server-side group membership takes effect
                        reconnectTakManager();
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Group assignment failed", e);
                    runOnUiThread(() -> {
                        assignGroupsButton.setEnabled(true);
                        assignGroupsButton.setText("Assign Selected Groups");
                        groupStatus.setText("Error: " + e.getMessage());
                        groupStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    });
                }
            }).start();
        });
    }

    /**
     * Reconnect TakManager with current settings so group changes take effect.
     */
    private void reconnectTakManager() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String address = prefs.getString(KEY_TAK_SERVER_ADDRESS, "");
        int port = prefs.getInt(KEY_TAK_SERVER_PORT, 8089);
        String trustStore = prefs.getString(KEY_TAK_TRUSTSTORE_PATH, "");
        String clientCert = prefs.getString(KEY_TAK_CLIENT_CERT_PATH, "");
        String certPw = prefs.getString(KEY_TAK_CERT_PASSWORD, "atakatak");
        String uid = prefs.getString(KEY_TAK_UID, "");
        String callsign = prefs.getString(KEY_TAK_CALLSIGN, "");
        String team = prefs.getString(KEY_TAK_TEAM, "Cyan");
        String role = prefs.getString(KEY_TAK_ROLE, "Team Member");

        if (address.isEmpty() || trustStore.isEmpty() || clientCert.isEmpty()) return;

        TakManager mgr = TakManager.getInstance();
        mgr.disconnect();
        mgr.connect(uid, callsign, team, role, address, port,
                trustStore, certPw, clientCert, certPw);
        Log.d(TAG, "TakManager reconnected after group assignment");
    }

    private SSLContext createMutualTlsContext(String trustStorePath, String clientCertPath, String certPw) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        FileInputStream trustIs = new FileInputStream(trustStorePath);
        trustStore.load(trustIs, certPw.toCharArray());
        trustIs.close();

        KeyStore clientStore = KeyStore.getInstance("PKCS12");
        FileInputStream clientIs = new FileInputStream(clientCertPath);
        clientStore.load(clientIs, certPw.toCharArray());
        clientIs.close();

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, certPw.toCharArray());

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslCtx;
    }

    private String takApiGet(String urlStr, SSLContext sslCtx, String authHeader) throws Exception {
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(sslCtx.getSocketFactory());
        conn.setHostnameVerifier((h, s) -> true);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        if (authHeader != null) conn.setRequestProperty("Authorization", authHeader);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int code = conn.getResponseCode();
        Log.d(TAG, "API GET " + urlStr + " -> " + code);
        if (code != 200) {
            throw new Exception("HTTP " + code + " from " + urlStr);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }

    private void takApiPut(String urlStr, String body, SSLContext sslCtx, String authHeader) throws Exception {
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(sslCtx.getSocketFactory());
        conn.setHostnameVerifier((h, s) -> true);
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if (authHeader != null) conn.setRequestProperty("Authorization", authHeader);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);

        conn.getOutputStream().write(body.getBytes());
        conn.getOutputStream().flush();
        conn.getOutputStream().close();

        int code = conn.getResponseCode();
        Log.d(TAG, "API PUT " + urlStr + " -> " + code);
        if (code < 200 || code >= 300) {
            // Read error body
            InputStream errStream = conn.getErrorStream();
            if (errStream != null) {
                BufferedReader er = new BufferedReader(new InputStreamReader(errStream));
                StringBuilder esb = new StringBuilder();
                String eline;
                while ((eline = er.readLine()) != null) esb.append(eline);
                er.close();
                throw new Exception("HTTP " + code + ": " + esb.toString());
            }
            throw new Exception("HTTP " + code);
        }
    }

    private List<String> parseGroups(String json) {
        List<String> groups = new ArrayList<>();
        try {
            // Try as JSON array of group objects
            JSONArray arr;
            if (json.trim().startsWith("{")) {
                JSONObject obj = new JSONObject(json);
                if (obj.has("data")) {
                    arr = obj.getJSONArray("data");
                } else if (obj.has("groups")) {
                    arr = obj.getJSONArray("groups");
                } else {
                    return groups;
                }
            } else {
                arr = new JSONArray(json);
            }

            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.get(i);
                if (item instanceof JSONObject) {
                    JSONObject gObj = (JSONObject) item;
                    String name = gObj.optString("name", "");
                    if (!name.isEmpty() && !name.startsWith("__")) {
                        groups.add(name);
                    }
                } else if (item instanceof String) {
                    String name = (String) item;
                    if (!name.startsWith("__")) {
                        groups.add(name);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse groups JSON: " + e.getMessage());
        }
        return groups;
    }

    private String getCertificateCN(String certPath, String password) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            FileInputStream fis = new FileInputStream(certPath);
            ks.load(fis, password.toCharArray());
            fis.close();

            java.util.Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                java.security.cert.Certificate cert = ks.getCertificate(alias);
                if (cert instanceof java.security.cert.X509Certificate) {
                    java.security.cert.X509Certificate x509 = (java.security.cert.X509Certificate) cert;
                    String dn = x509.getSubjectX500Principal().getName();
                    // Parse CN from DN like "CN=username,O=TAK,OU=TAK"
                    for (String part : dn.split(",")) {
                        String trimmed = part.trim();
                        if (trimmed.startsWith("CN=")) {
                            return trimmed.substring(3);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read cert CN", e);
        }
        return null;
    }

    // Static getters for TAK settings
    public static String getTakServerAddress(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        return prefs.getString(KEY_TAK_SERVER_ADDRESS, "");
    }

    public static int getTakServerPort(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        return prefs.getInt(KEY_TAK_SERVER_PORT, 8089);
    }

    public static String getTakCallsign(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        return prefs.getString(KEY_TAK_CALLSIGN, "");
    }

    public static String getTakTeam(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        return prefs.getString(KEY_TAK_TEAM, "Cyan");
    }

    public static String getTakRole(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        return prefs.getString(KEY_TAK_ROLE, "Team Member");
    }

    public static String getTakTrustStorePath(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        return prefs.getString(KEY_TAK_TRUSTSTORE_PATH, "");
    }

    public static String getTakClientCertPath(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        return prefs.getString(KEY_TAK_CLIENT_CERT_PATH, "");
    }

    public static String getTakCertPassword(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        return prefs.getString(KEY_TAK_CERT_PASSWORD, "atakatak");
    }

    public static String getTakUid(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        String uid = prefs.getString(KEY_TAK_UID, "");
        if (uid.isEmpty()) {
            uid = CotBuilder.generateUid();
            prefs.edit().putString(KEY_TAK_UID, uid).apply();
        }
        return uid;
    }
}
