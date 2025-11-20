package com.example.peerlink.Activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.example.peerlink.R;

public class MainActivity extends AppCompatActivity {

    private CardView cardDiscoverDevices, cardSendFile, cardReceiveFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cardDiscoverDevices = findViewById(R.id.cardDiscoverDevices);
        cardSendFile = findViewById(R.id.cardSendFile);
        cardReceiveFile = findViewById(R.id.cardReceiveFile);

        cardDiscoverDevices.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AvailableDevicesActivity.class);
            startActivity(intent);
        });

        cardSendFile.setOnClickListener(v -> {
            if (isDeviceConnected()) {
                openSendFileActivity();
            } else {
                Toast.makeText(this, "Please connect to a device first", Toast.LENGTH_LONG).show();
                // Redirect to discover devices
                Intent intent = new Intent(MainActivity.this, AvailableDevicesActivity.class);
                startActivity(intent);
            }
        });

        cardReceiveFile.setOnClickListener(v -> {
            if (isDeviceConnected()) {
                openReceiveFileActivity();
            } else {
                Toast.makeText(this, "Please connect to a device first", Toast.LENGTH_LONG).show();
                // Redirect to discover devices
                Intent intent = new Intent(MainActivity.this, AvailableDevicesActivity.class);
                startActivity(intent);
            }
        });
    }

    private boolean isDeviceConnected() {
        SharedPreferences prefs = getSharedPreferences("PeerLinkPrefs", MODE_PRIVATE);
        return prefs.getBoolean("IS_CONNECTED", false);
    }

    private void openSendFileActivity() {
        SharedPreferences prefs = getSharedPreferences("PeerLinkPrefs", MODE_PRIVATE);

        String deviceIp = prefs.getString("DEVICE_IP", null);
        String deviceName = prefs.getString("DEVICE_NAME", "Unknown Device");
        boolean isGroupOwner = prefs.getBoolean("IS_GROUP_OWNER", false);

        if (deviceIp != null) {
            Intent intent = new Intent(this, SendFileActivity.class);
            intent.putExtra("DEVICE_IP", deviceIp);
            intent.putExtra("DEVICE_NAME", deviceName);
            intent.putExtra("IS_GROUP_OWNER", isGroupOwner);
            startActivity(intent);
        } else {
            Toast.makeText(this, "Connection info not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void openReceiveFileActivity() {
        SharedPreferences prefs = getSharedPreferences("PeerLinkPrefs", MODE_PRIVATE);

        String deviceIp = prefs.getString("DEVICE_IP", null);
        String deviceName = prefs.getString("DEVICE_NAME", "Unknown Device");
        boolean isGroupOwner = prefs.getBoolean("IS_GROUP_OWNER", false);

        if (deviceIp != null) {
            Intent intent = new Intent(this, ReceiveFileActivity.class);
            intent.putExtra("DEVICE_IP", deviceIp);
            intent.putExtra("DEVICE_NAME", deviceName);
            intent.putExtra("IS_GROUP_OWNER", isGroupOwner);
            startActivity(intent);
        } else {
            Toast.makeText(this, "Connection info not available", Toast.LENGTH_SHORT).show();
        }
    }
}
