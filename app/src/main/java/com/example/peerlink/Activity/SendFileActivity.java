package com.example.peerlink.Activity;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import com.example.peerlink.R;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SendFileActivity extends AppCompatActivity {

    private static final int SERVER_PORT = 8988;
    private static final int BASE_CONNECTION_TIMEOUT = 10000; // 10 seconds base timeout
    private static final int SOCKET_BUFFER_SIZE = 262144; // ADD THIS: 256 KB socket buffer


    // UI Elements
    private CardView btnSelectFile, btnStartTransfer, filePreviewCard, statusBadge;
    private TextView tvConnectionStatus, tvFileNamePreview, tvFileSize, tvFileType;
    private TextView tvFileStatus, tvReceiverName, tvReceiverStatus;
    private ImageView ivFilePreview, ivClearFile;
    private View statusIndicator;

    // File data
    private Uri selectedFileUri = null;
    private String fileName = "";
    private long fileSize = 0;
    private String fileType = "";

    // Connection data
    private String connectedDeviceIp = null;
    private String connectedDeviceName = "Unknown Device";
    private boolean isReceiverReady = false;

    // Locks for keeping connection stable
    private WifiManager.WifiLock wifiLock = null;
    private PowerManager.WakeLock wakeLock = null;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // File picker launcher
    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        onFileSelected(uri);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.send_file);

        // Keep screen on during file transfer preparation
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initializeViews();
        loadConnectionInfo();
        setupListeners();
        acquireLocks();
    }

    private void initializeViews() {
        // Buttons
        btnSelectFile = findViewById(R.id.btnSelectFile);
        btnStartTransfer = findViewById(R.id.btnStartTransfer);

        // Cards
        filePreviewCard = findViewById(R.id.filePreviewCard);
        statusBadge = findViewById(R.id.statusBadge);

        // File preview elements
        tvFileNamePreview = findViewById(R.id.tvFileNamePreview);
        tvFileSize = findViewById(R.id.tvFileSize);
        tvFileType = findViewById(R.id.tvFileType);
        tvFileStatus = findViewById(R.id.tvFileStatus);
        ivFilePreview = findViewById(R.id.ivFilePreview);
        ivClearFile = findViewById(R.id.ivClearFile);

        // Receiver info elements
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvReceiverName = findViewById(R.id.tvReceiverName);
        tvReceiverStatus = findViewById(R.id.tvReceiverStatus);
        statusIndicator = findViewById(R.id.statusIndicator);

        // Initial state
        btnStartTransfer.setEnabled(false);
        btnStartTransfer.setAlpha(0.5f);
    }

    private void loadConnectionInfo() {
        // Get connection info from intent
        connectedDeviceIp = getIntent().getStringExtra("DEVICE_IP");
        connectedDeviceName = getIntent().getStringExtra("DEVICE_NAME");
        boolean isGroupOwner = getIntent().getBooleanExtra("IS_GROUP_OWNER", false);

        // Debug logging
        android.util.Log.d("SendFile", "Device IP: " + connectedDeviceIp +
                ", Device Name: " + connectedDeviceName +
                ", Is Group Owner: " + isGroupOwner);

        if (connectedDeviceIp == null || connectedDeviceIp.isEmpty()) {
            Toast.makeText(this, "No connected device found", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Update UI
        tvReceiverName.setText(connectedDeviceName);
        tvReceiverStatus.setText("Ready to receive");
        tvConnectionStatus.setText("Connected");
        statusBadge.setCardBackgroundColor(getResources().getColor(android.R.color.holo_green_dark));
    }

    private void setupListeners() {
        btnSelectFile.setOnClickListener(v -> openFilePicker());

        btnStartTransfer.setOnClickListener(v -> {
            if (selectedFileUri == null) {
                Toast.makeText(this, "Please select a file first", Toast.LENGTH_SHORT).show();
                return;
            }
            sendFileMetadata();
        });

        ivClearFile.setOnClickListener(v -> clearSelectedFile());
    }

    /**
     * Acquire WiFi and Wake locks to ensure stable transfer
     */
    private void acquireLocks() {
        try {
            // WiFi Lock - Prevent WiFi from sleeping
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PeerLink:SendFileLock");
                wifiLock.acquire();
                android.util.Log.d("SendFile", "WiFi lock acquired");
            }

            // Wake Lock - Keep CPU running (partial wake lock - doesn't keep screen on)
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PeerLink:SendFileWakeLock");
                wakeLock.acquire();
                android.util.Log.d("SendFile", "Wake lock acquired");
            }
        } catch (Exception e) {
            android.util.Log.e("SendFile", "Error acquiring locks: " + e.getMessage());
        }
    }

    /**
     * Calculate dynamic timeout based on file size
     * Assumes minimum speed of 512 KB/s with 3x safety buffer
     */
    private int calculateDynamicTimeout(long fileSize) {
        // For metadata exchange, use base timeout
        if (fileSize == 0) {
            return BASE_CONNECTION_TIMEOUT;
        }

        // Calculate estimated time (assuming 512 KB/s minimum speed)
        long estimatedSeconds = (fileSize / (512 * 1024)) * 3; // 3x buffer
        int timeoutMs = (int) Math.min(estimatedSeconds * 1000, 600000); // Max 10 minutes
        int finalTimeout = Math.max(BASE_CONNECTION_TIMEOUT, timeoutMs);

        android.util.Log.d("SendFile", "Calculated timeout: " + (finalTimeout / 1000) + " seconds for " +
                formatFileSize(fileSize));

        return finalTimeout;
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(intent);
    }

    private void onFileSelected(Uri uri) {
        selectedFileUri = uri;

        // Get file details
        fileName = getFileName(uri);
        fileSize = getFileSize(uri);
        fileType = getFileType(uri);

        android.util.Log.d("SendFile", "File selected: " + fileName +
                ", Size: " + fileSize + " bytes (" + formatFileSize(fileSize) + ")" +
                ", Type: " + fileType);

        // Show warning for very large files
        if (fileSize > 5L * 1024 * 1024 * 1024) { // > 5 GB
            Toast.makeText(this,
                    "Warning: Large file (" + formatFileSize(fileSize) + "). " +
                            "Keep devices close and don't interrupt the connection.",
                    Toast.LENGTH_LONG).show();
        }

        // Update UI
        showFilePreview();
        enableStartTransfer();
    }

    private void showFilePreview() {
        // Show file preview card
        filePreviewCard.setVisibility(View.VISIBLE);
        ivClearFile.setVisibility(View.VISIBLE);

        // Set file details
        tvFileNamePreview.setText(fileName);
        tvFileSize.setText(formatFileSize(fileSize));
        tvFileType.setText(fileType.toUpperCase());
        tvFileStatus.setText("Ready");
        tvFileStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));

        // Set file icon based on type
        setFileIcon(fileType);
    }

    private void clearSelectedFile() {
        selectedFileUri = null;
        fileName = "";
        fileSize = 0;
        fileType = "";

        filePreviewCard.setVisibility(View.GONE);
        btnStartTransfer.setEnabled(false);
        btnStartTransfer.setAlpha(0.5f);
        tvReceiverStatus.setText("Ready to receive");
        statusIndicator.setBackgroundTintList(
                getResources().getColorStateList(android.R.color.holo_orange_light));
    }

    private void enableStartTransfer() {
        btnStartTransfer.setEnabled(true);
        btnStartTransfer.setAlpha(1.0f);
    }

    /**
     * Step 1: Send file metadata to receiver
     */
    private void sendFileMetadata() {
        updateReceiverStatus("Connecting to receiver...");
        btnStartTransfer.setEnabled(false);

        executor.execute(() -> {
            Socket socket = null;
            DataOutputStream dos = null;
            DataInputStream dis = null;
            int maxRetries = 3;
            int retryCount = 0;

            try {
                // Calculate dynamic timeout for metadata connection
                int metadataTimeout = calculateDynamicTimeout(0); // Use base timeout for metadata

                // Connection retry loop
                while (retryCount < maxRetries) {
                    try {
                        // Add small delay before first connection attempt
                        if (retryCount == 0) {
                            Thread.sleep(1000);
                        }

                        final int currentAttempt = retryCount + 1;
                        mainHandler.post(() ->
                                updateReceiverStatus("Connecting... (Attempt " + currentAttempt + "/" + maxRetries + ")")
                        );

                        android.util.Log.d("SendFile", "Connection attempt " + currentAttempt +
                                " to " + connectedDeviceIp + ":" + SERVER_PORT);

                        socket = new Socket();

                        // OPTIMIZATION: Configure socket for reliable transfer
                        socket.setTcpNoDelay(true);        // Disable Nagle's algorithm for low latency
                        socket.setKeepAlive(true);          // Enable TCP keep-alive
                        socket.setSoTimeout(metadataTimeout); // Set read timeout
                        socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE); // 256KB receive buffer
                        socket.setSendBufferSize(SOCKET_BUFFER_SIZE);    // 256KB send buffer

                        socket.connect(new InetSocketAddress(connectedDeviceIp, SERVER_PORT), metadataTimeout);

                        android.util.Log.d("SendFile", "Connected successfully with optimized socket settings");

                        // Connection successful, break the retry loop
                        break;

                    } catch (SocketException e) {
                        retryCount++;
                        android.util.Log.e("SendFile", "Socket exception on attempt " + retryCount + ": " + e.getMessage());

                        if (retryCount >= maxRetries) {
                            final String errorMsg = e.getMessage();
                            mainHandler.post(() -> {
                                updateReceiverStatus("Connection failed");
                                Toast.makeText(this,
                                        "Cannot connect to receiver. Make sure receiver is ready.\nError: " + errorMsg,
                                        Toast.LENGTH_LONG).show();
                                btnStartTransfer.setEnabled(true);
                                statusIndicator.setBackgroundTintList(
                                        getResources().getColorStateList(android.R.color.holo_red_dark));
                            });
                            return;
                        }

                        // Wait before retry
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            ie.printStackTrace();
                        }

                    } catch (Exception e) {
                        retryCount++;
                        android.util.Log.e("SendFile", "Connection attempt " + retryCount + " failed: " + e.getMessage());

                        if (retryCount >= maxRetries) {
                            final String errorMsg = e.getMessage();
                            mainHandler.post(() -> {
                                updateReceiverStatus("Connection failed");
                                Toast.makeText(this,
                                        "Cannot connect to receiver.\nError: " + errorMsg,
                                        Toast.LENGTH_LONG).show();
                                btnStartTransfer.setEnabled(true);
                                statusIndicator.setBackgroundTintList(
                                        getResources().getColorStateList(android.R.color.holo_red_dark));
                            });
                            return;
                        }

                        // Wait before retry
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            ie.printStackTrace();
                        }
                    }
                }

                // If socket is null, connection failed
                if (socket == null || socket.isClosed()) {
                    mainHandler.post(() -> {
                        updateReceiverStatus("Connection failed");
                        Toast.makeText(this, "Failed to connect to receiver", Toast.LENGTH_LONG).show();
                        btnStartTransfer.setEnabled(true);
                    });
                    return;
                }

                // Connection successful, proceed with metadata exchange
                dos = new DataOutputStream(socket.getOutputStream());
                dis = new DataInputStream(socket.getInputStream());

                mainHandler.post(() -> updateReceiverStatus("Sending file info..."));

                // Send metadata packet
                dos.writeUTF("FILE_METADATA");
                dos.writeUTF(fileName);
                dos.writeLong(fileSize);
                dos.writeUTF(fileType);
                dos.writeUTF(android.os.Build.MODEL); // Sender device name
                dos.flush();

                android.util.Log.d("SendFile", "Sent metadata: " + fileName + ", " +
                        fileSize + " bytes (" + formatFileSize(fileSize) + ")");

                mainHandler.post(() -> {
                    updateReceiverStatus("Waiting for response...");
                    statusIndicator.setBackgroundTintList(
                            getResources().getColorStateList(android.R.color.holo_orange_light));
                });

                // Wait for receiver's response (ACCEPT or REJECT)
                String response = dis.readUTF();
                android.util.Log.d("SendFile", "Received response: " + response);

                if ("ACCEPT".equals(response)) {
                    mainHandler.post(() -> {
                        isReceiverReady = true;
                        updateReceiverStatus("Accepted! Starting transfer...");
                        statusIndicator.setBackgroundTintList(
                                getResources().getColorStateList(android.R.color.holo_green_dark));

                        // Start actual file transfer in new activity
                        startFileTransfer();
                    });
                } else {
                    mainHandler.post(() -> {
                        updateReceiverStatus("Transfer rejected");
                        statusIndicator.setBackgroundTintList(
                                getResources().getColorStateList(android.R.color.holo_red_dark));
                        Toast.makeText(this, "Receiver rejected the file", Toast.LENGTH_LONG).show();
                        btnStartTransfer.setEnabled(true);
                    });
                }

            } catch (Exception e) {
                e.printStackTrace();
                android.util.Log.e("SendFile", "Metadata exchange error: " + e.getMessage());
                final String errorMsg = e.getMessage();
                mainHandler.post(() -> {
                    updateReceiverStatus("Error during transfer");
                    Toast.makeText(this, "Error: " + errorMsg, Toast.LENGTH_LONG).show();
                    btnStartTransfer.setEnabled(true);
                    statusIndicator.setBackgroundTintList(
                            getResources().getColorStateList(android.R.color.holo_red_dark));
                });
            } finally {
                // Close all resources in finally block
                try {
                    if (dos != null) dos.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    if (dis != null) dis.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    if (socket != null && !socket.isClosed()) socket.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Step 2: Start actual file transfer and navigate to progress screen
     */
    private void startFileTransfer() {
        android.util.Log.d("SendFile", "Starting TransferProgressActivity");

        // Navigate to TransferProgressActivity with file details
        Intent intent = new Intent(this, TransferProgressActivity.class);
        intent.putExtra("FILE_URI", selectedFileUri.toString());
        intent.putExtra("FILE_NAME", fileName);
        intent.putExtra("FILE_SIZE", fileSize);
        intent.putExtra("FILE_TYPE", fileType);
        intent.putExtra("DEVICE_IP", connectedDeviceIp);
        intent.putExtra("DEVICE_NAME", connectedDeviceName);
        intent.putExtra("IS_SENDER", true);
        startActivity(intent);

        // Don't finish this activity so user can come back
    }

    private void updateReceiverStatus(String status) {
        tvReceiverStatus.setText(status);
    }

    // ==================== Utility Methods ====================

    private String getFileName(Uri uri) {
        String displayName = "unknown";
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx != -1) displayName = cursor.getString(idx);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return displayName;
    }

    private long getFileSize(Uri uri) {
        long size = 0;
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx != -1) size = cursor.getLong(idx);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return size;
    }

    private String getFileType(Uri uri) {
        String type = "UNKNOWN";
        String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
        if (extension != null && !extension.isEmpty()) {
            type = extension;
        } else {
            String mimeType = getContentResolver().getType(uri);
            if (mimeType != null) {
                type = mimeType.split("/")[1];
            }
        }
        return type;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        else if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        else if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        else return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void setFileIcon(String type) {
        int iconRes = R.drawable.ic_file_large;
        if (type != null) {
            type = type.toLowerCase();
            if (type.contains("pdf")) {
                iconRes = R.drawable.ic_file_large;
            } else if (type.contains("jpg") || type.contains("jpeg") ||
                    type.contains("png") || type.contains("image")) {
                iconRes = R.drawable.ic_file_large;
            } else if (type.contains("doc") || type.contains("txt")) {
                iconRes = R.drawable.ic_file_large;
            } else if (type.contains("mp4") || type.contains("avi") ||
                    type.contains("video")) {
                iconRes = R.drawable.ic_file_large;
            } else if (type.contains("mp3") || type.contains("wav") ||
                    type.contains("audio")) {
                iconRes = R.drawable.ic_file_large;
            }
        }
        ivFilePreview.setImageResource(iconRes);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Release locks
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                android.util.Log.d("SendFile", "WiFi lock released");
            }
        } catch (Exception e) {
            android.util.Log.e("SendFile", "Error releasing WiFi lock: " + e.getMessage());
        }

        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                android.util.Log.d("SendFile", "Wake lock released");
            }
        } catch (Exception e) {
            android.util.Log.e("SendFile", "Error releasing wake lock: " + e.getMessage());
        }

        executor.shutdown();
    }
}