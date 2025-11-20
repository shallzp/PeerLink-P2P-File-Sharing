package com.example.peerlink.Activity;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import com.example.peerlink.R;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReceiveFileActivity extends AppCompatActivity {

    private static final int SERVER_PORT = 8988;
    private static final int BUFFER_SIZE = 8192;

    // UI Elements - Waiting State
    private LinearLayout waitingIndicator;
    private ProgressBar progressWaiting;

    // UI Elements - Incoming File Card
    private CardView incomingFileCard;
    private ImageView ivIncomingFilePreview;
    private TextView tvIncomingFileName, tvIncomingFileSize, tvIncomingFileType, tvSenderDevice;

    // UI Elements - Action Buttons
    private LinearLayout actionButtonsContainer;
    private CardView btnStartReceiving, btnReject;

    // UI Elements - Progress Section
    private CardView progressSection;
    private TextView tvProgressFileName, tvProgressPercent, tvProgressDetails, tvTransferSpeed;
    private View progressBar;
    private View progressBarParent; // ADDED: Reference to parent view
    private ImageView btnCancelReceiving;

    // UI Elements - Connection Status
    private CardView statusBadge;
    private TextView tvConnectionStatus;

    // File transfer data
    private String incomingFileName = "";
    private long incomingFileSize = 0;
    private String incomingFileType = "";
    private String senderDeviceName = "";

    // Network - Store as class members
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private final AtomicBoolean isReceiving = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.receive_file);

        initializeViews();
        setupListeners();

        // Get connection info from intent
        String connectedDeviceIp = getIntent().getStringExtra("DEVICE_IP");
        String connectedDeviceName = getIntent().getStringExtra("DEVICE_NAME");
        if (connectedDeviceName != null) {
            senderDeviceName = connectedDeviceName;
        }

        // Automatically start listening when activity opens
        startListeningForFile();
    }

    private void initializeViews() {
        // Waiting state
        waitingIndicator = findViewById(R.id.waitingIndicator);
        progressWaiting = findViewById(R.id.progressWaiting);

        // Incoming file card
        incomingFileCard = findViewById(R.id.incomingFileCard);
        ivIncomingFilePreview = findViewById(R.id.ivIncomingFilePreview);
        tvIncomingFileName = findViewById(R.id.tvIncomingFileName);
        tvIncomingFileSize = findViewById(R.id.tvIncomingFileSize);
        tvIncomingFileType = findViewById(R.id.tvIncomingFileType);
        tvSenderDevice = findViewById(R.id.tvSenderDevice);

        // Action buttons
        actionButtonsContainer = findViewById(R.id.actionButtonsContainer);
        btnStartReceiving = findViewById(R.id.btnStartReceiving);
        btnReject = findViewById(R.id.btnReject);

        // Progress section
        progressSection = findViewById(R.id.progressSection);
        tvProgressFileName = findViewById(R.id.tvProgressFileName);
        tvProgressPercent = findViewById(R.id.tvProgressPercent);
        tvProgressDetails = findViewById(R.id.tvProgressDetails);
        tvTransferSpeed = findViewById(R.id.tvTransferSpeed);
        progressBar = findViewById(R.id.progressBar);
        progressBarParent = findViewById(R.id.progressBarContainer); // ADDED: Get parent container
        btnCancelReceiving = findViewById(R.id.btnCancelReceiving);

        // Status
        statusBadge = findViewById(R.id.statusBadge);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);

        // Initial state
        showWaitingState();
    }

    private void setupListeners() {
        btnStartReceiving.setOnClickListener(v -> acceptAndStartReceiving());
        btnReject.setOnClickListener(v -> rejectIncomingFile());
        btnCancelReceiving.setOnClickListener(v -> cancelReceiving());
    }

    private void showWaitingState() {
        waitingIndicator.setVisibility(View.VISIBLE);
        incomingFileCard.setVisibility(View.GONE);
        actionButtonsContainer.setVisibility(View.GONE);
        progressSection.setVisibility(View.GONE);
    }

    private void showIncomingFileState() {
        waitingIndicator.setVisibility(View.GONE);
        incomingFileCard.setVisibility(View.VISIBLE);
        actionButtonsContainer.setVisibility(View.VISIBLE);
        progressSection.setVisibility(View.GONE);
    }

    private void showReceivingState() {
        waitingIndicator.setVisibility(View.GONE);
        incomingFileCard.setVisibility(View.VISIBLE);
        actionButtonsContainer.setVisibility(View.GONE);
        progressSection.setVisibility(View.VISIBLE);
    }

    private void startListeningForFile() {
        if (isListening.get()) {
            return;
        }

        isListening.set(true);
        updateConnectionStatus("Listening...", android.R.color.holo_orange_light);

        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(SERVER_PORT);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Listening on port " + SERVER_PORT, Toast.LENGTH_SHORT).show();
                    updateConnectionStatus("Ready", android.R.color.holo_green_dark);
                    android.util.Log.d("ReceiveFile", "ServerSocket listening on port " + SERVER_PORT);
                });

                while (isListening.get()) {
                    // Wait for incoming connection
                    clientSocket = serverSocket.accept();
                    mainHandler.post(() ->
                            updateConnectionStatus("Connected", android.R.color.holo_green_dark));

                    // Handle the incoming connection
                    handleIncomingConnection(clientSocket);
                }

            } catch (Exception e) {
                if (isListening.get()) {
                    e.printStackTrace();
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Error listening: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        updateConnectionStatus("Error", android.R.color.holo_red_dark);
                    });
                }
            }
        });
    }

    private void handleIncomingConnection(Socket socket) {
        executor.execute(() -> {
            try {
                // Store streams as class members
                dataInputStream = new DataInputStream(socket.getInputStream());
                dataOutputStream = new DataOutputStream(socket.getOutputStream());

                // Read message type
                String messageType = dataInputStream.readUTF();

                if ("FILE_METADATA".equals(messageType)) {
                    // Read file metadata
                    incomingFileName = dataInputStream.readUTF();
                    incomingFileSize = dataInputStream.readLong();
                    incomingFileType = dataInputStream.readUTF();
                    senderDeviceName = dataInputStream.readUTF();

                    android.util.Log.d("ReceiveFile", "Metadata received: " + incomingFileName +
                            ", Size: " + incomingFileSize);

                    // Update UI to show incoming file
                    mainHandler.post(() -> displayIncomingFile());

                    // Keep socket and streams open for accept/reject
                }

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    Toast.makeText(this, "Error receiving metadata: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    closeSocket();
                    showWaitingState();
                    startListeningForFile();
                });
            }
        });
    }

    private void displayIncomingFile() {
        // Update incoming file card
        tvIncomingFileName.setText(incomingFileName);
        tvIncomingFileSize.setText(formatFileSize(incomingFileSize));
        tvIncomingFileType.setText(incomingFileType.toUpperCase());
        tvSenderDevice.setText(senderDeviceName);

        // Set file icon based on type
        setFileIcon(incomingFileType);

        // Show incoming file state
        showIncomingFileState();
        Toast.makeText(this, "Incoming file from " + senderDeviceName, Toast.LENGTH_SHORT).show();
    }

    private void acceptAndStartReceiving() {
        // Disable button to prevent double-click
        btnStartReceiving.setEnabled(false);

        executor.execute(() -> {
            try {
                // Validate streams
                if (dataOutputStream == null || clientSocket == null || clientSocket.isClosed()) {
                    throw new Exception("Connection lost. Please try again.");
                }

                // Send ACCEPT response to sender
                dataOutputStream.writeUTF("ACCEPT");
                dataOutputStream.flush();
                android.util.Log.d("ReceiveFile", "Sent ACCEPT response");

                mainHandler.post(() -> {
                    Toast.makeText(this, "Accepted! Waiting for file...", Toast.LENGTH_SHORT).show();
                    showReceivingState();
                    tvProgressFileName.setText(incomingFileName);
                });

                // IMPORTANT: Close the metadata socket
                // Sender will create a NEW connection for file transfer
                closeSocket();
                android.util.Log.d("ReceiveFile", "Closed metadata socket, waiting for new connection...");

                // Wait for NEW connection from TransferProgressActivity
                clientSocket = serverSocket.accept();
                android.util.Log.d("ReceiveFile", "New connection accepted for file transfer");

                // Create new streams for the new connection
                dataInputStream = new DataInputStream(clientSocket.getInputStream());
                dataOutputStream = new DataOutputStream(clientSocket.getOutputStream());

                // Wait for FILE_DATA marker from sender
                String dataMarker = dataInputStream.readUTF();
                android.util.Log.d("ReceiveFile", "Received marker: " + dataMarker);

                if ("FILE_DATA".equals(dataMarker)) {
                    // Start receiving the actual file
                    receiveFile();
                } else {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Invalid data received from sender",
                                Toast.LENGTH_SHORT).show();
                        resetToWaitingState();
                    });
                }

            } catch (Exception e) {
                e.printStackTrace();
                final String errorMsg = e.getMessage();
                mainHandler.post(() -> {
                    Toast.makeText(this, "Error accepting file: " + errorMsg,
                            Toast.LENGTH_LONG).show();
                    resetToWaitingState();
                });
            }
        });
    }

    private void receiveFile() {
        isReceiving.set(true);

        try {
            // Create custom folder in Documents directory
            File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);

            // Create your app's custom folder
            File peerLinkFolder = new File(documentsDir, "PeerLink");
            if (!peerLinkFolder.exists()) {
                boolean created = peerLinkFolder.mkdirs();
                android.util.Log.d("ReceiveFile", "PeerLink folder created: " + created +
                        " at " + peerLinkFolder.getAbsolutePath());
            }

            File outputFile = new File(peerLinkFolder, incomingFileName);

            // If file exists, create unique name
            int counter = 1;
            while (outputFile.exists()) {
                int dotIndex = incomingFileName.lastIndexOf('.');
                if (dotIndex > 0) {
                    String nameWithoutExt = incomingFileName.substring(0, dotIndex);
                    String ext = incomingFileName.substring(dotIndex);
                    outputFile = new File(peerLinkFolder, nameWithoutExt + "_" + counter + ext);
                } else {
                    outputFile = new File(peerLinkFolder, incomingFileName + "_" + counter);
                }
                counter++;
            }

            final File finalOutputFile = outputFile;
            android.util.Log.d("ReceiveFile", "Saving to: " + finalOutputFile.getAbsolutePath());

            // Check write permissions
            if (!peerLinkFolder.canWrite()) {
                throw new Exception("No write permission to Documents/PeerLink folder");
            }

            FileOutputStream fos = new FileOutputStream(finalOutputFile);
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalReceived = 0;
            int bytesRead;
            long startTime = System.currentTimeMillis();
            long lastUpdateTime = startTime;

            while (isReceiving.get() && totalReceived < incomingFileSize) {
                int remaining = (int) Math.min(BUFFER_SIZE, incomingFileSize - totalReceived);
                bytesRead = dataInputStream.read(buffer, 0, remaining);

                if (bytesRead == -1) {
                    android.util.Log.d("ReceiveFile", "End of stream at " + totalReceived + " bytes");
                    break;
                }

                fos.write(buffer, 0, bytesRead);
                totalReceived += bytesRead;

                long currentTime = System.currentTimeMillis();

                // Update UI every 200ms
                if (currentTime - lastUpdateTime >= 200) {
                    final long received = totalReceived;
                    final long elapsed = currentTime - startTime;
                    mainHandler.post(() -> updateProgress(received, elapsed));
                    lastUpdateTime = currentTime;
                }
            }

            fos.flush();
            fos.close();

            android.util.Log.d("ReceiveFile", "File received: " + totalReceived + " / " +
                    incomingFileSize + " bytes");
            android.util.Log.d("ReceiveFile", "File exists: " + finalOutputFile.exists() +
                    ", Size: " + finalOutputFile.length());

            // Make file visible in file managers and gallery
            scanMediaFile(finalOutputFile);

            // Transfer complete
            if (totalReceived >= incomingFileSize) {
                final long totalTime = System.currentTimeMillis() - startTime;
                mainHandler.post(() -> {
                    Toast.makeText(this, "File saved successfully!\nDocuments/PeerLink/" +
                            finalOutputFile.getName(), Toast.LENGTH_LONG).show();
                    updateProgress(incomingFileSize, totalTime);
                    tvProgressPercent.setText("100%");
                    tvProgressPercent.setTextColor(
                            getResources().getColor(android.R.color.holo_green_dark));
                    mainHandler.postDelayed(this::resetToWaitingState, 3000);
                });
            } else {
                final long finalReceived = totalReceived;
                mainHandler.post(() -> {
                    Toast.makeText(this, "Transfer incomplete. Received " +
                                    finalReceived + " of " + incomingFileSize + " bytes",
                            Toast.LENGTH_LONG).show();
                    resetToWaitingState();
                });
            }

        } catch (Exception e) {
            e.printStackTrace();
            final String errorMsg = e.getMessage();
            mainHandler.post(() -> {
                Toast.makeText(this, "Error receiving file: " + errorMsg,
                        Toast.LENGTH_LONG).show();
                resetToWaitingState();
            });
        } finally {
            isReceiving.set(false);
            closeSocket();
        }
    }

    // Add this method to make files visible in file managers
    private void scanMediaFile(File file) {
        android.media.MediaScannerConnection.scanFile(
                this,
                new String[]{file.getAbsolutePath()},
                null,
                (path, uri) -> {
                    android.util.Log.d("ReceiveFile", "File scanned: " + path);
                    android.util.Log.d("ReceiveFile", "Media URI: " + uri);
                }
        );
    }

    private void updateProgress(long received, long elapsedMs) {
        // Calculate percentage
        int percentage = incomingFileSize > 0 ? (int) ((received * 100) / incomingFileSize) : 0;
        percentage = Math.min(percentage, 100);

        // FIXED: Get parent width properly
        int parentWidth = 0;
        if (progressBarParent != null) {
            parentWidth = progressBarParent.getWidth();
        }

        // Update progress bar width
        if (parentWidth > 0 && received > 0 && incomingFileSize > 0) {
            int progressWidth = (int) ((received * parentWidth) / incomingFileSize);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) progressBar.getLayoutParams();
            params.width = progressWidth;
            progressBar.setLayoutParams(params);
        }

        // Update percentage text
        tvProgressPercent.setText(percentage + "%");

        // Update data transferred
        tvProgressDetails.setText(formatFileSize(received) + " / " + formatFileSize(incomingFileSize));

        // Calculate and update speed
        if (elapsedMs > 0) {
            double speedBytesPerSec = (received * 1000.0) / elapsedMs;
            tvTransferSpeed.setText(formatSpeed(speedBytesPerSec));
        }
    }

    private void rejectIncomingFile() {
        executor.execute(() -> {
            try {
                // Send REJECT response to sender
                if (dataOutputStream != null) {
                    dataOutputStream.writeUTF("REJECT");
                    dataOutputStream.flush();
                }

                mainHandler.post(() -> {
                    Toast.makeText(this, "File transfer rejected", Toast.LENGTH_SHORT).show();
                    resetToWaitingState();
                });

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(this::resetToWaitingState);
            } finally {
                closeSocket();
            }
        });
    }

    private void cancelReceiving() {
        isReceiving.set(false);
        Toast.makeText(this, "Transfer cancelled", Toast.LENGTH_SHORT).show();
        resetToWaitingState();
        closeSocket();
    }

    private void resetToWaitingState() {
        incomingFileName = "";
        incomingFileSize = 0;
        incomingFileType = "";
        dataInputStream = null;
        dataOutputStream = null;

        closeSocket();
        showWaitingState();

        // Restart listening
        startListeningForFile();
    }

    private void closeSocket() {
        try {
            if (dataInputStream != null) {
                dataInputStream.close();
            }
            if (dataOutputStream != null) {
                dataOutputStream.close();
            }
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateConnectionStatus(String status, int colorResId) {
        tvConnectionStatus.setText(status);
        statusBadge.setCardBackgroundColor(getResources().getColor(colorResId));
    }

    private void setFileIcon(String type) {
        int iconRes = R.drawable.ic_file_large;
        if (type != null) {
            type = type.toLowerCase();
            // You can add specific icons for different file types
        }
        ivIncomingFilePreview.setImageResource(iconRes);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isListening.set(false);
        isReceiving.set(false);
        closeSocket();

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        executor.shutdownNow();
    }
}