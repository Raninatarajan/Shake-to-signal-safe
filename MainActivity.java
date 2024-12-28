package com.example.sosapp;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final String PREFS_NAME = "SOSAppPrefs";
    private static final String EMERGENCY_CONTACTS_KEY = "EmergencyContacts";

    private SensorManager sensorManager;
    private float accelerationCurrentValue;
    private float accelerationPreviousValue;
    private float shakeThreshold = 12f;
    private FusedLocationProviderClient fusedLocationClient;
    private EditText contactInput;
    private ArrayAdapter<String> adapter;
    private ArrayList<String> emergencyContacts;
    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize the accelerometer to detect shake
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);

        // Initialize the location service
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Initialize the shake detection variables
        accelerationCurrentValue = SensorManager.GRAVITY_EARTH;
        accelerationPreviousValue = SensorManager.GRAVITY_EARTH;

        // Request permissions (SEND_SMS and ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Initialize the contact input and list view
        contactInput = findViewById(R.id.contact_input);
        ListView contactsListView = findViewById(R.id.contacts_list_view);

        // Load existing emergency contacts
        emergencyContacts = loadEmergencyContacts();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, emergencyContacts);
        contactsListView.setAdapter(adapter);

        // Set up the save button to add a new contact number
        Button saveButton = findViewById(R.id.save_button);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String enteredNumber = contactInput.getText().toString().trim();
                if (!enteredNumber.isEmpty() && !emergencyContacts.contains(enteredNumber)) {
                    addEmergencyContact(enteredNumber);
                    Toast.makeText(MainActivity.this, "Emergency contact added!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Please enter a valid number or it's already added", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Set up the list item click listener to remove a contact
        contactsListView.setOnItemClickListener((parent, view, position, id) -> {
            String contactToRemove = emergencyContacts.get(position);
            removeEmergencyContact(contactToRemove);
            Toast.makeText(MainActivity.this, "Emergency contact removed!", Toast.LENGTH_SHORT).show();
        });
    }

    // Function to load emergency contacts from SharedPreferences
    private ArrayList<String> loadEmergencyContacts() {
        Set<String> contactsSet = sharedPreferences.getStringSet(EMERGENCY_CONTACTS_KEY, new HashSet<>());
        return new ArrayList<>(contactsSet);
    }

    // Function to add a new emergency contact number
    private void addEmergencyContact(String contactNumber) {
        emergencyContacts.add(contactNumber);
        saveEmergencyContacts();
        adapter.notifyDataSetChanged();
    }

    // Function to remove an emergency contact number
    private void removeEmergencyContact(String contactNumber) {
        emergencyContacts.remove(contactNumber);
        saveEmergencyContacts();
        adapter.notifyDataSetChanged();
    }

    // Function to save the emergency contacts to SharedPreferences
    private void saveEmergencyContacts() {
        Set<String> contactsSet = new HashSet<>(emergencyContacts);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putStringSet(EMERGENCY_CONTACTS_KEY, contactsSet);
        editor.apply();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        float acceleration = (float) Math.sqrt(x * x + y * y + z * z);
        float delta = acceleration - accelerationPreviousValue;
        accelerationPreviousValue = accelerationCurrentValue;
        accelerationCurrentValue = acceleration;

        if (delta > shakeThreshold) {
            // Phone has been shaken, send SOS message
            sendSOSMessage();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do nothing for now
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void sendSOSMessage() {
        // Get the saved emergency contact numbers
        for (String phoneNumber : emergencyContacts) {
            // Get location and send SMS
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            fusedLocationClient.getLastLocation().addOnSuccessListener(this, new OnSuccessListener<Location>() {
                @Override
                public void onSuccess(Location location) {
                    if (location != null) {
                        String message = "SOS! I need help. My location: https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
                        sendSMS(phoneNumber, message);
                        notifyAdmin(phoneNumber, location);
                    } else {
                        Toast.makeText(MainActivity.this, "Unable to get location!", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    private void notifyAdmin(String phoneNumber, Location location) {
        // Create the JSON payload
        String jsonPayload = String.format("{\"phoneNumber\":\"%s\",\"location\":\"https://maps.google.com/?q=%s,%s\"}",
                phoneNumber, location.getLatitude(), location.getLongitude());

        // Create a new thread for the network operation
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://668c8234-0963-45bc-92e5-fe4fa64654b6-00-ksyivpceux9c.sisko.replit.dev/sos"); // Ensure endpoint is correct
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                // Write the JSON payload
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                // Check the response code
                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d("SOSApp", "Admin notified successfully.");
                } else {
                    Log.e("SOSApp", "Failed to notify admin. Response Code: " + responseCode);
                    // Read the error stream for more details
                    InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        Log.e("SOSApp", "Error Response: " + response.toString());
                    }
                }
            } catch (Exception e) {
                Log.e("SOSApp", "Error notifying admin", e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }



    private void sendSMS(String phoneNumber, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            Toast.makeText(getApplicationContext(), "SOS message sent to: " + phoneNumber, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "SMS failed, please try again!", Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }
}
