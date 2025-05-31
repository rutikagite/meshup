package com.example.meshup.ui.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.meshup.R;
import com.example.meshup.ui.data.ConnectedDevice;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ConnectedDevicesAdapter extends RecyclerView.Adapter<ConnectedDevicesAdapter.DeviceViewHolder> {

    private List<ConnectedDevice> devices;
    private OnDeviceClickListener listener;
    private ConnectedDevice selectedDevice;

    public interface OnDeviceClickListener {
        void onDeviceClick(ConnectedDevice device);
    }

    public ConnectedDevicesAdapter(List<ConnectedDevice> devices, OnDeviceClickListener listener) {
        this.devices = devices;
        this.listener = listener;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_connected_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        ConnectedDevice device = devices.get(position);
        holder.bind(device, device.equals(selectedDevice));
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    public void setSelectedDevice(ConnectedDevice device) {
        ConnectedDevice previousSelected = selectedDevice;
        selectedDevice = device;

        // Notify changes for previous and current selection
        if (previousSelected != null) {
            int prevIndex = devices.indexOf(previousSelected);
            if (prevIndex != -1) {
                notifyItemChanged(prevIndex);
            }
        }

        int currentIndex = devices.indexOf(device);
        if (currentIndex != -1) {
            notifyItemChanged(currentIndex);
        }
    }

    class DeviceViewHolder extends RecyclerView.ViewHolder {
        private ImageView imageViewProfile;
        private TextView textViewUsername;
        private TextView textViewDeviceName;
        private TextView textViewLastSeen;
        private View statusIndicator;
        private View itemContainer;

        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            imageViewProfile = itemView.findViewById(R.id.imageView_profile);
            textViewUsername = itemView.findViewById(R.id.textView_username);
            textViewDeviceName = itemView.findViewById(R.id.textView_device_name);
            textViewLastSeen = itemView.findViewById(R.id.textView_last_seen);
            statusIndicator = itemView.findViewById(R.id.status_indicator);
            itemContainer = itemView.findViewById(R.id.item_container);

            itemView.setOnClickListener(v -> {
                if (listener != null && getAdapterPosition() != RecyclerView.NO_POSITION) {
                    listener.onDeviceClick(devices.get(getAdapterPosition()));
                }
            });
        }

        public void bind(ConnectedDevice device, boolean isSelected) {
            textViewUsername.setText(device.getUsername());
            textViewDeviceName.setText(device.getDeviceName());

            // Set profile picture
            if (device.getProfilePicture() != 0) {
                imageViewProfile.setImageResource(device.getProfilePicture());
            } else {
                imageViewProfile.setImageResource(android.R.drawable.ic_menu_myplaces);
            }

            // Set online status
            if (device.isOnline()) {
                statusIndicator.setBackgroundResource(R.color.status_online);
                textViewLastSeen.setText("Online");
                textViewLastSeen.setTextColor(itemView.getContext().getColor(R.color.status_online));
            } else {
                statusIndicator.setBackgroundResource(R.color.status_offline);
                String lastSeenTime = formatLastSeen(device.getLastSeen());
                textViewLastSeen.setText("Last seen " + lastSeenTime);
                textViewLastSeen.setTextColor(itemView.getContext().getColor(R.color.text_secondary));
            }

            // Set selection state
            if (isSelected) {
                itemContainer.setBackgroundResource(R.color.item_selected);
            } else {
                itemContainer.setBackgroundResource(R.color.item_background);
            }
        }

        private String formatLastSeen(long timestamp) {
            long currentTime = System.currentTimeMillis();
            long timeDiff = currentTime - timestamp;

            if (timeDiff < 60000) { // Less than 1 minute
                return "just now";
            } else if (timeDiff < 3600000) { // Less than 1 hour
                int minutes = (int) (timeDiff / 60000);
                return minutes + "m ago";
            } else if (timeDiff < 86400000) { // Less than 1 day
                int hours = (int) (timeDiff / 3600000);
                return hours + "h ago";
            } else {
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd", Locale.getDefault());
                return sdf.format(new Date(timestamp));
            }
        }
    }
}