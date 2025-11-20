package com.example.peerlink.Activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransferProgressActivity extends AppCompatActivity {

    private static final int SERVER_PORT = 8988;
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECTION_TIMEOUT = 10000; // 10 seconds

    // UI Elements - Progress State
    private LinearLayout contentContainer;
    private TextView tvTransferStatus, tvFileName, tvFileSize;
    private TextView tvProgressPercent, tvTransferSpeed, tvDataTransferred, tvTimeRemaining;
    private TextView tvTransferDirection, tvDeviceName;
    private View progressBar;
    private ImageView ivTransferIcon, ivFileIcon;
    private CardView btnCancel;
    private ProgressBar circularProgress;

    // UI Elements - Success State
    private LinearLayout successContainer;
    private TextView tvSuccessMessage, tvCompletedFileName, tvCompletedSize;
    private TextView tvCompletedDeviceName, tvTimeTaken;
    private CardView btnDone, btnSendAnother;
    private ImageView ivSuccessCheck;

    // Transfer data
    private String fileName;
    private long fileSize;
    private String fileType;
    private String deviceIp;
    private String deviceName;
    private Uri fileUri;
    private boolean isSender;

    // Transfer control
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isTransferring = new AtomicBoolean(false);
    private Socket socket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transfer_progress);

        initializeViews();
        loadTransferData();
        setupListeners();

        // Add small delay to ensure receiver is ready
        mainHandler.postDelayed(this::startFileTransfer, 1000);
    }

    private void initializeViews() {
        // Progress state views
        contentContainer = findViewById(R.id.contentContainer);
        tvTransferStatus = findViewById(R.id.tvTransferStatus);
        tvFileName = findViewById(R.id.tvFileName);
        tvFileSize = findViewById(R.id.tvFileSize);
        tvProgressPercent = findViewById(R.id.tvProgressPercent);
        tvTransferSpeed = findViewById(R.id.tvTransferSpeed);
        tvDataTransferred = findViewById(R.id.tvDataTransferred);
        tvTimeRemaining = findViewById(R.id.tvTimeRemaining);
        tvTransferDirection = findViewById(R.id.tvTransferDirection);
        tvDeviceName = findViewById(R.id.tvDeviceName);
        progressBar = findViewById(R.id.progressBar);
        ivTransferIcon = findViewById(R.id.ivTransferIcon);
        ivFileIcon = findViewById(R.id.ivFileIcon);
        btnCancel = findViewById(R.id.btnCancel);
        circularProgress = findViewById(R.id.circularProgress);

        // Success state views
        successContainer = findViewById(R.id.successContainer);
        tvSuccessMessage = findViewById(R.id.tvSuccessMessage);
        tvCompletedFileName = findViewById(R.id.tvCompletedFileName);
        tvCompletedSize = findViewById(R.id.tvCompletedSize);
        tvCompletedDeviceName = findViewById(R.id.tvCompletedDeviceName);
        tvTimeTaken = findViewById(R.id.tvTimeTaken);
        btnDone = findViewById(R.id.btnDone);
        btnSendAnother = findViewById(R.id.btnSendAnother);
        ivSuccessCheck = findViewById(R.id.ivSuccessCheck);

        // Initial state
        contentContainer.setVisibility(View.VISIBLE);
        successContainer.setVisibility(View.GONE);

        // Enable circular progress
        circularProgress.setVisibility(View.VISIBLE);
    }

    private void loadTransferData() {
        Intent intent = getIntent();

        String uriString = intent.getStringExtra("FILE_URI");
        fileUri = uriString != null ? Uri.parse(uriString) : null;
        fileName = intent.getStringExtra("FILE_NAME");
        fileSize = intent.getLongExtra("FILE_SIZE", 0);
        fileType = intent.getStringExtra("FILE_TYPE");
        deviceIp = intent.getStringExtra("DEVICE_IP");
        deviceName = intent.getStringExtra("DEVICE_NAME");
        isSender = intent.getBooleanExtra("IS_SENDER", true);

        // Update UI with file info
        if (isSender) {
            tvTransferStatus.setText("Sending File");
            tvTransferDirection.setText("Sending to");
        } else {
            tvTransferStatus.setText("Receiving File");
            tvTransferDirection.setText("Receiving from");
        }

        tvFileName.setText(fileName);
        tvFileSize.setText(formatFileSize(fileSize));
        tvDeviceName.setText(deviceName);

        // Set file icon
        setFileIcon(fileType);

        android.util.Log.d("TransferProgress", "Device IP: " + deviceIp + ", File: " + fileName);
    }

    private void setupListeners() {
        btnCancel.setOnClickListener(v -> cancelTransfer());
        btnDone.setOnClickListener(v -> finish());
        btnSendAnother.setOnClickListener(v -> {
            finish();
            // Navigate back to SendFileActivity
            Intent intent = new Intent(this, SendFileActivity.class);
            intent.putExtra("DEVICE_IP", deviceIp);
            intent.putExtra("DEVICE_NAME", deviceName);
            startActivity(intent);
        });
    }

    private void startFileTransfer() {
        if (fileUri == null || deviceIp == null) {
            Toast.makeText(this, "Missing transfer information", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        isTransferring.set(true);

        mainHandler.post(() -> tvTransferStatus.setText("Connecting..."));

        executor.execute(() -> {
            int maxRetries = 3;
            int retryCount = 0;

            while (retryCount < maxRetries && isTransferring.get()) {
                try {
                    // Create new socket connection for file data
                    socket = new Socket();
                    final int attempt = retryCount + 1;

                    mainHandler.post(() ->
                            tvTransferStatus.setText("Connecting (Attempt " + attempt + "/" + maxRetries + ")...")
                    );

                    socket.connect(new InetSocketAddress(deviceIp, SERVER_PORT), CONNECTION_TIMEOUT);

                    android.util.Log.d("TransferProgress", "Connected to receiver");

                    mainHandler.post(() -> {
                        Toast.makeText(this, "Connected! Starting transfer...", Toast.LENGTH_SHORT).show();
                        tvTransferStatus.setText("Sending File");
                    });

                    // Perform the file transfer
                    performFileTransfer(socket);

                    // If we reach here, transfer was successful
                    break;

                } catch (Exception e) {
                    retryCount++;
                    e.printStackTrace();
                    android.util.Log.e("TransferProgress", "Connection attempt " + retryCount + " failed: " + e.getMessage());

                    if (retryCount >= maxRetries) {
                        // All retries failed
                        final String errorMsg = e.getMessage();
                        mainHandler.post(() -> {
                            Toast.makeText(this, "Transfer failed after " + maxRetries + " attempts\n" + errorMsg,
                                    Toast.LENGTH_LONG).show();
                            finish();
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
        });
    }

    private void performFileTransfer(Socket socket) {
        try (InputStream fileInputStream = getContentResolver().openInputStream(fileUri);
             OutputStream outputStream = socket.getOutputStream();
             DataOutputStream dos = new DataOutputStream(outputStream)) {

            if (fileInputStream == null) {
                throw new Exception("Unable to read file");
            }

            // Send FILE_DATA marker
            dos.writeUTF("FILE_DATA");
            dos.flush();

            android.util.Log.d("TransferProgress", "Sent FILE_DATA marker, starting file transfer");

            // Transfer the file
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalSent = 0;
            int bytesRead;

            long startTime = System.currentTimeMillis();
            long lastUpdateTime = startTime;

            while (isTransferring.get() && (bytesRead = fileInputStream.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
                totalSent += bytesRead;

                long currentTime = System.currentTimeMillis();

                // Update UI every 200ms
                if (currentTime - lastUpdateTime >= 200) {
                    final long sent = totalSent;
                    final long elapsed = currentTime - startTime;

                    mainHandler.post(() -> updateProgress(sent, elapsed));

                    lastUpdateTime = currentTime;
                }
            }

            dos.flush();

            android.util.Log.d("TransferProgress", "File sent: " + totalSent + " bytes");

            // Transfer complete
            if (isTransferring.get() && totalSent == fileSize) {
                final long totalTime = System.currentTimeMillis() - startTime;

                mainHandler.post(() -> {
                    // Final progress update
                    updateProgress(fileSize, totalTime);

                    // Show success screen
                    showSuccessScreen(totalTime);

                    Toast.makeText(this, "File sent successfully!",
                            Toast.LENGTH_LONG).show();
                });
            } else if (totalSent < fileSize) {
                final long finalSent = totalSent;
                mainHandler.post(() -> {
                    Toast.makeText(this, "Transfer incomplete: " + finalSent + "/" + fileSize + " bytes",
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            }

        } catch (Exception e) {
            e.printStackTrace();
            android.util.Log.e("TransferProgress", "Transfer error: " + e.getMessage());

            final String errorMsg = e.getMessage();
            mainHandler.post(() -> {
                if (isTransferring.get()) {
                    Toast.makeText(this, "Transfer failed: " + errorMsg,
                            Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        } finally {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            isTransferring.set(false);
        }
    }

    private void updateProgress(long transferred, long elapsedMs) {
        // Calculate percentage
        int percentage = fileSize > 0 ? (int) ((transferred * 100) / fileSize) : 0;
        percentage = Math.min(percentage, 100);

        // Update progress bar width
        int parentWidth = 0;
        if (progressBar.getParent() != null) {
            parentWidth = ((View) progressBar.getParent()).getWidth();
        }

        int progressWidth = parentWidth > 0 ?
                (int) ((transferred * parentWidth) / fileSize) : 0;

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) progressBar.getLayoutParams();
        params.width = progressWidth;
        progressBar.setLayoutParams(params);

        // Update circular progress
        circularProgress.setProgress(percentage);

        // Update percentage text
        tvProgressPercent.setText(percentage + "%");

        // Update data transferred
        tvDataTransferred.setText(formatFileSize(transferred) + " / " + formatFileSize(fileSize));

        // Calculate and update speed
        if (elapsedMs > 0) {
            double speedBytesPerSec = (transferred * 1000.0) / elapsedMs;
            tvTransferSpeed.setText(formatSpeed(speedBytesPerSec));

            // Calculate time remaining
            if (transferred > 0 && transferred < fileSize) {
                long remaining = fileSize - transferred;
                double timeRemainingMs = (remaining * elapsedMs) / (double) transferred;
                tvTimeRemaining.setText(formatTimeRemaining((long) timeRemainingMs));
            } else {
                tvTimeRemaining.setText("Almost done...");
            }
        }
    }

    private void showSuccessScreen(long totalTimeMs) {
        contentContainer.setVisibility(View.GONE);
        successContainer.setVisibility(View.VISIBLE);

        // Update success message
        tvSuccessMessage.setText("File Sent Successfully!");

        // Update file details
        tvCompletedFileName.setText(fileName);
        tvCompletedSize.setText(formatFileSize(fileSize) + " transferred");
        tvCompletedDeviceName.setText(deviceName);

        // Update time taken
        tvTimeTaken.setText(formatTimeTaken(totalTimeMs));

        // Animate success icon
        animateSuccessIcon();
    }

    private void animateSuccessIcon() {
        ivSuccessCheck.setScaleX(0f);
        ivSuccessCheck.setScaleY(0f);
        ivSuccessCheck.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .start();
    }

    private void cancelTransfer() {
        isTransferring.set(false);

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Toast.makeText(this, "Transfer cancelled", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void setFileIcon(String type) {
        int iconRes = R.drawable.ic_file;

        if (type != null) {
            type = type.toLowerCase();
            if (type.contains("pdf")) {
                iconRes = R.drawable.ic_file;
            } else if (type.contains("jpg") || type.contains("jpeg") ||
                    type.contains("png") || type.contains("image")) {
                iconRes = R.drawable.ic_file;
            } else if (type.contains("doc") || type.contains("txt")) {
                iconRes = R.drawable.ic_file;
            } else if (type.contains("mp4") || type.contains("avi") ||
                    type.contains("video")) {
                iconRes = R.drawable.ic_file;
            } else if (type.contains("mp3") || type.contains("wav") ||
                    type.contains("audio")) {
                iconRes = R.drawable.ic_file;
            }
        }

        ivFileIcon.setImageResource(iconRes);
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        else if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        else if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB",
                bytes / (1024.0 * 1024));
        else return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024) return String.format("%.0f B/s", bytesPerSecond);
        else if (bytesPerSecond < 1024 * 1024) return String.format("%.2f KB/s",
                bytesPerSecond / 1024);
        else return String.format("%.2f MB/s", bytesPerSecond / (1024 * 1024));
    }

    private String formatTimeRemaining(long ms) {
        long seconds = ms / 1000;

        if (seconds < 60) {
            return seconds + " sec left";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return minutes + " min " + secs + " sec left";
        } else {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            return hours + " hr " + minutes + " min left";
        }
    }

    private String formatTimeTaken(long ms) {
        long seconds = ms / 1000;

        if (seconds < 60) {
            return seconds + " sec";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return minutes + " min " + secs + " sec";
        } else {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            return hours + " hr " + minutes + " min";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isTransferring.set(false);

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        executor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        // Prevent back button during transfer
        if (isTransferring.get()) {
            Toast.makeText(this, "Transfer in progress. Use cancel button to stop.",
                    Toast.LENGTH_SHORT).show();
        } else {
            super.onBackPressed();
        }
    }
}