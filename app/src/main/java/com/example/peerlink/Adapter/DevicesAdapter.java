package com.example.peerlink.Adapter;

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

    public DevicesAdapter(List<WifiP2pDevice> devices) {
        this.devices = devices;
    }

    // Listener interface for connect button click events
    public interface OnDeviceConnectListener {
        void onDeviceConnectClicked(WifiP2pDevice device);
    }

    private OnDeviceConnectListener connectListener;

    public void setOnDeviceConnectListener(OnDeviceConnectListener listener) {
        this.connectListener = listener;
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

        // Handle click for connect button
        holder.btnConnect.setOnClickListener(v -> {
            if (connectListener != null) {
                connectListener.onDeviceConnectClicked(device);
            }
        });
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    public static class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView tvDeviceName;
        CardView btnConnect;

        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDeviceName = itemView.findViewById(R.id.tvDeviceName);
            btnConnect = itemView.findViewById(R.id.btnConnect);
        }
    }
}