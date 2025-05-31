package com.example.meshup.ui.devicediscovery;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.meshup.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DeviceDiscoveryFragment extends Fragment implements
        DeviceListAdapter.DeviceClickListener,
        BluetoothConnectionManager.BluetoothConnectionListener {

    private static final String TAG = "DeviceDiscoveryFrag";
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_BLUETOOTH_SCAN_PERMISSION = 2;
    private static final int REQUEST_BLUETOOTH_CONNECT_PERMISSION = 3;
    private static final int DISCOVERY_TIMEOUT = 30000; // 30 seconds

    private RecyclerView recyclerViewPairedDevices;
    private RecyclerView recyclerViewDiscoveredDevices;
    private TextView textViewPairedDevicesTitle;
    private TextView textViewDiscoveredDevicesTitle;
    private Button buttonScan;
    private ProgressBar progressBarScanning;
    private TextView textViewScanningStatus;
    private TextView textViewConnectionStatus;
    private BluetoothDevice connectedDevice;
    private BluetoothDevice lastAttemptedDevice;
    private BluetoothAdapter bluetoothAdapter;
    private DeviceListAdapter pairedDevicesAdapter;
    private DeviceListAdapter discoveredDevicesAdapter;
    private List<BluetoothDevice> pairedDevicesList;
    private List<BluetoothDevice> discoveredDevicesList;
    private DeviceConnectionListener connectionListener;
    private BluetoothConnectionManager connectionManager;
    private Handler timeoutHandler;
    private boolean isScanning = false;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        try {
            connectionListener = (DeviceConnectionListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context + " must implement DeviceConnectionListener");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_device_discovery, container, false);
        initializeViews(view);
        initializeBluetoothAdapter();
        setupRecyclerViews();
        setupListeners();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        registerBroadcastReceivers();
        checkBluetoothEnabled();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterBroadcastReceivers();
        cancelDiscovery();
        if (timeoutHandler != null) {
            timeoutHandler.removeCallbacksAndMessages(null);
        }
    }

    private void initializeViews(View view) {
        recyclerViewPairedDevices = view.findViewById(R.id.recyclerViewPairedDevices);
        recyclerViewDiscoveredDevices = view.findViewById(R.id.recyclerViewDiscoveredDevices);
        textViewPairedDevicesTitle = view.findViewById(R.id.textViewPairedDevicesTitle);
        textViewDiscoveredDevicesTitle = view.findViewById(R.id.textViewDiscoveredDevicesTitle);
        buttonScan = view.findViewById(R.id.buttonScan);
        progressBarScanning = view.findViewById(R.id.progressBarScanning);
        textViewScanningStatus = view.findViewById(R.id.textViewScanningStatus);

        // Add connection status TextView if it doesn't exist in your layout
        // You'll need to add this to your fragment_device_discovery.xml
        textViewConnectionStatus = view.findViewById(R.id.textViewConnectionStatus);
        if (textViewConnectionStatus == null) {
            Log.w(TAG, "Connection status TextView not found in layout");
        }
    }

    @Override
    public void onConnectionLost(String message) {
        Log.w(TAG, "Connection lost: " + message);
        showStatus("Connection lost: " + message);

        // Optionally notify the activity about connection loss
        if (connectionListener != null && connectedDevice != null) {
            connectionListener.onConnectionLost(connectedDevice, message);
        }
    }

    private void initializeBluetoothAdapter() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(getContext(), "Bluetooth is not supported on this device", Toast.LENGTH_SHORT).show();
            return;
        }

        connectionManager = new BluetoothConnectionManager(getContext());
        connectionManager.setConnectionListener(this);
        pairedDevicesList = new ArrayList<>();
        discoveredDevicesList = new ArrayList<>();
        timeoutHandler = new Handler(Looper.getMainLooper());
    }

    private void setupRecyclerViews() {
        // Setup paired devices RecyclerView
        pairedDevicesAdapter = new DeviceListAdapter(pairedDevicesList, this);
        recyclerViewPairedDevices.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerViewPairedDevices.setAdapter(pairedDevicesAdapter);

        // Setup discovered devices RecyclerView
        discoveredDevicesAdapter = new DeviceListAdapter(discoveredDevicesList, this);
        recyclerViewDiscoveredDevices.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerViewDiscoveredDevices.setAdapter(discoveredDevicesAdapter);
    }

    private void setupListeners() {
        buttonScan.setOnClickListener(v -> {
            if (isScanning) {
                cancelDiscovery();
            } else {
                startDeviceDiscovery();
            }
        });
    }

    private void checkBluetoothEnabled() {
        if (bluetoothAdapter == null) {
            showStatus("Bluetooth not supported on this device");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            // Request to enable Bluetooth
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            try {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception when enabling Bluetooth", e);
                showStatus("Permission denied: " + e.getMessage());
                requestBluetoothPermissions();
            }
        } else {
            loadPairedDevices();
            // Start server mode to accept incoming connections
            connectionManager.startAcceptingConnections();
        }
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    REQUEST_BLUETOOTH_CONNECT_PERMISSION
            );
        }
    }

    private void loadPairedDevices() {
        pairedDevicesList.clear();

        if (bluetoothAdapter != null) {
            // Check for BLUETOOTH_CONNECT permission for Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                            REQUEST_BLUETOOTH_CONNECT_PERMISSION);
                    return;
                }
            }

            try {
                Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
                if (pairedDevices.size() > 0) {
                    Log.d(TAG, "Found " + pairedDevices.size() + " paired devices");
                    pairedDevicesList.addAll(pairedDevices);
                    textViewPairedDevicesTitle.setVisibility(View.VISIBLE);
                    recyclerViewPairedDevices.setVisibility(View.VISIBLE);
                } else {
                    Log.d(TAG, "No paired devices found");
                    textViewPairedDevicesTitle.setVisibility(View.GONE);
                    recyclerViewPairedDevices.setVisibility(View.GONE);
                }
                pairedDevicesAdapter.notifyDataSetChanged();
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception when getting bonded devices", e);
                showStatus("Permission denied: " + e.getMessage());
            }
        }
    }

    private void startDeviceDiscovery() {
        if (bluetoothAdapter == null) {
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            checkBluetoothEnabled();
            return;
        }

        // Check for required permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN},
                        REQUEST_BLUETOOTH_SCAN_PERMISSION);
                return;
            }
        } else if (ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_BLUETOOTH_SCAN_PERMISSION);
            return;
        }

        Log.d(TAG, "Starting device discovery");
        // Cancel any ongoing discovery
        cancelDiscovery();

        // Clear previous list of discovered devices
        discoveredDevicesList.clear();
        discoveredDevicesAdapter.notifyDataSetChanged();

        // Show scanning indicators
        progressBarScanning.setVisibility(View.VISIBLE);
        textViewScanningStatus.setVisibility(View.VISIBLE);
        textViewScanningStatus.setText(R.string.scanning_for_devices);
        buttonScan.setText(R.string.stop_scanning);
        isScanning = true;

        try {
            // Start discovery
            boolean started = bluetoothAdapter.startDiscovery();
            Log.d(TAG, "Discovery started: " + started);
            if (started) {
                textViewDiscoveredDevicesTitle.setVisibility(View.VISIBLE);
                recyclerViewDiscoveredDevices.setVisibility(View.VISIBLE);

                // Set a timeout for discovery
                timeoutHandler.postDelayed(() -> {
                    if (isScanning && bluetoothAdapter.isDiscovering()) {
                        Log.d(TAG, "Discovery timeout reached");
                        cancelDiscovery();
                        showStatus("Discovery timeout reached");
                    }
                }, DISCOVERY_TIMEOUT);
            } else {
                showStatus("Failed to start discovery");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception when starting discovery", e);
            showStatus("Permission denied: " + e.getMessage());
        }
    }

    private void cancelDiscovery() {
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(requireContext(),
                            Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                        Log.d(TAG, "Canceling discovery");
                        bluetoothAdapter.cancelDiscovery();
                    }
                } else {
                    Log.d(TAG, "Canceling discovery");
                    bluetoothAdapter.cancelDiscovery();
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception when canceling discovery", e);
            }
        }

        // Reset scanning indicators
        progressBarScanning.setVisibility(View.GONE);
        textViewScanningStatus.setVisibility(View.GONE);
        buttonScan.setText(R.string.scan_for_devices);
        isScanning = false;
        timeoutHandler.removeCallbacksAndMessages(null);
    }

    private void registerBroadcastReceivers() {
        // Register for broadcasts when a device is discovered
        IntentFilter discoveryFilter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        requireActivity().registerReceiver(discoveryBroadcastReceiver, discoveryFilter);

        // Register for broadcasts when discovery has finished
        IntentFilter discoveryFinishedFilter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        requireActivity().registerReceiver(discoveryFinishedBroadcastReceiver, discoveryFinishedFilter);

        // Register for broadcasts when bluetooth state changes
        IntentFilter btStateFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        requireActivity().registerReceiver(bluetoothStateBroadcastReceiver, btStateFilter);
    }

    private void unregisterBroadcastReceivers() {
        try {
            requireActivity().unregisterReceiver(discoveryBroadcastReceiver);
            requireActivity().unregisterReceiver(discoveryFinishedBroadcastReceiver);
            requireActivity().unregisterReceiver(bluetoothStateBroadcastReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered
            Log.w(TAG, "Error unregistering receiver", e);
        }
    }

    // BroadcastReceiver for ACTION_FOUND
    private final BroadcastReceiver discoveryBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Get the BluetoothDevice object from the Intent
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ActivityCompat.checkSelfPermission(requireContext(),
                                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                return;
                            }
                        }

                        String deviceName = device.getName();
                        String deviceAddress = device.getAddress();
                        Log.d(TAG, "Device found: " + (deviceName != null ? deviceName : "Unknown") +
                                " - " + deviceAddress);

                        // Check if the device is already in the list
                        boolean isInList = false;
                        for (BluetoothDevice d : discoveredDevicesList) {
                            if (d.getAddress().equals(device.getAddress())) {
                                isInList = true;
                                break;
                            }
                        }

                        for (BluetoothDevice d : pairedDevicesList) {
                            if (d.getAddress().equals(device.getAddress())) {
                                isInList = true;
                                break;
                            }
                        }

                        if (!isInList) {
                            discoveredDevicesList.add(device);
                            discoveredDevicesAdapter.notifyDataSetChanged();
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "Security exception when processing discovered device", e);
                    }
                }
            }
        }
    };

    // BroadcastReceiver for ACTION_DISCOVERY_FINISHED
    private final BroadcastReceiver discoveryFinishedBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.d(TAG, "Discovery finished");
                progressBarScanning.setVisibility(View.GONE);
                textViewScanningStatus.setText(R.string.scanning_complete);
                buttonScan.setText(R.string.scan_for_devices);
                isScanning = false;

                // Show message if no devices found
                if (discoveredDevicesList.isEmpty()) {
                    textViewScanningStatus.setText(R.string.no_devices_found);
                    textViewScanningStatus.setVisibility(View.VISIBLE);
                    Log.d(TAG, "No devices found during discovery");
                } else {
                    textViewScanningStatus.setVisibility(View.GONE);
                    Log.d(TAG, "Found " + discoveredDevicesList.size() + " devices during discovery");
                }
            }
        }
    };

    // BroadcastReceiver for ACTION_STATE_CHANGED
    private final BroadcastReceiver bluetoothStateBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        Log.d(TAG, "Bluetooth turned off");
                        showStatus("Bluetooth is turned off");
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        Log.d(TAG, "Bluetooth turning off");
                        cancelDiscovery();
                        break;
                    case BluetoothAdapter.STATE_ON:
                        Log.d(TAG, "Bluetooth turned on");
                        loadPairedDevices();
                        // Start server mode
                        connectionManager.startAcceptingConnections();
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        Log.d(TAG, "Bluetooth turning on");
                        break;
                }
            }
        }
    };

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == -1) { // RESULT_OK
                Log.d(TAG, "Bluetooth enabled by user");
                loadPairedDevices();
                // Start server mode
                connectionManager.startAcceptingConnections();
            } else {
                Log.d(TAG, "Bluetooth enabling rejected by user");
                showStatus("Bluetooth must be enabled to use this feature");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_BLUETOOTH_SCAN_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Bluetooth scan permission granted");
                startDeviceDiscovery();
            } else {
                Log.d(TAG, "Bluetooth scan permission denied");
                Toast.makeText(getContext(), "Bluetooth scan permission denied", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_BLUETOOTH_CONNECT_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Bluetooth connect permission granted");
                loadPairedDevices();
                // Start server mode
                connectionManager.startAcceptingConnections();
            } else {
                Log.d(TAG, "Bluetooth connect permission denied");
                Toast.makeText(getContext(), "Bluetooth connect permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onDeviceClick(BluetoothDevice device) {
        // Cancel discovery because it's resource intensive
        cancelDiscovery();

        lastAttemptedDevice = device; // Track the device being connected to

        try {
            // Check for BLUETOOTH_CONNECT permission for Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(getContext(), "Bluetooth connect permission required", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            String deviceName = device.getName();
            Log.d(TAG, "Connecting to device: " + (deviceName != null ? deviceName : "Unknown") +
                    " (" + device.getAddress() + ")");

            showStatus("Connecting to " + (deviceName != null ? deviceName : "Unknown device"));

            // Initiate connection to the selected device
            connectionManager.connectToDevice(device);

            // Notify activity about device selection
            if (connectionListener != null) {
                connectionListener.onDeviceSelected(device);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception when connecting to device", e);
            showStatus("Permission denied: " + e.getMessage());
        }
    }

    // Implement BluetoothConnectionListener methods
    @Override
    public void onConnectionEstablished(BluetoothDevice device) {
        try {
            String deviceName = device.getName();
            Log.d(TAG, "Connection established with: " +
                    (deviceName != null ? deviceName : "Unknown") +
                    " (" + device.getAddress() + ")");

            showStatus("Connected to " + (deviceName != null ? deviceName : "Unknown device"));

            // Notify activity about successful connection using the new method
            if (connectionListener != null) {
                connectionListener.onDeviceConnected(device);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception when accessing device info", e);
        }
    }
    @Override
    public void onConnectionFailed(String message) {
        Log.e(TAG, "Connection failed: " + message);
        showStatus("Connection failed: " + message);

        // Optionally notify the activity about connection failure
        // You'll need to track which device was being connected to
        if (connectionListener != null && lastAttemptedDevice != null) {
            connectionListener.onConnectionFailed(lastAttemptedDevice, message);
        }
    }

    @Override
    public void onDataReceived(byte[] buffer, int bytes) {
        // Handle received data
        // For now, we'll just log it
        Log.d(TAG, "Received " + bytes + " bytes of data");
    }

    private void showStatus(String status) {
        Log.d(TAG, status);
        if (textViewConnectionStatus != null) {
            textViewConnectionStatus.setText(status);
            textViewConnectionStatus.setVisibility(View.VISIBLE);
        }

        // Also show as toast for visibility
        if (getContext() != null) {
            Toast.makeText(getContext(), status, Toast.LENGTH_SHORT).show();
        }
    }
}