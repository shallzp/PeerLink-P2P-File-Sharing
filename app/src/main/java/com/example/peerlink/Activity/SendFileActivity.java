package com.example.peerlink.Activity;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.example.peerlink.R;

public class SendFileActivity extends AppCompatActivity {

    private static final String TAG = "SendFileActivity";

    private CardView btnSelectFile, btnStartTransfer, fileStatusCard, filePreviewCard;
    private TextView tvFileStatus, tvFileName, tvFileNamePreview, tvFileSize;
    private ImageView ivClearFile, ivFileIcon, ivFilePreview;

    private Uri selectedFileUri = null;

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

        btnSelectFile = findViewById(R.id.btnSelectFile);
        btnStartTransfer = findViewById(R.id.btnStartTransfer);
        fileStatusCard = findViewById(R.id.fileStatusCard);
        filePreviewCard = findViewById(R.id.filePreviewCard);

        tvFileStatus = findViewById(R.id.tvFileStatus);
        tvFileName = findViewById(R.id.tvFileName);
        tvFileNamePreview = findViewById(R.id.tvFileNamePreview);
        tvFileSize = findViewById(R.id.tvFileSize);

        ivClearFile = findViewById(R.id.ivClearFile);
        ivFileIcon = findViewById(R.id.ivFileIcon);
        ivFilePreview = findViewById(R.id.ivFilePreview);

        // Select File button click
        btnSelectFile.setOnClickListener(v -> openFilePicker());

        // Clear file icon click
        ivClearFile.setOnClickListener(v -> clearSelectedFile());

        // Start transfer button click
        btnStartTransfer.setOnClickListener(v -> {
            if (selectedFileUri == null) {
                Toast.makeText(this, "Please select a file first", Toast.LENGTH_SHORT).show();
                return;
            }
            startFileTransfer(selectedFileUri);
        });

        // Initial UI state
        clearSelectedFile();
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
        String name = getFileName(uri);
        long size = getFileSize(uri);

        tvFileStatus.setText("File selected");
        tvFileName.setText(name);
        tvFileName.setVisibility(View.VISIBLE);
        ivClearFile.setVisibility(View.VISIBLE);

        tvFileNamePreview.setText(name);
        tvFileSize.setText(formatFileSize(size));

        // Show preview card
        filePreviewCard.setVisibility(View.VISIBLE);

        // Update file icon if desired based on MIME type (optional)
        String mimeType = getContentResolver().getType(uri);
        updateFileIcon(mimeType);
    }

    private void clearSelectedFile() {
        selectedFileUri = null;

        tvFileStatus.setText("No file selected");
        tvFileName.setText("");
        tvFileName.setVisibility(View.GONE);
        ivClearFile.setVisibility(View.GONE);

        filePreviewCard.setVisibility(View.GONE);
    }

    // Example placeholder for starting the transfer process
    private void startFileTransfer(Uri uri) {
        // Implement your transfer start logic here
        Toast.makeText(this, "Starting transfer for file: " + getFileName(uri), Toast.LENGTH_SHORT).show();
    }

    // Helper to get file name from Uri
    private String getFileName(Uri uri) {
        String displayName = "unknown";
        ContentResolver resolver = getContentResolver();
        Cursor cursor = resolver.query(uri, null, null, null, null);
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

    // Helper to get file size in bytes from Uri
    private long getFileSize(Uri uri) {
        long size = 0;
        ContentResolver resolver = getContentResolver();
        Cursor cursor = resolver.query(uri, null, null, null, null);
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

    // Format file size for display (e.g., "2.5 MB")
    private String formatFileSize(long sizeInBytes) {
        if (sizeInBytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(sizeInBytes) / Math.log10(1024));
        return String.format("%.1f %s",
                sizeInBytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    // Optional: Update file icon based on MIME type
    private void updateFileIcon(String mimeType) {
        if (mimeType == null) {
            ivFileIcon.setImageResource(R.drawable.ic_file_empty);
            ivFilePreview.setImageResource(R.drawable.ic_file_large);
            return;
        }
        if (mimeType.startsWith("image/")) {
            ivFileIcon.setImageResource(R.drawable.ic_image);
            ivFilePreview.setImageResource(R.drawable.ic_image_large);
        } else if (mimeType.startsWith("video/")) {
            ivFileIcon.setImageResource(R.drawable.ic_video);
            ivFilePreview.setImageResource(R.drawable.ic_video_large);
        } else if (mimeType.startsWith("audio/")) {
            ivFileIcon.setImageResource(R.drawable.ic_audio);
            ivFilePreview.setImageResource(R.drawable.ic_audio_large);
        } else {
            ivFileIcon.setImageResource(R.drawable.ic_file_empty);
            ivFilePreview.setImageResource(R.drawable.ic_file_large);
        }
    }
}
