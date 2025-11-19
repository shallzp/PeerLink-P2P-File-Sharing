package com.example.peerlink.Activity;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.example.peerlink.R;

public class ReceiveFileActivity extends AppCompatActivity {

    private TextView tvFileStatus, tvIncomingFileName, tvIncomingFileSize, tvSenderDevice;
    private ImageView ivFileIcon;
    private LinearLayout fileDetailsContainer, waitingIndicator;
    private ProgressBar progressWaiting;
    private CardView btnWaitForFile, btnStartReceiving, btnReject;

    private Uri incomingFileUri = null; // placeholder for the incoming file URI
    private String senderDeviceName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.receive_file);

        tvFileStatus = findViewById(R.id.tvFileStatus);
        tvIncomingFileName = findViewById(R.id.tvIncomingFileName);
        tvIncomingFileSize = findViewById(R.id.tvIncomingFileSize);
        tvSenderDevice = findViewById(R.id.tvSenderDevice);
        ivFileIcon = findViewById(R.id.ivFileIcon);

        fileDetailsContainer = findViewById(R.id.fileDetailsContainer);
        waitingIndicator = findViewById(R.id.waitingIndicator);
        progressWaiting = findViewById(R.id.progressWaiting);

        btnWaitForFile = findViewById(R.id.btnWaitForFile);
        btnStartReceiving = findViewById(R.id.btnStartReceiving);
        btnReject = findViewById(R.id.btnReject);

        // Initial UI state on activity start
        showWaitingState();

        // Wait for File button click: start listening or initiate discovery for incoming files
        btnWaitForFile.setOnClickListener(v -> {
            Toast.makeText(this, "Listening for incoming files...", Toast.LENGTH_SHORT).show();
            startListeningForFile();
        });

        // Start Receiving button click: accept incoming file transfer
        btnStartReceiving.setOnClickListener(v -> {
            if (incomingFileUri != null) {
                startReceivingFile(incomingFileUri);
            } else {
                Toast.makeText(this, "No incoming file to receive", Toast.LENGTH_SHORT).show();
            }
        });

        // Reject button click: reject incoming file transfer request
        btnReject.setOnClickListener(v -> rejectIncomingFile());
    }

    // Shows UI elements related to waiting/listening state
    private void showWaitingState() {
        btnWaitForFile.setVisibility(View.VISIBLE);
        waitingIndicator.setVisibility(View.VISIBLE);

        btnStartReceiving.setVisibility(View.GONE);
        btnReject.setVisibility(View.GONE);
        fileDetailsContainer.setVisibility(View.GONE);

        tvFileStatus.setText("No file selected");
        tvIncomingFileName.setVisibility(View.GONE);
        tvIncomingFileSize.setText("");
        tvSenderDevice.setText("");
        ivFileIcon.setImageResource(R.drawable.ic_file_incoming); // default incoming file icon
    }

    // Called when an incoming file transfer request is detected
    // Updates UI with file info and displays accept/reject buttons
    private void onIncomingFileDetected(Uri fileUri, String fileName, long fileSize, String senderName) {
        incomingFileUri = fileUri;
        senderDeviceName = senderName;

        btnWaitForFile.setVisibility(View.GONE);
        waitingIndicator.setVisibility(View.GONE);

        btnStartReceiving.setVisibility(View.VISIBLE);
        btnReject.setVisibility(View.VISIBLE);
        fileDetailsContainer.setVisibility(View.VISIBLE);

        tvFileStatus.setText("Incoming file");
        tvIncomingFileName.setText(fileName);
        tvIncomingFileName.setVisibility(View.VISIBLE);
        tvIncomingFileSize.setText(formatFileSize(fileSize));
        tvSenderDevice.setText(senderName);

        // Optionally update file icon based on file type (leave as is for now)
        ivFileIcon.setImageResource(R.drawable.ic_file_incoming);
    }

    // Simulated method to start listening for incoming files (stub)
    private void startListeningForFile() {
        // TODO: Add real listening code for incoming connections/files here.

        // For demo, simulate an incoming file after delay (replace with actual network event)
        tvFileStatus.postDelayed(() -> {
            onIncomingFileDetected(
                    Uri.parse("content://example/incoming/file.pdf"),
                    "file.pdf",
                    2_500_000L, // size in bytes (~2.5 MB)
                    "Android_456"
            );
        }, 3000);
    }

    // Starts receiving the file with the specified uri
    private void startReceivingFile(Uri uri) {
        Toast.makeText(this, "Starting to receive file: " + uri.toString(), Toast.LENGTH_SHORT).show();
        // TODO: Implement actual file receiving logic (open socket, save stream, etc.)

        // After receiving, reset UI to waiting state (for demo)
        tvFileStatus.postDelayed(this::showWaitingState, 5000);
    }

    // Rejects an incoming file transfer request
    private void rejectIncomingFile() {
        Toast.makeText(this, "Incoming file transfer rejected", Toast.LENGTH_SHORT).show();
        incomingFileUri = null;
        senderDeviceName = "";
        showWaitingState();
    }

    // Utility to format file size for display (e.g., "2.5 MB")
    private String formatFileSize(long sizeInBytes) {
        if (sizeInBytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(sizeInBytes) / Math.log10(1024));
        return String.format("%.1f %s",
                sizeInBytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}