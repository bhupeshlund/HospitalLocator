package com.example.hospitallocator;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private MapView map;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private double currentLat = 0.0;
    private double currentLng = 0.0;
    private boolean isInitialLocationSet = false;
    private Marker userMarker;

    private static final String PREFS_NAME = "EmergencyPrefs";
    private static final String KEY_CONTACT = "emergency_contact";

    // Humanitarian OpenStreetMap Tile Server (100% Free, No API key, No 403 blocks)
    public static final OnlineTileSourceBase OSM_HOT = new XYTileSource(
            "OSM_HOT_v3",
            0, 19, 256, ".png",
            new String[]{
                    "https://a.tile.openstreetmap.fr/hot/",
                    "https://b.tile.openstreetmap.fr/hot/",
                    "https://c.tile.openstreetmap.fr/hot/"
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Explicit User-Agent required by OpenStreetMap usage policy
        Configuration.getInstance().setUserAgentValue("EmergencyHospitalLocator_MumbaiApp_v1");
        Configuration.getInstance().load(this, getSharedPreferences("osm_prefs", MODE_PRIVATE));

        // Purge old 403 tiles from disk cache
        clearOsmdroidDiskCache();

        setContentView(R.layout.activity_main);

        // Initialize MapView with Humanitarian OpenStreetMap engine
        map = findViewById(R.id.map);
        map.setTileSource(OSM_HOT);
        map.setMultiTouchControls(true);
        map.getController().setZoom(15.0);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        Button btnSearchHospitals = findViewById(R.id.btn_search_hospitals);
        Button btnSetContact = findViewById(R.id.btn_set_contact);
        Button btnCallAmbulance = findViewById(R.id.btn_call_ambulance);
        Button btnFirstAid = findViewById(R.id.btn_first_aid);
        Button btnSos = findViewById(R.id.btn_sos);

        btnSearchHospitals.setOnClickListener(v -> searchNearbyHospitalsInApp());
        btnSetContact.setOnClickListener(v -> showSetContactDialog());
        btnCallAmbulance.setOnClickListener(v -> callAmbulance());
        btnFirstAid.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, FirstAidActivity.class)));
        btnSos.setOnClickListener(v -> triggerSOS());

        setupLocationCallback();
        startLocationUpdates();
    }

    private void clearOsmdroidDiskCache() {
        try {
            File cacheDir = new File(getCacheDir(), "osmdroid");
            if (cacheDir.exists()) {
                deleteRecursive(cacheDir);
            }
        } catch (Exception ignored) {}
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] files = fileOrDirectory.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    private void setupLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        currentLat = location.getLatitude();
                        currentLng = location.getLongitude();
                        updateUserLocationOnMap();
                    }
                }
            }
        };
    }

    private void updateUserLocationOnMap() {
        if (map == null) return;

        GeoPoint userPoint = new GeoPoint(currentLat, currentLng);

        if (userMarker == null) {
            userMarker = new Marker(map);
            userMarker.setTitle("Your Location");
            userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            map.getOverlays().add(userMarker);
        }

        userMarker.setPosition(userPoint);

        if (!isInitialLocationSet) {
            map.getController().setCenter(userPoint);
            isInitialLocationSet = true;
        }

        map.invalidate();
    }

    private void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            LocationRequest locationRequest = new LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY, 3000)
                    .setMinUpdateIntervalMillis(1000)
                    .build();

            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    private void searchNearbyHospitalsInApp() {
        if (currentLat == 0.0 && currentLng == 0.0) {
            Toast.makeText(this, "Acquiring live GPS location... Please wait.", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Searching nearby hospitals...", Toast.LENGTH_SHORT).show();

        map.getOverlays().clear();
        if (userMarker != null) {
            map.getOverlays().add(userMarker);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                String overpassUrl = "https://overpass-api.de/api/interpreter?data=[out:json];node[\"amenity\"=\"hospital\"](around:5000,"
                        + currentLat + "," + currentLng + ");out;";

                URL url = new URL(overpassUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "EmergencyHospitalLocator_MumbaiApp_v1");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject jsonObject = new JSONObject(response.toString());
                JSONArray elements = jsonObject.getJSONArray("elements");

                runOnUiThread(() -> {
                    if (elements.length() == 0) {
                        Toast.makeText(MainActivity.this, "No hospitals found within 5km.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int hospitalCount = 0;
                    for (int i = 0; i < elements.length(); i++) {
                        try {
                            JSONObject elem = elements.getJSONObject(i);
                            double lat = elem.getDouble("lat");
                            double lon = elem.getDouble("lon");

                            String name = "Hospital";
                            if (elem.has("tags") && elem.getJSONObject("tags").has("name")) {
                                name = elem.getJSONObject("tags").getString("name");
                            }

                            GeoPoint hospitalPoint = new GeoPoint(lat, lon);
                            Marker hospitalMarker = new Marker(map);
                            hospitalMarker.setPosition(hospitalPoint);
                            hospitalMarker.setTitle(name);
                            hospitalMarker.setSnippet("Emergency Care Available");
                            hospitalMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

                            map.getOverlays().add(hospitalMarker);
                            hospitalCount++;
                        } catch (Exception ignored) {}
                    }

                    if (hospitalCount > 0) {
                        Toast.makeText(MainActivity.this, "Found " + hospitalCount + " hospitals nearby!", Toast.LENGTH_SHORT).show();
                        map.invalidate();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error fetching hospitals. Check internet connection.", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showSetContactDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Set SOS Emergency Phone Number");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        input.setText(prefs.getString(KEY_CONTACT, ""));
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String number = input.getText().toString().trim();
            if (!number.isEmpty()) {
                prefs.edit().putString(KEY_CONTACT, number).apply();
                Toast.makeText(MainActivity.this, "Emergency Contact Saved!", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void callAmbulance() {
        Intent intent = new Intent(Intent.ACTION_DIAL);
        intent.setData(Uri.parse("tel:108"));
        startActivity(intent);
    }

    private void triggerSOS() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String emergencyContact = prefs.getString(KEY_CONTACT, "");

        if (emergencyContact.isEmpty()) {
            Toast.makeText(this, "Please set an SOS contact number first!", Toast.LENGTH_LONG).show();
            showSetContactDialog();
        } else {
            SOSHelper.sendEmergencySMS(this, emergencyContact, currentLat, currentLng);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        map.onPause();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                Toast.makeText(this, "Location permission is required for map features", Toast.LENGTH_SHORT).show();
            }
        }
    }
}