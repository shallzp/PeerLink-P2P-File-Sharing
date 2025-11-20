package com.example.peerlink.Activity;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.peerlink.Adapter.DevicesAdapter;
import com.example.peerlink.R;
import com.example.peerlink.Utils.WiFiDirectBroadcastReceiver;

import java.util.ArrayList;
import java.util.List;

public class AvailableDevicesActivity extends AppCompatActivity implements
        WifiP2pManager.PeerListListener,
        WifiP2pManager.ConnectionInfoListener {

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;

    private RecyclerView rvDevices;
    private DevicesAdapter devicesAdapter;
    private List<WifiP2pDevice> peers = new ArrayList<>();

    // Store connected device info
    private String connectedDeviceAddress = null;
    private String connectedDeviceName = null;
    private String groupOwnerAddress = null;
    private boolean isGroupOwner = false;

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

        // Set connect listener
        devicesAdapter.setOnDeviceConnectListener(device -> connectToDevice(device));

        // Set disconnect listener
        devicesAdapter.setOnDeviceDisconnectListener(device -> disconnectFromDevice(device));

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
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
        boolean hasPermission;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        } else {
            hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        if (hasPermission) {
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
            Toast.makeText(this, "Permission to access nearby Wi-Fi devices is required.", Toast.LENGTH_LONG).show();
        }
    }

    private void connectToDevice(WifiP2pDevice device) {
        WifiP2pManager.ActionListener actionListener = new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Toast.makeText(AvailableDevicesActivity.this,
                        "Connection request sent to " + device.deviceName, Toast.LENGTH_SHORT).show();

                // Store device info
                connectedDeviceAddress = device.deviceAddress;
                connectedDeviceName = device.deviceName;
            }

            @Override
            public void onFailure(int reason) {
                connectedDeviceAddress = null;
                connectedDeviceName = null;

                String reasonStr;
                switch (reason) {
                    case WifiP2pManager.P2P_UNSUPPORTED:
                        reasonStr = "Wi-Fi Direct not supported";
                        break;
                    case WifiP2pManager.BUSY:
                        reasonStr = "System busy or already connecting";
                        break;
                    case WifiP2pManager.ERROR:
                    default:
                        reasonStr = "Internal error, try again";
                        break;
                }
                Toast.makeText(AvailableDevicesActivity.this,
                        "Connection failed: " + reasonStr, Toast.LENGTH_LONG).show();
            }
        };

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.groupOwnerIntent = 15; // High intent to be group owner

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
        }
    }

    private void disconnectFromDevice(WifiP2pDevice device) {
        new AlertDialog.Builder(this)
                .setTitle("Disconnect")
                .setMessage("Are you sure you want to disconnect from " + device.deviceName + "?")
                .setPositiveButton("Disconnect", (dialog, which) -> {
                    performDisconnect();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performDisconnect() {
        if (manager != null && channel != null) {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Toast.makeText(AvailableDevicesActivity.this,
                            "Disconnected successfully", Toast.LENGTH_SHORT).show();

                    // Clear connection data
                    clearConnectionData();

                    // Restart discovery
                    startPermissionAndDiscovery();
                }

                @Override
                public void onFailure(int reason) {
                    String message;
                    switch (reason) {
                        case WifiP2pManager.BUSY:
                            message = "System busy, try again";
                            break;
                        case WifiP2pManager.ERROR:
                        default:
                            message = "Disconnect failed";
                            break;
                    }
                    Toast.makeText(AvailableDevicesActivity.this,
                            message, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void clearConnectionData() {
        connectedDeviceAddress = null;
        connectedDeviceName = null;
        groupOwnerAddress = null;
        isGroupOwner = false;

        // Clear SharedPreferences
        SharedPreferences prefs = getSharedPreferences("PeerLinkPrefs", MODE_PRIVATE);
        prefs.edit().clear().apply();

        // Reset device statuses
        for (WifiP2pDevice device : peers) {
            if (device.status == WifiP2pDevice.CONNECTED || device.status == WifiP2pDevice.INVITED) {
                device.status = WifiP2pDevice.AVAILABLE;
            }
        }

        devicesAdapter.notifyDataSetChanged();
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

    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if (info.groupFormed) {
            Toast.makeText(this, "Connection established!", Toast.LENGTH_SHORT).show();

            // Store connection info
            isGroupOwner = info.isGroupOwner;
            groupOwnerAddress = info.groupOwnerAddress.getHostAddress();

            // Save to SharedPreferences
            saveConnectionInfo();

            // CRITICAL: Auto-start receiver on Group Owner
            if (isGroupOwner) {
                // This device is the Group Owner (Server)
                Toast.makeText(this, "You are the host. Starting receiver...",
                        Toast.LENGTH_LONG).show();

                // Auto-launch ReceiveFileActivity
                Intent receiveIntent = new Intent(this, ReceiveFileActivity.class);
                receiveIntent.putExtra("DEVICE_IP", groupOwnerAddress);
                receiveIntent.putExtra("DEVICE_NAME", connectedDeviceName != null ?
                        connectedDeviceName : "Connected Device");
                receiveIntent.putExtra("IS_GROUP_OWNER", true);
                receiveIntent.putExtra("AUTO_START", true);
                startActivity(receiveIntent);

            } else {
                // This device is the Client
                Toast.makeText(this, "Connected! You can send files now.",
                        Toast.LENGTH_LONG).show();
            }

            // Show mode selection dialog for manual control
            showModeSelectionDialog();
        } else {
            clearConnectionData();
        }

        devicesAdapter.notifyDataSetChanged();
    }

    private void showModeSelectionDialog() {
        String message = isGroupOwner ?
                "You are the Group Owner (Host).\nReceiver is already active.\n\nYou can also send files if needed." :
                "You are connected to the host.\nYou can send files now.";

        new AlertDialog.Builder(this)
                .setTitle("Connection Established")
                .setMessage(message)
                .setPositiveButton("Send File", (dialog, which) -> {
                    openSendFileActivity();
                })
                .setNegativeButton(isGroupOwner ? "Receiver Active" : "Open Receiver", (dialog, which) -> {
                    if (!isGroupOwner) {
                        openReceiveFileActivity();
                    }
                })
                .setNeutralButton("Close", null)
                .setCancelable(true)
                .show();
    }

    private void openSendFileActivity() {
        Intent intent = new Intent(this, SendFileActivity.class);
        intent.putExtra("DEVICE_IP", groupOwnerAddress);
        intent.putExtra("DEVICE_NAME", connectedDeviceName != null ? connectedDeviceName : "Connected Device");
        intent.putExtra("IS_GROUP_OWNER", isGroupOwner);
        startActivity(intent);
    }

    private void openReceiveFileActivity() {
        Intent intent = new Intent(this, ReceiveFileActivity.class);
        intent.putExtra("DEVICE_IP", groupOwnerAddress);
        intent.putExtra("DEVICE_NAME", connectedDeviceName != null ? connectedDeviceName : "Connected Device");
        intent.putExtra("IS_GROUP_OWNER", isGroupOwner);
        startActivity(intent);
    }

    private void saveConnectionInfo() {
        SharedPreferences prefs = getSharedPreferences("PeerLinkPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // IMPORTANT: Both devices should use Group Owner's IP
        editor.putString("DEVICE_IP", groupOwnerAddress);
        editor.putString("DEVICE_NAME", connectedDeviceName != null ?
                connectedDeviceName : "Connected Device");
        editor.putBoolean("IS_GROUP_OWNER", isGroupOwner);
        editor.putBoolean("IS_CONNECTED", true);

        editor.apply();

        // Log for debugging
        android.util.Log.d("WiFiDirect", "Saved - IP: " + groupOwnerAddress +
                ", IsGroupOwner: " + isGroupOwner);
    }

    public void onDisconnected() {
        Toast.makeText(this, "Disconnected from device", Toast.LENGTH_SHORT).show();
        clearConnectionData();
        startPermissionAndDiscovery();
    }
}