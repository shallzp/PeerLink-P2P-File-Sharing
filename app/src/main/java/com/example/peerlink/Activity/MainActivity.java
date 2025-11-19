package com.example.peerlink.Activity;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.example.peerlink.R;

public class MainActivity extends AppCompatActivity {

    private CardView cardDiscoverDevices, cardSendFile, cardReceiveFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Find CardViews by ID
        cardDiscoverDevices = findViewById(R.id.cardDiscoverDevices);
        cardSendFile = findViewById(R.id.cardSendFile);
        cardReceiveFile = findViewById(R.id.cardReceiveFile);

        // Set click listeners
        cardDiscoverDevices.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AvailableDevicesActivity.class);
            startActivity(intent);
        });

        cardSendFile.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SendFileActivity.class);
            startActivity(intent);
        });

        cardReceiveFile.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ReceiveFileActivity.class);
            startActivity(intent);
        });
    }
}
