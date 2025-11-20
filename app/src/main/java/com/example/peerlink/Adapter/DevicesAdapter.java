package com.example.peerlink.Adapter;

import android.graphics.Color;
import android.net.wifi.p2p.WifiP2pDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.example.peerlink.R;

import java.util.List;

public class DevicesAdapter extends RecyclerView.Adapter<DevicesAdapter.DeviceViewHolder> {

    private List<WifiP2pDevice> devices;

    // Listener interface for connect button click events
    public interface OnDeviceConnectListener {
        void onDeviceConnectClicked(WifiP2pDevice device);
    }

    // Listener interface for disconnect button click events
    public interface OnDeviceDisconnectListener {
        void onDeviceDisconnectClicked(WifiP2pDevice device);
    }

    private OnDeviceConnectListener connectListener;
    private OnDeviceDisconnectListener disconnectListener;

    public void setOnDeviceConnectListener(OnDeviceConnectListener listener) {
        this.connectListener = listener;
    }

    public void setOnDeviceDisconnectListener(OnDeviceDisconnectListener listener) {
        this.disconnectListener = listener;
    }

    public DevicesAdapter(List<WifiP2pDevice> devices) {
        this.devices = devices;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        WifiP2pDevice device = devices.get(position);

        holder.tvDeviceName.setText(device.deviceName);

        // Update UI based on connection status
        if (device.status == WifiP2pDevice.CONNECTED) {
            // Device is connected - show "Disconnect" button
            holder.tvConnectButton.setText("Disconnect");
            holder.btnConnect.setCardBackgroundColor(Color.parseColor("#FF5252")); // Red
            holder.btnConnect.setEnabled(true);
            holder.btnConnect.setClickable(true);

            // Handle disconnect button click
            holder.btnConnect.setOnClickListener(v -> {
                if (disconnectListener != null) {
                    disconnectListener.onDeviceDisconnectClicked(device);
                }
            });

        } else if (device.status == WifiP2pDevice.INVITED) {
            // Device invitation sent - show "Connecting..." status
            holder.tvConnectButton.setText("Connecting...");
            holder.btnConnect.setCardBackgroundColor(Color.parseColor("#FF9800")); // Orange
            holder.btnConnect.setEnabled(false);
            holder.btnConnect.setClickable(false);
            holder.btnConnect.setOnClickListener(null);

        } else {
            // Device is available - show "Connect" button
            holder.tvConnectButton.setText("Connect");
            holder.btnConnect.setCardBackgroundColor(Color.parseColor("#5B9FED")); // Blue
            holder.btnConnect.setEnabled(true);
            holder.btnConnect.setClickable(true);

            // Handle connect button click
            holder.btnConnect.setOnClickListener(v -> {
                if (connectListener != null) {
                    connectListener.onDeviceConnectClicked(device);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    public static class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView tvDeviceName;
        CardView btnConnect;
        TextView tvConnectButton;

        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDeviceName = itemView.findViewById(R.id.tvDeviceName);
            btnConnect = itemView.findViewById(R.id.btnConnect);
            tvConnectButton = itemView.findViewById(R.id.tvConnectButton);
        }
    }
}