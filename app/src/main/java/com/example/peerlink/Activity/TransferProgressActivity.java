package com.example.peerlink.Activity;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.example.peerlink.R;

public class TransferProgressActivity extends AppCompatActivity {

    private TextView tvTransferStatus, tvFileName, tvFileSize, tvProgressPercent, tvTransferSpeed,
            tvTransferDirection, tvDeviceName, tvSuccessMessage, tvCompletedFileName, tvCompletedSize;
    private View progressBar;
    private LinearLayout contentContainer, successContainer;
    private CardView btnCancel, btnDone;
    private ImageView ivTransferIcon;

    private long totalBytes = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transfer_progress);

        // Initialize views
        contentContainer = findViewById(R.id.contentContainer);
        successContainer = findViewById(R.id.successContainer);
        ivTransferIcon = findViewById(R.id.ivTransferIcon);

        tvTransferStatus = findViewById(R.id.tvTransferStatus);
        tvFileName = findViewById(R.id.tvFileName);
        tvFileSize = findViewById(R.id.tvFileSize);
        tvProgressPercent = findViewById(R.id.tvProgressPercent);
        tvTransferSpeed = findViewById(R.id.tvTransferSpeed);
        tvTransferDirection = findViewById(R.id.tvTransferDirection);
        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvSuccessMessage = findViewById(R.id.tvSuccessMessage);
        tvCompletedFileName = findViewById(R.id.tvCompletedFileName);
        tvCompletedSize = findViewById(R.id.tvCompletedSize);
        progressBar = findViewById(R.id.progressBar);

        btnCancel = findViewById(R.id.btnCancel);
        btnDone = findViewById(R.id.btnDone);

        // Cancel button listener to stop transfer and close activity
        btnCancel.setOnClickListener(v -> {
            cancelTransfer();
            finish();
        });

        // Done button listener to close the success screen
        btnDone.setOnClickListener(v -> finish());

        // Initialize UI for sending mode by default; you can adapt as needed
        setModeSending("document.pdf", 2_500_000L, "Android_456");
    }

    // Call to update UI for sending mode
    public void setModeSending(String fileName, long fileSize, String deviceName) {
        contentContainer.setVisibility(View.VISIBLE);
        successContainer.setVisibility(View.GONE);

        tvTransferStatus.setText("Sending File");
        tvFileName.setText(fileName);
        tvFileSize.setText(formatFileSize(fileSize));
        tvTransferDirection.setText("Sending to");
        tvDeviceName.setText(deviceName);

        updateProgress(0, 0);
    }

    // Call to update UI for receiving mode
    public void setModeReceiving(String fileName, long fileSize, String deviceName) {
        contentContainer.setVisibility(View.VISIBLE);
        successContainer.setVisibility(View.GONE);

        tvTransferStatus.setText("Receiving File");
        tvFileName.setText(fileName);
        tvFileSize.setText(formatFileSize(fileSize));
        tvTransferDirection.setText("Receiving from");
        tvDeviceName.setText(deviceName);

        updateProgress(0, 0);
    }

    // Call to update progress UI
    // bytesTransferred: how many bytes transferred so far
    // bytesPerSec: current transfer speed in bytes per second
    public void updateProgress(long bytesTransferred, long bytesPerSec) {
        if (totalBytes <= 0) return;

        int percent = (int) ((bytesTransferred * 100) / totalBytes);
        percent = Math.min(percent, 100);

        tvProgressPercent.setText(percent + "%");
        tvTransferSpeed.setText(formatFileSize(bytesPerSec) + "/s");

        final int percentFinal = percent;  // Make final for lambda use
        progressBar.post(() -> {
            int width = ((View) progressBar.getParent()).getWidth();
            int newWidth = (width * percentFinal) / 100;
            progressBar.getLayoutParams().width = newWidth;
            progressBar.requestLayout();
        });

        if (percent >= 100) {
            showSuccessScreen();
        }
    }

    // Call this method once you know the total file size before starting transfer
    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    // Shows success UI after successful transfer
    private void showSuccessScreen() {
        contentContainer.setVisibility(View.GONE);
        successContainer.setVisibility(View.VISIBLE);

        tvSuccessMessage.setText("File Sent Successfully");
        tvCompletedFileName.setText(tvFileName.getText());
        tvCompletedSize.setText(tvFileSize.getText() + " transferred");
    }

    // Cancels the ongoing transfer (implementation required)
    private void cancelTransfer() {
        // TODO: Add cancellation of transfer socket / streams here
        Toast.makeText(this, "Transfer cancelled", Toast.LENGTH_SHORT).show();
    }

    // Helper to format file sizes as human-readable strings
    private String formatFileSize(long sizeInBytes) {
        if (sizeInBytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(sizeInBytes) / Math.log10(1024));
        return String.format("%.1f %s",
                sizeInBytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}
