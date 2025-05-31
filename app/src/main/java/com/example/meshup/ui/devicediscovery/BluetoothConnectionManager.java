package com.example.meshup.ui.devicediscovery;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * This class handles the Bluetooth connections, both as a server and client
 */
public class BluetoothConnectionManager {
    private static final String TAG = "BluetoothConnManager";
    private static final String APP_NAME = "MeshUp";
    // Use the standard SerialPortServiceClass UUID for Bluetooth communication
    private static final UUID APP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter bluetoothAdapter;
    private final Context context;
    private final Handler handler;

    private AcceptThread acceptThread;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;

    private BluetoothConnectionListener connectionListener;
    private BluetoothDevice connectedDevice;
    private volatile ConnectionState connectionState = ConnectionState.NONE;

    // Connection states
    public enum ConnectionState {
        NONE,
        LISTENING,
        CONNECTING,
        CONNECTED
    }

    public interface BluetoothConnectionListener {
        void onConnectionEstablished(BluetoothDevice device);
        void onConnectionFailed(String message);
        void onDataReceived(byte[] buffer, int bytes);
        void onConnectionLost(String message);
    }

    public void setConnectionListener(BluetoothConnectionListener listener) {
        this.connectionListener = listener;
    }

    public BluetoothConnectionManager(Context context) {
        this.context = context;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Get current connection state
     */
    public synchronized ConnectionState getConnectionState() {
        return connectionState;
    }

    /**
     * Get currently connected device
     */
    public BluetoothDevice getConnectedDevice() {
        return connectedDevice;
    }

    /**
     * Start AcceptThread to begin a session in listening mode
     */
    public synchronized void startAcceptingConnections() {
        Log.d(TAG, "startAcceptingConnections, current state: " + connectionState);

        // Don't start if already connected
        if (connectionState == ConnectionState.CONNECTED) {
            Log.d(TAG, "Already connected, not starting accept thread");
            return;
        }

        // Cancel any existing connecting thread
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // Start the AcceptThread if not already running
        if (acceptThread == null) {
            acceptThread = new AcceptThread();
            acceptThread.start();
            connectionState = ConnectionState.LISTENING;
            Log.d(TAG, "AcceptThread started");
        }
    }

    /**
     * Initiate a connection to a remote device
     * @param device The BluetoothDevice to connect
     */
    public synchronized void connectToDevice(BluetoothDevice device) {
        Log.d(TAG, "connectToDevice: " + device.getAddress() + ", current state: " + connectionState);

        // Don't attempt connection if already connected to this device
        if (connectionState == ConnectionState.CONNECTED &&
                connectedDevice != null &&
                connectedDevice.getAddress().equals(device.getAddress())) {
            Log.d(TAG, "Already connected to this device");
            return;
        }

        // Check permissions before proceeding
        if (!checkBluetoothPermissions()) {
            return;
        }

        // Cancel discovery as it will slow down the connection
        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
                Log.d(TAG, "Discovery canceled for connection");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception when canceling discovery", e);
            notifyConnectionFailed("Permission denied: " + e.getMessage());
            return;
        }

        // Cancel any existing threads
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // If already connected, disconnect first
        if (connectionState == ConnectionState.CONNECTED) {
            disconnect();
        }

        // Cancel accept thread during connection attempt
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        // Start the thread to connect with the given device
        connectThread = new ConnectThread(device);
        connectThread.start();
        connectionState = ConnectionState.CONNECTING;
    }

    /**
     * Send data to the connected device
     * @param data Bytes to send
     * @return true if the data was sent, false otherwise
     */
    public boolean write(byte[] data) {
        ConnectedThread thread;
        synchronized (this) {
            if (connectionState != ConnectionState.CONNECTED) {
                Log.e(TAG, "Cannot write data: not connected");
                notifyConnectionFailed("Not connected to any device");
                return false;
            }
            thread = connectedThread;
        }

        if (thread != null) {
            thread.write(data);
            return true;
        } else {
            Log.e(TAG, "Cannot write data: connected thread is null");
            notifyConnectionFailed("Connection thread not available");
            return false;
        }
    }

    /**
     * Disconnect from current device
     */
    public synchronized void disconnect() {
        Log.d(TAG, "Disconnecting from current device");

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        connectionState = ConnectionState.NONE;
        connectedDevice = null;

        // Restart listening for new connections
        startAcceptingConnections();
    }

    /**
     * Stop all connections and threads
     */
    public synchronized void stop() {
        Log.d(TAG, "Stopping all connections");

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        connectionState = ConnectionState.NONE;
        connectedDevice = null;
    }

