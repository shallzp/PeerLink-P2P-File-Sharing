package com.example.peerlink.Activity;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
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
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReceiveFileActivity extends AppCompatActivity {

    private static final int SERVER_PORT = 8988;
    private static final int BUFFER_SIZE = 16384; // MATCHED: 16 KB buffer to match sender
    private static final int SOCKET_BUFFER_SIZE = 262144; // 256 KB socket buffer
    private static final int BASE_TIMEOUT = 60000; // 1 minute base timeout

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
    private View progressBarParent;
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

    // Locks for keeping connection stable
    private WifiManager.WifiLock wifiLock = null;
    private PowerManager.WakeLock wakeLock = null;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private final AtomicBoolean isReceiving = new AtomicBoolean(false);
    private final AtomicBoolean awaitingFileDataConnection = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.receive_file);

        // Keep screen on during file transfer
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initializeViews();
        setupListeners();
        acquireLocks();

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
        progressBarParent = findViewById(R.id.progressBarContainer);
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

    /**
     * Acquire WiFi and Wake locks to ensure stable transfer
     */
    private void acquireLocks() {
        try {
            // WiFi Lock - Prevent WiFi from sleeping
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PeerLink:ReceiveFileLock");
                wifiLock.acquire();
                android.util.Log.d("ReceiveFile", "WiFi lock acquired");
            }

            // Wake Lock - Keep CPU running
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PeerLink:ReceiveFileWakeLock");
                wakeLock.acquire();
                android.util.Log.d("ReceiveFile", "Wake lock acquired");
            }
        } catch (Exception e) {
            android.util.Log.e("ReceiveFile", "Error acquiring locks: " + e.getMessage());
        }
    }

    /**
     * Calculate dynamic timeout based on file size
     * Assumes minimum speed of 512 KB/s with 3x safety buffer
     */
    private int calculateDynamicTimeout(long fileSize) {
        if (fileSize == 0) {
            return BASE_TIMEOUT;
        }

        // Calculate estimated time (assuming 512 KB/s minimum speed)
        long estimatedSeconds = (fileSize / (512 * 1024)) * 3; // 3x buffer
        int timeoutMs = (int) Math.min(estimatedSeconds * 1000, 600000); // Max 10 minutes
        int finalTimeout = Math.max(BASE_TIMEOUT, timeoutMs);

        android.util.Log.d("ReceiveFile", "Calculated timeout: " + (finalTimeout / 1000) +
                " seconds for " + formatFileSize(fileSize));

        return finalTimeout;
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
        updateConnectionStatus("Listening...", R.color.accent_warm);

        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(SERVER_PORT);

                // OPTIMIZATION: Set larger receive buffer for server socket
                serverSocket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);

                mainHandler.post(() -> {
                    Toast.makeText(this, "Listening on port " + SERVER_PORT, Toast.LENGTH_SHORT).show();
                    updateConnectionStatus("Ready", R.color.accent_success);
                    android.util.Log.d("ReceiveFile", "ServerSocket listening on port " + SERVER_PORT +
                            ". Buffer: " + BUFFER_SIZE + " bytes, Socket buffer: " + SOCKET_BUFFER_SIZE + " bytes");
                });

                while (isListening.get()) {
                    // Wait for incoming connection
                    clientSocket = serverSocket.accept();

                    // MATCHED: Same socket configuration as sender
                    clientSocket.setReceiveBufferSize(SOCKET_BUFFER_SIZE); // 256 KB
                    clientSocket.setSendBufferSize(SOCKET_BUFFER_SIZE);    // 256 KB
                    clientSocket.setTcpNoDelay(false);                     // Enable Nagle's algorithm
                    clientSocket.setKeepAlive(true);                        // Enable TCP keep-alive
                    clientSocket.setSoLinger(true, 0);                     // Immediate close

                    android.util.Log.d("ReceiveFile", "Client connected with matched socket settings");

                    mainHandler.post(() ->
                            updateConnectionStatus("Connected", R.color.accent_success));

                    // Handle the incoming connection
                    handleIncomingConnection(clientSocket);
                }

            } catch (SocketException e) {
                if (isListening.get()) {
                    e.printStackTrace();
                    android.util.Log.e("ReceiveFile", "Socket error while listening: " + e.getMessage());
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Socket error: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        updateConnectionStatus("Error", R.color.accent_error);
                    });
                }
            } catch (Exception e) {
                if (isListening.get()) {
                    e.printStackTrace();
                    android.util.Log.e("ReceiveFile", "Error listening: " + e.getMessage());
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Error listening: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        updateConnectionStatus("Error", R.color.accent_error);
                    });
                }
            }
        });
    }

    private void handleIncomingConnection(Socket socket) {
        executor.execute(() -> {
            DataInputStream localInputStream = null;
            DataOutputStream localOutputStream = null;
            try {
                localInputStream = new DataInputStream(socket.getInputStream());
                localOutputStream = new DataOutputStream(socket.getOutputStream());

                // Store active connection
                synchronized (this) {
                    clientSocket = socket;
                    dataInputStream = localInputStream;
                    dataOutputStream = localOutputStream;
                }

                // Read message type
                String messageType = localInputStream.readUTF();

                if ("FILE_METADATA".equals(messageType)) {
                    awaitingFileDataConnection.set(false);

                    // Read file metadata
                    incomingFileName = localInputStream.readUTF();
                    incomingFileSize = localInputStream.readLong();
                    incomingFileType = localInputStream.readUTF();
                    senderDeviceName = localInputStream.readUTF();

                    android.util.Log.d("ReceiveFile", "Metadata received: " + incomingFileName +
                            ", Size: " + incomingFileSize + " bytes (" + formatFileSize(incomingFileSize) + ")" +
                            ", Type: " + incomingFileType);

                    // Show warning for very large files
                    if (incomingFileSize > 5L * 1024 * 1024 * 1024) { // > 5 GB
                        final String warningMsg = "Large file (" + formatFileSize(incomingFileSize) +
                                "). Keep devices close during transfer.";
                        mainHandler.post(() ->
                                Toast.makeText(this, warningMsg, Toast.LENGTH_LONG).show());
                    }

                    // Update UI to show incoming file
                    mainHandler.post(() -> displayIncomingFile());

                    // Keep socket and streams open for accept/reject
                } else if ("FILE_DATA".equals(messageType)) {
                    if (!awaitingFileDataConnection.get()) {
                        throw new Exception("Unexpected file data connection. Accept the file first.");
                    }

                    awaitingFileDataConnection.set(false);
                    android.util.Log.d("ReceiveFile", "FILE_DATA connection received. Starting transfer...");
                    receiveFile();
                } else {
                    throw new Exception("Unknown message type: " + messageType);
                }

            } catch (Exception e) {
                e.printStackTrace();
                final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unexpected connection error";
                android.util.Log.e("ReceiveFile", "Error handling incoming connection: " + errorMsg);

                closeConnection(socket, localInputStream, localOutputStream);
                clearActiveConnectionIfMatches(socket, localInputStream, localOutputStream);

                mainHandler.post(() -> {
                    Toast.makeText(this, "Error receiving data: " + errorMsg,
                            Toast.LENGTH_LONG).show();
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
            Socket metadataSocket;
            DataInputStream metadataInput;
            DataOutputStream metadataOutput;

            synchronized (this) {
                metadataSocket = clientSocket;
                metadataInput = dataInputStream;
                metadataOutput = dataOutputStream;
            }

            try {
                // Validate streams
                if (metadataOutput == null || metadataSocket == null || metadataSocket.isClosed()) {
                    throw new Exception("Connection lost. Please try again.");
                }

                // Send ACCEPT response to sender
                metadataOutput.writeUTF("ACCEPT");
                metadataOutput.flush();
                awaitingFileDataConnection.set(true);
                android.util.Log.d("ReceiveFile", "Sent ACCEPT response");

                mainHandler.post(() -> {
                    Toast.makeText(this, "Accepted! Waiting for sender...", Toast.LENGTH_SHORT).show();
                    showReceivingState();
                    tvProgressFileName.setText(incomingFileName);
                });

                // Close only metadata connection. Listener thread will handle incoming FILE_DATA socket.
                closeConnection(metadataSocket, metadataInput, metadataOutput);
                clearActiveConnectionIfMatches(metadataSocket, metadataInput, metadataOutput);
                android.util.Log.d("ReceiveFile", "Closed metadata socket, waiting for FILE_DATA connection...");

                mainHandler.post(() ->
                        updateConnectionStatus("Waiting for sender...", R.color.accent_warm));

            } catch (NullPointerException e) {
                e.printStackTrace();
                awaitingFileDataConnection.set(false);
                final String errorMsg = "Internal error: " + (e.getMessage() != null ? e.getMessage() :
                        "Null pointer - connection lost");
                android.util.Log.e("ReceiveFile", "NullPointerException in accept: " + errorMsg);
                mainHandler.post(() -> {
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                    resetToWaitingState();
                });
            } catch (Exception e) {
                e.printStackTrace();
                awaitingFileDataConnection.set(false);
                final String errorMsg;
                if (e.getMessage() != null && !e.getMessage().trim().isEmpty()) {
                    errorMsg = e.getMessage();
                } else {
                    errorMsg = "Unexpected error (" + e.getClass().getSimpleName() + ")";
                }
                android.util.Log.e("ReceiveFile", "Error accepting file: " + errorMsg);
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
        FileOutputStream fos = null;

        try {
            // Validate socket and streams
            if (clientSocket == null || clientSocket.isClosed()) {
                throw new Exception("Client socket is not connected");
            }

            if (dataInputStream == null) {
                throw new Exception("Data input stream is null");
            }

            // Create custom folder in Documents directory
            File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (documentsDir == null) {
                throw new Exception("Cannot access Documents directory");
            }

            // Create your app's custom folder
            File peerLinkFolder = new File(documentsDir, "PeerLink");
            if (!peerLinkFolder.exists()) {
                boolean created = peerLinkFolder.mkdirs();
                android.util.Log.d("ReceiveFile", "PeerLink folder created: " + created +
                        " at " + peerLinkFolder.getAbsolutePath());

                if (!created) {
                    throw new Exception("Failed to create PeerLink folder");
                }
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

            fos = new FileOutputStream(finalOutputFile);
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalReceived = 0;
            int bytesRead;
            long startTime = System.currentTimeMillis();
            long lastUpdateTime = startTime;
            int packetCount = 0;

            android.util.Log.d("ReceiveFile", "Starting file reception with " + BUFFER_SIZE + " byte buffer");

            while (isReceiving.get() && totalReceived < incomingFileSize) {
                int remaining = (int) Math.min(BUFFER_SIZE, incomingFileSize - totalReceived);

                try {
                    bytesRead = dataInputStream.read(buffer, 0, remaining);

                    if (bytesRead == -1) {
                        android.util.Log.d("ReceiveFile", "End of stream at " + totalReceived + " bytes");
                        break;
                    }

                    fos.write(buffer, 0, bytesRead);
                    totalReceived += bytesRead;
                    packetCount++;

                    // MATCHED: Flush every 10 packets like sender
                    if (packetCount % 10 == 0) {
                        fos.flush();
                        android.util.Log.d("ReceiveFile",
                                "Checkpoint at " + formatFileSize(totalReceived) +
                                        " (" + packetCount + " packets)");
                    }

                } catch (SocketTimeoutException e) {
                    android.util.Log.e("ReceiveFile", "Socket timeout at " + totalReceived +
                            " bytes (" + packetCount + " packets)");
                    throw new Exception("Connection timeout during transfer at " + formatFileSize(totalReceived));
                } catch (SocketException e) {
                    android.util.Log.e("ReceiveFile", "Socket error at " + totalReceived +
                            " bytes (" + packetCount + " packets): " +
                            (e.getMessage() != null ? e.getMessage() : "Connection lost"));
                    throw new Exception("Connection interrupted at " + formatFileSize(totalReceived) +
                            ": " + (e.getMessage() != null ? e.getMessage() : "Connection lost"));
                } catch (NullPointerException e) {
                    android.util.Log.e("ReceiveFile", "Null pointer at " + totalReceived + " bytes");
                    e.printStackTrace();
                    throw new Exception("Internal error during transfer: Input stream became null");
                }

                long currentTime = System.currentTimeMillis();

                // Update UI every 200ms
                if (currentTime - lastUpdateTime >= 200) {
                    final long received = totalReceived;
                    final long elapsed = currentTime - startTime;
                    mainHandler.post(() -> updateProgress(received, elapsed));
                    lastUpdateTime = currentTime;
                }
            }

            if (fos != null) {
                fos.flush();
                fos.close();
                fos = null;
            }

            android.util.Log.d("ReceiveFile", "File received: " + totalReceived + " / " +
                    incomingFileSize + " bytes (" + packetCount + " packets)");
            android.util.Log.d("ReceiveFile", "File exists: " + finalOutputFile.exists() +
                    ", Size: " + finalOutputFile.length());

            // Make file visible in file managers
            scanMediaFile(finalOutputFile);

            // Transfer complete
            if (totalReceived >= incomingFileSize) {
                final long totalTime = System.currentTimeMillis() - startTime;
                final double avgSpeedMBps = (totalReceived / (1024.0 * 1024)) / (totalTime / 1000.0);
                android.util.Log.d("ReceiveFile", "Transfer completed successfully. Average speed: " +
                        String.format("%.2f MB/s", avgSpeedMBps));

                mainHandler.post(() -> {
                    Toast.makeText(this, "File saved successfully!\nDocuments/PeerLink/" +
                            finalOutputFile.getName(), Toast.LENGTH_LONG).show();
                    updateProgress(incomingFileSize, totalTime);
                    tvProgressPercent.setText("100%");
                    tvProgressPercent.setTextColor(
                            getResources().getColor(R.color.accent_success));
                    mainHandler.postDelayed(this::resetToWaitingState, 3000);
                });
            } else {
                final long finalReceived = totalReceived;
                mainHandler.post(() -> {
                    Toast.makeText(this, "Transfer incomplete. Received " +
                                    formatFileSize(finalReceived) + " of " + formatFileSize(incomingFileSize),
                            Toast.LENGTH_LONG).show();
                    resetToWaitingState();
                });
            }

        } catch (NullPointerException e) {
            e.printStackTrace();
            final String errorMsg = "Internal error: " + (e.getMessage() != null ? e.getMessage() : "Null pointer exception");
            android.util.Log.e("ReceiveFile", "NullPointerException in receiveFile: " + errorMsg);
            mainHandler.post(() -> {
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                resetToWaitingState();
            });
        } catch (Exception e) {
            e.printStackTrace();
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error occurred";
            android.util.Log.e("ReceiveFile", "Error receiving file: " + errorMsg);
            mainHandler.post(() -> {
                Toast.makeText(this, "Error receiving file: " + errorMsg,
                        Toast.LENGTH_LONG).show();
                resetToWaitingState();
            });
        } finally {
            isReceiving.set(false);

            // Close file output stream if still open
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception e) {
                    android.util.Log.e("ReceiveFile", "Error closing file output stream: " + e.getMessage());
                }
            }

            closeSocket();
        }
    }

    /**
     * Make files visible in file managers and gallery
     */
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

        // Get parent width properly
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
        awaitingFileDataConnection.set(false);

        closeSocket();
        showWaitingState();

        // Restart listening
        startListeningForFile();
    }

    private void closeSocket() {
        Socket socketToClose;
        DataInputStream inputToClose;
        DataOutputStream outputToClose;

        synchronized (this) {
            socketToClose = clientSocket;
            inputToClose = dataInputStream;
            outputToClose = dataOutputStream;
            clientSocket = null;
            dataInputStream = null;
            dataOutputStream = null;
        }

        closeConnection(socketToClose, inputToClose, outputToClose);
    }

    private synchronized void clearActiveConnectionIfMatches(Socket socket, DataInputStream input, DataOutputStream output) {
        if (clientSocket == socket) {
            clientSocket = null;
        }
        if (dataInputStream == input) {
            dataInputStream = null;
        }
        if (dataOutputStream == output) {
            dataOutputStream = null;
        }
    }

    private void closeConnection(Socket socket, DataInputStream input, DataOutputStream output) {
        try {
            if (input != null) {
                input.close();
            }
            if (output != null) {
                output.close();
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
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

        // Release locks
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                android.util.Log.d("ReceiveFile", "WiFi lock released");
            }
        } catch (Exception e) {
            android.util.Log.e("ReceiveFile", "Error releasing WiFi lock: " + e.getMessage());
        }

        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                android.util.Log.d("ReceiveFile", "Wake lock released");
            }
        } catch (Exception e) {
            android.util.Log.e("ReceiveFile", "Error releasing wake lock: " + e.getMessage());
        }

        executor.shutdownNow();
    }
}
