package com.example.peerlink.Activity;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import com.example.peerlink.R;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransferProgressActivity extends AppCompatActivity {

    private static final int SERVER_PORT = 8988;
    private static final int BUFFER_SIZE = 16384; // REDUCED: 16 KB buffer for stability
    private static final int SOCKET_BUFFER_SIZE = 262144; // 256 KB socket buffer
    private static final int BASE_CONNECTION_TIMEOUT = 10000; // 10 seconds base timeout

    // UI Elements - File Info Card
    private CardView fileInfoCard;
    private ImageView ivFilePreview;
    private TextView tvFileName, tvFileSize, tvFileType;

    // UI Elements - Device Info Card
    private CardView deviceInfoCard;
    private TextView tvDeviceName, tvDeviceRole;

    // UI Elements - Progress Section
    private CardView progressSection;
    private TextView tvProgressPercent, tvProgressDetails, tvTransferSpeed, tvTimeRemaining;
    private View progressBar;
    private View progressBarParent;
    private ImageView btnCancelTransfer;

    // UI Elements - Status Section
    private LinearLayout statusSection;
    private ProgressBar progressSpinner;
    private TextView tvStatusMessage;

    // UI Elements - Completion Section
    private LinearLayout completionSection;
    private ImageView ivCompletionIcon;
    private TextView tvCompletionMessage, tvCompletionDetails;
    private CardView btnDone, btnViewFile;

    // Transfer data
    private Uri fileUri;
    private String fileName;
    private long fileSize;
    private String fileType;
    private String deviceIp;
    private String deviceName;
    private boolean isSender;

    // Locks for keeping connection stable
    private WifiManager.WifiLock wifiLock = null;
    private PowerManager.WakeLock wakeLock = null;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isTransferring = new AtomicBoolean(false);

    private Socket socket;
    private DataOutputStream dos;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transfer_progress);

        // Keep screen on during file transfer
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initializeViews();
        loadTransferData();
        setupListeners();
        acquireLocks();

        // Start transfer based on mode
        if (isSender) {
            startSendingFile();
        } else {
            // Receiver mode is handled by ReceiveFileActivity
            Toast.makeText(this, "This activity is for sending only", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void initializeViews() {
        // File info card
        fileInfoCard = findViewById(R.id.fileInfoCard);
        ivFilePreview = findViewById(R.id.ivFilePreview);
        tvFileName = findViewById(R.id.tvFileName);
        tvFileSize = findViewById(R.id.tvFileSize);
        tvFileType = findViewById(R.id.tvFileType);

        // Device info card
        deviceInfoCard = findViewById(R.id.deviceInfoCard);
        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvDeviceRole = findViewById(R.id.tvDeviceRole);

        // Progress section
        progressSection = findViewById(R.id.progressSection);
        tvProgressPercent = findViewById(R.id.tvProgressPercent);
        tvProgressDetails = findViewById(R.id.tvProgressDetails);
        tvTransferSpeed = findViewById(R.id.tvTransferSpeed);
        tvTimeRemaining = findViewById(R.id.tvTimeRemaining);
        progressBar = findViewById(R.id.progressBar);
        progressBarParent = findViewById(R.id.progressBarContainer);
        btnCancelTransfer = findViewById(R.id.btnCancelTransfer);

        // Status section
        statusSection = findViewById(R.id.statusSection);
        progressSpinner = findViewById(R.id.progressSpinner);
        tvStatusMessage = findViewById(R.id.tvStatusMessage);

        // Completion section
        completionSection = findViewById(R.id.completionSection);
        ivCompletionIcon = findViewById(R.id.ivCompletionIcon);
        tvCompletionMessage = findViewById(R.id.tvCompletionMessage);
        tvCompletionDetails = findViewById(R.id.tvCompletionDetails);
        btnDone = findViewById(R.id.btnDone);
        btnViewFile = findViewById(R.id.btnViewFile);

        // Initial state
        showConnectingState();
    }

    /**
     * Acquire WiFi and Wake locks to ensure stable transfer
     */
    private void acquireLocks() {
        try {
            // WiFi Lock - Prevent WiFi from sleeping
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PeerLink:TransferLock");
                wifiLock.acquire();
                android.util.Log.d("TransferProgress", "WiFi lock acquired");
            }

            // Wake Lock - Keep CPU running
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PeerLink:TransferWakeLock");
                wakeLock.acquire();
                android.util.Log.d("TransferProgress", "Wake lock acquired");
            }
        } catch (Exception e) {
            android.util.Log.e("TransferProgress", "Error acquiring locks: " + e.getMessage());
        }
    }

    /**
     * Calculate dynamic timeout based on file size
     * Assumes minimum speed of 512 KB/s with 3x safety buffer
     */
    private int calculateDynamicTimeout(long fileSize) {
        if (fileSize == 0) {
            return BASE_CONNECTION_TIMEOUT;
        }

        // Calculate estimated time (assuming 512 KB/s minimum speed)
        long estimatedSeconds = (fileSize / (512 * 1024)) * 3; // 3x buffer
        int timeoutMs = (int) Math.min(estimatedSeconds * 1000, 600000); // Max 10 minutes
        int finalTimeout = Math.max(BASE_CONNECTION_TIMEOUT, timeoutMs);

        android.util.Log.d("TransferProgress", "Calculated timeout: " + (finalTimeout / 1000) +
                " seconds for " + formatFileSize(fileSize));

        return finalTimeout;
    }

    private void loadTransferData() {
        // Get data from intent
        String fileUriString = getIntent().getStringExtra("FILE_URI");
        fileName = getIntent().getStringExtra("FILE_NAME");
        fileSize = getIntent().getLongExtra("FILE_SIZE", 0);
        fileType = getIntent().getStringExtra("FILE_TYPE");
        deviceIp = getIntent().getStringExtra("DEVICE_IP");
        deviceName = getIntent().getStringExtra("DEVICE_NAME");
        isSender = getIntent().getBooleanExtra("IS_SENDER", true);

        if (fileUriString != null) {
            fileUri = Uri.parse(fileUriString);
        }

        android.util.Log.d("TransferProgress", "Mode: " + (isSender ? "SENDER" : "RECEIVER") +
                ", File: " + fileName + ", Size: " + fileSize + " bytes (" + formatFileSize(fileSize) + ")" +
                ", Device IP: " + deviceIp + ", Device Name: " + deviceName);

        // Update UI with file info
        updateFileInfo();
        updateDeviceInfo();
    }

    private void updateFileInfo() {
        tvFileName.setText(fileName);
        tvFileSize.setText(formatFileSize(fileSize));
        tvFileType.setText(fileType != null ? fileType.toUpperCase() : "FILE");

        // Set file icon
        setFileIcon(fileType);
    }

    private void updateDeviceInfo() {
        tvDeviceName.setText(deviceName != null ? deviceName : "Unknown Device");
        tvDeviceRole.setText(isSender ? "Receiver" : "Sender");
    }

    private void setupListeners() {
        btnCancelTransfer.setOnClickListener(v -> cancelTransfer());
        btnDone.setOnClickListener(v -> finish());
        btnViewFile.setOnClickListener(v -> {
            // For sender, we don't have a local file to view
            // For receiver mode, this would open the received file
            Toast.makeText(this, "File viewing not implemented for sender", Toast.LENGTH_SHORT).show();
        });
    }

    private void showConnectingState() {
        statusSection.setVisibility(View.VISIBLE);
        progressSection.setVisibility(View.GONE);
        completionSection.setVisibility(View.GONE);
        tvStatusMessage.setText("Connecting to receiver...");
    }

    private void showTransferringState() {
        statusSection.setVisibility(View.GONE);
        progressSection.setVisibility(View.VISIBLE);
        completionSection.setVisibility(View.GONE);
    }

    private void showCompletionState(boolean success, String message, String details) {
        statusSection.setVisibility(View.GONE);
        progressSection.setVisibility(View.GONE);
        completionSection.setVisibility(View.VISIBLE);

        if (success) {
            ivCompletionIcon.setImageResource(R.drawable.ic_check);
            ivCompletionIcon.setColorFilter(getResources().getColor(android.R.color.holo_green_dark));
            tvCompletionMessage.setText(message);
            tvCompletionMessage.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            btnViewFile.setVisibility(isSender ? View.GONE : View.VISIBLE);
        } else {
            ivCompletionIcon.setImageResource(R.drawable.ic_error);
            ivCompletionIcon.setColorFilter(getResources().getColor(android.R.color.holo_red_dark));
            tvCompletionMessage.setText(message);
            tvCompletionMessage.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            btnViewFile.setVisibility(View.GONE);
        }

        tvCompletionDetails.setText(details);
    }

    private void startSendingFile() {
        if (fileUri == null || deviceIp == null || deviceIp.isEmpty()) {
            Toast.makeText(this, "Missing file or device information", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        isTransferring.set(true);

        executor.execute(() -> {
            InputStream inputStream = null;
            long totalSent = 0;

            try {
                // Calculate dynamic timeout based on file size
                int dynamicTimeout = calculateDynamicTimeout(fileSize);

                // Step 1: Connect to receiver
                mainHandler.post(() -> tvStatusMessage.setText("Connecting to receiver..."));
                android.util.Log.d("TransferProgress", "Connecting to " + deviceIp + ":" + SERVER_PORT);

                socket = new Socket();

                // CRITICAL FIX: Increase socket buffers and disable Nagle for small packets
                socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE); // 256 KB receive buffer
                socket.setSendBufferSize(SOCKET_BUFFER_SIZE);    // 256 KB send buffer
                socket.setTcpNoDelay(false);                     // Enable Nagle's algorithm for better batching
                socket.setKeepAlive(true);                        // Enable TCP keep-alive
                socket.setSoTimeout(dynamicTimeout);              // Dynamic timeout based on file size
                socket.setSoLinger(true, 0);                     // Immediate close without TIME_WAIT

                socket.connect(new InetSocketAddress(deviceIp, SERVER_PORT), BASE_CONNECTION_TIMEOUT);
                dos = new DataOutputStream(socket.getOutputStream());

                android.util.Log.d("TransferProgress", "Connected successfully. " +
                        "Buffer: " + BUFFER_SIZE + " bytes, " +
                        "Socket buffer: " + SOCKET_BUFFER_SIZE + " bytes, " +
                        "Timeout: " + (dynamicTimeout / 1000) + " seconds");

                // Step 2: Send FILE_DATA marker
                mainHandler.post(() -> tvStatusMessage.setText("Initiating transfer..."));
                try {
                    dos.writeUTF("FILE_DATA");
                    dos.flush();
                } catch (SocketException e) {
                    throw new Exception("Failed to send FILE_DATA marker: Connection lost");
                }

                android.util.Log.d("TransferProgress", "Sent FILE_DATA marker");

                // Longer delay to ensure receiver is ready
                Thread.sleep(1000); // Increased to 1 second

                // Step 3: Switch to transferring state
                mainHandler.post(this::showTransferringState);

                // Step 4: Open file and start sending
                inputStream = getContentResolver().openInputStream(fileUri);
                if (inputStream == null) {
                    throw new Exception("Cannot open file for reading");
                }

                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long startTime = System.currentTimeMillis();
                long lastUpdateTime = startTime;
                long lastSentBytes = 0;
                int packetCount = 0;

                android.util.Log.d("TransferProgress", "Starting file transfer with " + BUFFER_SIZE +
                        " byte buffer, size: " + fileSize + " bytes (" + formatFileSize(fileSize) + ")");

                // Send file data
                while (isTransferring.get() && (bytesRead = inputStream.read(buffer)) != -1) {

                    // Check if socket is still connected
                    if (socket == null || socket.isClosed() || !socket.isConnected()) {
                        throw new SocketException("Socket disconnected during transfer at " +
                                formatFileSize(totalSent));
                    }

                    try {
                        dos.write(buffer, 0, bytesRead);
                        totalSent += bytesRead;
                        packetCount++;

                        // CRITICAL FIX: Flush every 10 packets (160 KB) and add small delay
                        if (packetCount % 10 == 0) {
                            dos.flush();
                            Thread.sleep(5); // 5ms pause to let receiver catch up

                            android.util.Log.d("TransferProgress",
                                    "Checkpoint at " + formatFileSize(totalSent) +
                                            " (" + packetCount + " packets)");
                        }

                    } catch (SocketException e) {
                        android.util.Log.e("TransferProgress", "Socket error at " + totalSent +
                                " bytes (" + packetCount + " packets): " + e.getMessage());
                        throw new Exception("Connection interrupted at " + formatFileSize(totalSent) +
                                ": " + (e.getMessage() != null ? e.getMessage() : "Broken pipe"));
                    } catch (InterruptedException e) {
                        android.util.Log.e("TransferProgress", "Transfer interrupted by thread");
                        throw new Exception("Transfer interrupted");
                    }

                    long currentTime = System.currentTimeMillis();

                    // Update UI every 200ms
                    if (currentTime - lastUpdateTime >= 200) {
                        final long sent = totalSent;
                        final long elapsed = currentTime - startTime;
                        final long bytesSinceLastUpdate = sent - lastSentBytes;
                        final long timeSinceLastUpdate = currentTime - lastUpdateTime;

                        mainHandler.post(() -> updateProgress(sent, elapsed, bytesSinceLastUpdate, timeSinceLastUpdate));

                        lastUpdateTime = currentTime;
                        lastSentBytes = sent;
                    }
                }

                // Final flush
                dos.flush();

                // ADDED: Send completion marker
                try {
                    dos.writeUTF("TRANSFER_COMPLETE");
                    dos.flush();
                    android.util.Log.d("TransferProgress", "Sent completion marker");
                } catch (Exception e) {
                    android.util.Log.e("TransferProgress", "Could not send completion marker: " + e.getMessage());
                }

                final long totalTime = System.currentTimeMillis() - startTime;
                final long finalSent = totalSent;
                final double avgSpeedMBps = (totalSent / (1024.0 * 1024)) / (totalTime / 1000.0);

                android.util.Log.d("TransferProgress", "Transfer complete. Sent " + totalSent +
                        " bytes (" + packetCount + " packets) in " + totalTime + "ms. " +
                        "Average speed: " + String.format("%.2f MB/s", avgSpeedMBps));

                // Check if transfer was complete
                if (totalSent >= fileSize && isTransferring.get()) {
                    // Success
                    mainHandler.post(() -> {
                        updateProgress(fileSize, totalTime, 0, 0);
                        showCompletionState(true,
                                "Transfer Complete!",
                                fileName + " sent successfully to " + deviceName +
                                        "\nAverage speed: " + String.format("%.2f MB/s", avgSpeedMBps));
                    });
                } else if (!isTransferring.get()) {
                    // Cancelled
                    mainHandler.post(() ->
                            showCompletionState(false,
                                    "Transfer Cancelled",
                                    "Sent " + formatFileSize(finalSent) + " of " + formatFileSize(fileSize))
                    );
                } else {
                    // Incomplete
                    mainHandler.post(() ->
                            showCompletionState(false,
                                    "Transfer Incomplete",
                                    "Sent " + formatFileSize(finalSent) + " of " + formatFileSize(fileSize))
                    );
                }

            } catch (SocketException e) {
                e.printStackTrace();
                android.util.Log.e("TransferProgress", "Socket error: " + e.getMessage());
                final String errorMsg = e.getMessage();
                final long finalSent = totalSent;
                mainHandler.post(() ->
                        showCompletionState(false,
                                "Connection Lost",
                                "Error: " + (errorMsg != null ? errorMsg : "Broken pipe") +
                                        "\nSent: " + formatFileSize(finalSent))
                );
            } catch (Exception e) {
                e.printStackTrace();
                android.util.Log.e("TransferProgress", "Transfer error: " + e.getMessage());
                final String errorMsg = e.getMessage();
                final long finalSent = totalSent;
                mainHandler.post(() ->
                        showCompletionState(false,
                                "Transfer Failed",
                                "Error: " + (errorMsg != null ? errorMsg : "Unknown error") +
                                        "\nSent: " + formatFileSize(finalSent))
                );
            } finally {
                isTransferring.set(false);

                // Close resources
                try {
                    if (inputStream != null) inputStream.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    if (dos != null) {
                        dos.flush();
                        dos.close();
                    }
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

    private void updateProgress(long sent, long elapsedMs, long recentBytes, long recentTimeMs) {
        // Calculate percentage
        int percentage = fileSize > 0 ? (int) ((sent * 100) / fileSize) : 0;
        percentage = Math.min(percentage, 100);

        // Update progress bar width
        int parentWidth = progressBarParent.getWidth();
        if (parentWidth > 0 && sent > 0 && fileSize > 0) {
            int progressWidth = (int) ((sent * parentWidth) / fileSize);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) progressBar.getLayoutParams();
            params.width = progressWidth;
            progressBar.setLayoutParams(params);
        }

        // Update percentage text
        tvProgressPercent.setText(percentage + "%");

        // Update data transferred
        tvProgressDetails.setText(formatFileSize(sent) + " / " + formatFileSize(fileSize));

        // Calculate current speed (based on recent data)
        if (recentTimeMs > 0 && recentBytes > 0) {
            double currentSpeedBytesPerSec = (recentBytes * 1000.0) / recentTimeMs;
            tvTransferSpeed.setText(formatSpeed(currentSpeedBytesPerSec));

            // Calculate estimated time remaining
            long remainingBytes = fileSize - sent;
            if (currentSpeedBytesPerSec > 0) {
                long remainingSeconds = (long) (remainingBytes / currentSpeedBytesPerSec);
                tvTimeRemaining.setText(formatTime(remainingSeconds));
            }
        } else if (elapsedMs > 0 && sent > 0) {
            // Fallback to average speed
            double avgSpeedBytesPerSec = (sent * 1000.0) / elapsedMs;
            tvTransferSpeed.setText(formatSpeed(avgSpeedBytesPerSec));

            long remainingBytes = fileSize - sent;
            if (avgSpeedBytesPerSec > 0) {
                long remainingSeconds = (long) (remainingBytes / avgSpeedBytesPerSec);
                tvTimeRemaining.setText(formatTime(remainingSeconds));
            }
        }

        // If complete, show completion message
        if (percentage >= 100) {
            tvProgressPercent.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            tvTimeRemaining.setText("Complete");
        }
    }

    private void cancelTransfer() {
        isTransferring.set(false);
        Toast.makeText(this, "Cancelling transfer...", Toast.LENGTH_SHORT).show();

        // Close socket to interrupt transfer
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setFileIcon(String type) {
        int iconRes = R.drawable.ic_file_large;
        if (type != null) {
            type = type.toLowerCase();
            // Add specific icons for different file types if you have them
        }
        ivFilePreview.setImageResource(iconRes);
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        else if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        else if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        else return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024) return String.format("%.0f B/s", bytesPerSecond);
        else if (bytesPerSecond < 1024 * 1024) return String.format("%.2f KB/s", bytesPerSecond / 1024);
        else return String.format("%.2f MB/s", bytesPerSecond / (1024 * 1024));
    }

    private String formatTime(long seconds) {
        if (seconds < 60) return seconds + " sec";
        else if (seconds < 3600) {
            long mins = seconds / 60;
            long secs = seconds % 60;
            return mins + " min " + secs + " sec";
        } else {
            long hours = seconds / 3600;
            long mins = (seconds % 3600) / 60;
            return hours + " hr " + mins + " min";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isTransferring.set(false);

        try {
            if (dos != null) dos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Release locks
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                android.util.Log.d("TransferProgress", "WiFi lock released");
            }
        } catch (Exception e) {
            android.util.Log.e("TransferProgress", "Error releasing WiFi lock: " + e.getMessage());
        }

        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                android.util.Log.d("TransferProgress", "Wake lock released");
            }
        } catch (Exception e) {
            android.util.Log.e("TransferProgress", "Error releasing wake lock: " + e.getMessage());
        }

        executor.shutdownNow();
    }
}