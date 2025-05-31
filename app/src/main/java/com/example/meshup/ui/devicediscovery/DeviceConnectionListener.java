package com.example.meshup.ui.devicediscovery;

import android.bluetooth.BluetoothDevice;

/**
 * Interface to handle Bluetooth device selection and connection events
 */
public interface DeviceConnectionListener {
    /**
     * Called when a Bluetooth device is selected from the list
     * @param device The selected BluetoothDevice
     */
    void onDeviceSelected(BluetoothDevice device);

    /**
     * Called when a Bluetooth connection is established
     * @param device The connected BluetoothDevice
     */
    default void onDeviceConnected(BluetoothDevice device) {
        // Default implementation calls onDeviceSelected for backward compatibility
        onDeviceSelected(device);
    }

    /**
     * Called when a Bluetooth connection fails
     * @param device The BluetoothDevice that failed to connect
     * @param errorMessage The error message
     */
    default void onConnectionFailed(BluetoothDevice device, String errorMessage) {
        // Default empty implementation
    }

    /**
     * Called when a Bluetooth connection is lost
     * @param device The BluetoothDevice that lost connection
     * @param errorMessage The error message
     */
    default void onConnectionLost(BluetoothDevice device, String errorMessage) {
        // Default empty implementation
    }

    /**
     * Called when data is received from a connected device
     * @param device The BluetoothDevice that sent the data
     * @param data The received data
     * @param length The length of the received data
     */
    default void onDataReceived(BluetoothDevice device, byte[] data, int length) {
        // Default empty implementation
    }
}