    private boolean checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                notifyConnectionFailed("Bluetooth connect permission not granted");
                return false;
            }
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                notifyConnectionFailed("Bluetooth scan permission not granted");
                return false;
            }
        }
        return true;
    }

    private void notifyConnectionFailed(String message) {
        final BluetoothConnectionListener listener = connectionListener; // Create final copy
        if (listener != null) {
            handler.post(() -> listener.onConnectionFailed(message));
        }
    }

    private void notifyConnectionLost(String message) {
        final BluetoothConnectionListener listener = connectionListener; // Create final copy
        if (listener != null) {
            handler.post(() -> listener.onConnectionLost(message));
        }
    }

    /**
     * Handle successful connection
     */
    private synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        Log.d(TAG, "connected: " + device.getAddress());

        if (socket == null) {
            Log.e(TAG, "Cannot connect: socket is null");
            notifyConnectionFailed("Connection failed: socket is null");
            connectionState = ConnectionState.NONE;
            return;
        }

        // Cancel any connection threads
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        // Stop accepting new connections since we're now connected
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        connectedThread = new ConnectedThread(socket);
        connectedThread.start();

        connectedDevice = device;
        connectionState = ConnectionState.CONNECTED;

        // Notify the UI about the established connection
        final BluetoothConnectionListener listener = connectionListener; // Create final copy
        if (listener != null) {
            handler.post(() -> listener.onConnectionEstablished(device));
        }
    }


    /**
     * Handle connection failures
     */
    private synchronized void connectionFailed(String reason) {
        Log.e(TAG, "Connection failed: " + reason);
        connectionState = ConnectionState.NONE;
        connectedDevice = null;

        // Restart accept thread to listen for new connections
        startAcceptingConnections();

        // Notify listeners
        notifyConnectionFailed(reason);
    }

    /**
     * Handle connection lost
     */
    private synchronized void connectionLost(String reason) {
        Log.e(TAG, "Connection lost: " + reason);
        connectionState = ConnectionState.NONE;
        connectedDevice = null;

        // Restart accept thread to listen for new connections
        startAcceptingConnections();

        // Notify listeners
        notifyConnectionLost("Connection lost: " + reason);
    }

    /**
     * Server-side connection acceptor thread
     */
    private class AcceptThread extends Thread {
        private BluetoothServerSocket serverSocket = null;
        private volatile boolean shouldRun = true;

        public AcceptThread() {
            setName("AcceptThread");
            // Initialize server socket with proper permission checks
            try {
                if (!checkBluetoothPermissions()) {
                    return;
                }

                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID);
                Log.d(TAG, "Server socket created successfully");
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception during socket creation", e);
                notifyConnectionFailed("Permission denied: " + e.getMessage());
            } catch (IOException e) {
                Log.e(TAG, "Socket's listen() method failed", e);
                notifyConnectionFailed("Failed to start server socket: " + e.getMessage());
            }
        }

        public void run() {
            Log.d(TAG, "BEGIN AcceptThread " + this);
            BluetoothSocket socket = null;

            // Check if server socket was initialized successfully
            if (serverSocket == null) {
                Log.e(TAG, "ServerSocket was not initialized, cannot accept connections");
                notifyConnectionFailed("Server socket initialization failed");
                return;
            }

            // Keep listening until cancelled or connected
            while (shouldRun && connectionState != ConnectionState.CONNECTED) {
                try {
                    Log.d(TAG, "Waiting for connections...");
                    socket = serverSocket.accept();
                    Log.d(TAG, "Connection accepted from: " + socket.getRemoteDevice().getAddress());
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception during socket accept", e);
                    notifyConnectionFailed("Permission denied: " + e.getMessage());
                    break;
                } catch (IOException e) {
                    if (shouldRun) {
                        Log.e(TAG, "Socket's accept() method failed", e);
                    } else {
                        Log.d(TAG, "AcceptThread cancelled");
                    }
                    break;
                }

                if (socket != null && shouldRun) {
                    try {
                        BluetoothDevice remoteDevice = socket.getRemoteDevice();
                        Log.d(TAG, "Incoming connection from: " + remoteDevice.getAddress());
                        connected(socket, remoteDevice);
                        break; // Exit after successful connection
                    } catch (SecurityException e) {
                        Log.e(TAG, "Security exception when getting remote device", e);
                        try {
                            socket.close();
                        } catch (IOException closeException) {
                            Log.e(TAG, "Could not close the socket", closeException);
                        }
                        notifyConnectionFailed("Permission denied: " + e.getMessage());
                    }
                }
            }
            Log.i(TAG, "END AcceptThread");
        }

        public void cancel() {
            Log.d(TAG, "Canceling AcceptThread");
            shouldRun = false;
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not close the server socket", e);
            }
        }
    }

    /**
     * Client-side connection initiator thread
     */
    private class ConnectThread extends Thread {
        private BluetoothSocket socket = null;
        private final BluetoothDevice device;
        private volatile boolean shouldRun = true;

        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            setName("ConnectThread");

            try {
                if (!checkBluetoothPermissions()) {
                    return;
                }

                // First try standard socket
                socket = device.createRfcommSocketToServiceRecord(APP_UUID);
                Log.d(TAG, "Socket created for device: " + device.getAddress());
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception during socket creation", e);
                notifyConnectionFailed("Permission denied: " + e.getMessage());
            } catch (IOException e) {
                Log.e(TAG, "Socket's create() method failed", e);
                notifyConnectionFailed("Failed to create socket: " + e.getMessage());
            }
        }

        public void run() {
            Log.d(TAG, "BEGIN ConnectThread " + this);

            // Check if socket was initialized successfully
            if (socket == null) {
                Log.e(TAG, "Socket initialization failed");
                connectionFailed("Socket initialization failed");
                return;
            }

            try {
                Log.d(TAG, "Attempting to connect to " + device.getAddress());
                socket.connect();
                Log.d(TAG, "Connection successful to " + device.getAddress());
            } catch (IOException connectException) {
                Log.w(TAG, "Standard connection failed, trying fallback method: " + connectException.getMessage());

                // Try fallback connection method
                try {
                    socket.close();
                    // Use reflection to get fallback socket (for older devices)
                    socket = (BluetoothSocket) device.getClass()
                            .getMethod("createRfcommSocket", new Class[]{int.class})
                            .invoke(device, 1);
                    socket.connect();
                    Log.d(TAG, "Fallback connection successful");
                } catch (Exception fallbackException) {
                    Log.e(TAG, "Both connection attempts failed", fallbackException);
                    try {
                        socket.close();
                    } catch (IOException closeException) {
                        Log.e(TAG, "Could not close the client socket", closeException);
                    }
                    connectionFailed("Failed to connect: " + fallbackException.getMessage());
                    return;
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception during socket connection", e);
                try {
                    socket.close();
                } catch (IOException closeException) {
                    Log.e(TAG, "Could not close the client socket", closeException);
                }
                connectionFailed("Permission denied: " + e.getMessage());
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothConnectionManager.this) {
                connectThread = null;
            }

            // Start managing the connection
            if (shouldRun) {
                connected(socket, device);
            }
        }

        public void cancel() {
            Log.d(TAG, "Canceling ConnectThread");
            shouldRun = false;
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not close the client socket", e);
            }
        }
    }

    /**
     * Thread for managing an established connection and sending/receiving data
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream inputStream;
        private final OutputStream outputStream;
        private volatile boolean shouldRun = true;

        public ConnectedThread(BluetoothSocket socket) {
            Log.d(TAG, "Creating ConnectedThread");
            this.socket = socket;
            setName("ConnectedThread");

            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
                Log.d(TAG, "Input and output streams obtained");
            } catch (IOException e) {
                Log.e(TAG, "Error creating input/output streams", e);
                notifyConnectionFailed("Failed to create streams: " + e.getMessage());
            }

            inputStream = tmpIn;
            outputStream = tmpOut;
        }

        public void run() {
            Log.d(TAG, "BEGIN ConnectedThread " + this);

            // Check if streams are initialized
            if (inputStream == null || outputStream == null) {
                Log.e(TAG, "Input/output streams not initialized");
                connectionLost("Connection streams not initialized");
                return;
            }

            byte[] buffer = new byte[1024];
            int bytes;

            // Keep listening to the InputStream while connected
            while (shouldRun && connectionState == ConnectionState.CONNECTED) {
                try {
                    // Read from the InputStream
                    bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        Log.d(TAG, "Received " + bytes + " bytes");

                        // Send the obtained bytes to the UI activity
                        final BluetoothConnectionListener listener = connectionListener; // Create final copy
                        if (listener != null) {
                            final byte[] receivedData = new byte[bytes];
                            System.arraycopy(buffer, 0, receivedData, 0, bytes);
                            final int finalBytes = bytes; // Make bytes final for lambda
                            handler.post(() -> listener.onDataReceived(receivedData, finalBytes));
                        }
                    }
                } catch (IOException e) {
                    if (shouldRun) {
                        Log.e(TAG, "Connection lost during read", e);
                        connectionLost("Connection lost: " + e.getMessage());
                    } else {
                        Log.d(TAG, "ConnectedThread cancelled");
                    }
                    break;
                }
            }
            Log.i(TAG, "END ConnectedThread");
        }

        /**
         * Write to the connected OutStream
         * @param buffer The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                if (outputStream == null) {
                    Log.e(TAG, "Output stream is null");
                    notifyConnectionFailed("Output stream is null");
                    return;
                }

                Log.d(TAG, "Writing " + buffer.length + " bytes");
                outputStream.write(buffer);
                outputStream.flush();
                Log.d(TAG, "Data sent successfully");
            } catch (IOException e) {
                Log.e(TAG, "Error writing to output stream", e);
                notifyConnectionFailed("Write failed: " + e.getMessage());
                connectionLost("Write failed: " + e.getMessage());
            }
        }

        public void cancel() {
            Log.d(TAG, "Canceling ConnectedThread");
            shouldRun = false;
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not close the connected socket", e);
            }
        }
    }
}