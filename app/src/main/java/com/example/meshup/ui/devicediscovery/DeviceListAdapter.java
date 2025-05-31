package com.example.meshup.ui.devicediscovery;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.meshup.R;

import java.util.List;

public class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder> {

    private static final String TAG = "DeviceListAdapter";
    private List<BluetoothDevice> deviceList;
    private DeviceClickListener listener;

    public interface DeviceClickListener {
        void onDeviceClick(BluetoothDevice device);
    }

    public DeviceListAdapter(List<BluetoothDevice> deviceList, DeviceClickListener listener) {
        this.deviceList = deviceList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bluetooth_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        BluetoothDevice device = deviceList.get(position);
        holder.bind(device, listener);
    }

    @Override
    public int getItemCount() {
        return deviceList != null ? deviceList.size() : 0;
    }

    /**
     * Updates the device list and refreshes the adapter
     * @param devices new list of devices
     */
    public void updateDevices(List<BluetoothDevice> devices) {
        this.deviceList = devices;
        notifyDataSetChanged();
    }

    /**
     * Add a device to the list if it doesn't already exist
     * @param device device to add
     * @return true if device was added, false if it already existed
     */
    public boolean addDevice(BluetoothDevice device) {
        if (device == null) return false;

        // Check if device already exists in the list
        for (BluetoothDevice existingDevice : deviceList) {
            if (existingDevice.getAddress().equals(device.getAddress())) {
                return false; // Device already exists
            }
        }

        // Add the device and notify adapter
        deviceList.add(device);
        notifyItemInserted(deviceList.size() - 1);
        return true;
    }

    /**
     * Clear all devices from the list
     */
    public void clearDevices() {
        int size = deviceList.size();
        deviceList.clear();
        notifyItemRangeRemoved(0, size);
    }

    static class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView deviceName;
        TextView deviceAddress;

        DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(R.id.textViewDeviceName);
            deviceAddress = itemView.findViewById(R.id.textViewDeviceAddress);
        }

        void bind(final BluetoothDevice device, final DeviceClickListener listener) {
            if (device == null) {
                deviceName.setText(R.string.unknown_device);
                deviceAddress.setText(device.getAddress());
                return;
            }

            // Handle permission check for Android 12+
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(itemView.getContext(),
                            Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        deviceName.setText(R.string.unknown_device);
                        deviceAddress.setText(device.getAddress());
                        return;
                    }
                }

                String name = device.getName();
                if (name == null || name.isEmpty()) {
                    deviceName.setText(R.string.unknown_device);
                } else {
                    deviceName.setText(name);
                }
                deviceAddress.setText(device.getAddress());
            } catch (SecurityException e) {
                // Handle security exception
                deviceName.setText(R.string.unknown_device);
                deviceAddress.setText(device.getAddress());
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeviceClick(device);
                }
            });
        }
    }
}