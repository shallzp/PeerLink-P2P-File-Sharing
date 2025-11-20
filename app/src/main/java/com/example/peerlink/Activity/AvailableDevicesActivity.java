package com.example.peerlink.Activity;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.peerlink.Adapter.DevicesAdapter;
import com.example.peerlink.R;
import com.example.peerlink.WiFiDirectBroadcastReceiver;

import java.util.ArrayList;
import java.util.List;

public class AvailableDevicesActivity extends AppCompatActivity implements
        WifiP2pManager.PeerListListener {

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;

    private RecyclerView rvDevices;
    private DevicesAdapter devicesAdapter;
    private List<WifiP2pDevice> peers = new ArrayList<>();

    private LinearLayout emptyState;
    private CardView btnRefresh;

    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    allGranted &= granted;
                }
                if (allGranted) {
                    discoverPeers();
                } else {
                    Toast.makeText(this, "Permissions are required for discovering devices", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.available_devices);

        rvDevices = findViewById(R.id.rvDevices);
        emptyState = findViewById(R.id.emptyState);
        btnRefresh = findViewById(R.id.btnRefresh);

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager != null) {
            channel = manager.initialize(this, getMainLooper(), null);
        } else {
            Toast.makeText(this, "Wi-Fi P2P not supported on this device", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        devicesAdapter = new DevicesAdapter(peers);

        devicesAdapter.setOnDeviceConnectListener(device -> {
            connectToDevice(device);
        });

        rvDevices.setLayoutManager(new LinearLayoutManager(this));
        rvDevices.setAdapter(devicesAdapter);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);

        btnRefresh.setOnClickListener(v -> startPermissionAndDiscovery());

        // Start discovery on activity start
        startPermissionAndDiscovery();
    }

    private void startPermissionAndDiscovery() {
        List<String> permissionsNeeded = new ArrayList<>();

        // Request location permissions as before (needed for all versions)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        // Request NEARBY_WIFI_DEVICES for Android 13+ (API 33)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            requestPermissionsLauncher.launch(permissionsNeeded.toArray(new String[0]));
        } else {
            discoverPeers();
        }
    }


    private void discoverPeers() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                == PackageManager.PERMISSION_GRANTED) {
            if (manager != null && channel != null) {
                manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Toast.makeText(AvailableDevicesActivity.this, "Discovery Started", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onFailure(int reason) {
                        String message;
                        switch (reason) {
                            case WifiP2pManager.P2P_UNSUPPORTED:
                                message = "Wi-Fi Direct is not supported on this device.";
                                break;
                            case WifiP2pManager.BUSY:
                                message = "Wi-Fi system busy. Please wait and try again.";
                                // Optionally add a retry after delay as above
                                break;
                            case WifiP2pManager.ERROR:
                            default:
                                message = "Discovery failed due to an internal error. Please try again.";
                                break;
                        }
                        Toast.makeText(AvailableDevicesActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        } else {
            // Permission not granted; request permission or inform user
            Toast.makeText(this, "Permission to access nearby Wi-Fi devices is required.", Toast.LENGTH_LONG).show();
            // Optionally trigger your permission request here if not already requested
        }
    }

    private void connectToDevice(WifiP2pDevice device) {
        WifiP2pManager.ActionListener actionListener = new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Toast.makeText(AvailableDevicesActivity.this,
                        "Connection request sent to " + device.deviceName, Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onFailure(int reason) {
                String reasonStr;
                switch (reason) {
                    case WifiP2pManager.P2P_UNSUPPORTED: reasonStr = "Wi-Fi Direct not supported"; break;
                    case WifiP2pManager.BUSY: reasonStr = "System busy or already connecting"; break;
                    case WifiP2pManager.ERROR:
                    default: reasonStr = "Internal error, try again";
                }
                Toast.makeText(AvailableDevicesActivity.this,
                        "Connection failed: " + reasonStr, Toast.LENGTH_LONG).show();
            }
        };

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        // Optionally: config.wps.setup = WpsInfo.PBC (if available)

        // Check permissions before connecting
        boolean hasPermission;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        } else {
            hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        if (hasPermission) {
            manager.connect(channel, config, actionListener);
        } else {
            Toast.makeText(this, "Wi-Fi Direct permission required to connect.", Toast.LENGTH_SHORT).show();
            // Optionally request permissions here
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(receiver, intentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }

    @Override
    public void onPeersAvailable(WifiP2pDeviceList peerList) {
        peers.clear();
        peers.addAll(peerList.getDeviceList());
        devicesAdapter.notifyDataSetChanged();

        emptyState.setVisibility(peers.size() == 0 ? View.VISIBLE : View.GONE);
        rvDevices.setVisibility(peers.size() == 0 ? View.GONE : View.VISIBLE);

        if (peers.size() == 0) {
            Toast.makeText(this, "No devices found", Toast.LENGTH_SHORT).show();
        }
    }
}