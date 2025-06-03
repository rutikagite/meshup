package com.example.meshup;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.bumptech.glide.Glide;
import com.example.meshup.databinding.ActivityMainBinding;
import com.example.meshup.ui.chat.ChatFragment;
import com.example.meshup.ui.devicediscovery.BluetoothConnectionManager;
import com.example.meshup.ui.devicediscovery.DeviceConnectionListener;
import com.example.meshup.utils.DevicePreferences;
import com.google.android.material.navigation.NavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class MainActivity extends AppCompatActivity implements
        NavigationView.OnNavigationItemSelectedListener,
        ChatFragment.ChatFragmentListener,
        BluetoothConnectionManager.BluetoothConnectionListener,
        DeviceConnectionListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_ALL_PERMISSIONS = 1001;
    private static final int REQUEST_ENABLE_BT = 1002;

    // Connection management constants
    private static final long HEARTBEAT_INTERVAL = 10000; // 10 seconds
    private static final long RECONNECTION_DELAY = 3000; // 3 seconds
    private static final int MAX_RECONNECTION_ATTEMPTS = 5;
    private static final String HEARTBEAT_MESSAGE = "PING";

    private AppBarConfiguration mAppBarConfiguration;
    private ActivityMainBinding binding;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothConnectionManager connectionManager;
    private DatabaseReference chatRoomsRef;
    private NavController navController;

    // Connection stability enhancement variables
    private Handler heartbeatHandler;
    private Handler reconnectionHandler;
    private Runnable heartbeatRunnable;
    private Runnable reconnectionRunnable;
    private BluetoothDevice lastConnectedDevice;
    private boolean isManualDisconnect = false;
    private int reconnectionAttempts = 0;
    private long lastHeartbeatSent = 0;
    private long lastDataReceived = 0;
    private boolean connectionStable = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check if user is logged in first
        if (!isUserLoggedIn()) {
            redirectToLogin();
            return;
        }

        try {
            binding = ActivityMainBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());

            setSupportActionBar(binding.appBarMain.toolbar);

            DrawerLayout drawer = binding.drawerLayout;
            NavigationView navigationView = binding.navView;

            mAppBarConfiguration = new AppBarConfiguration.Builder(
                    R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow, R.id.nav_find_devices, R.id.nav_chat)
                    .setOpenableLayout(drawer)
                    .build();

            NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.nav_host_fragment_content_main);
            if (navHostFragment != null) {
                navController = navHostFragment.getNavController();
                NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
                NavigationUI.setupWithNavController(navigationView, navController);
            } else {
                Log.e(TAG, "NavHostFragment is null - check your layout files");
                Toast.makeText(this, "Navigation setup failed", Toast.LENGTH_SHORT).show();
                return;
            }

            navigationView.setNavigationItemSelectedListener(this);

            // Initialize connection management handlers
            initializeConnectionHandlers();

            // Initialize Bluetooth connection manager with error handling
            initializeBluetoothManager();

            // Load profile with error handling
            loadProfileToDrawer();

            // Check permissions and enable Bluetooth
            checkPermissionsAndEnableBluetooth();

            // Initialize Firebase reference with error handling
            try {
                chatRoomsRef = FirebaseDatabase.getInstance().getReference("chatRooms");
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize Firebase database reference", e);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
            Toast.makeText(this, "Failed to initialize app", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /**
     * Initialize handlers for connection management
     */
    private void initializeConnectionHandlers() {
        heartbeatHandler = new Handler(Looper.getMainLooper());
        reconnectionHandler = new Handler(Looper.getMainLooper());

        // Heartbeat mechanism to keep connection alive
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (connectionManager != null &&
                        connectionManager.getConnectionState() == BluetoothConnectionManager.ConnectionState.CONNECTED) {

                    // Send heartbeat only if no recent data activity
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastDataReceived > HEARTBEAT_INTERVAL / 2) {
                        sendHeartbeat();
                    }

                    // Check for connection timeout
                    if (currentTime - lastDataReceived > HEARTBEAT_INTERVAL * 3) {
                        Log.w(TAG, "Connection appears to be dead - initiating reconnection");
                        handleConnectionTimeout();
                    }
                }

                // Schedule next heartbeat
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL);
            }
        };

        // Reconnection mechanism
        reconnectionRunnable = new Runnable() {
            @Override
            public void run() {
                attemptReconnection();
            }
        };
    }

    /**
     * Send heartbeat to maintain connection
     */
    private void sendHeartbeat() {
        if (connectionManager != null) {
            try {
                byte[] heartbeatBytes = HEARTBEAT_MESSAGE.getBytes("UTF-8");
                boolean success = connectionManager.write(heartbeatBytes);
                if (success) {
                    lastHeartbeatSent = System.currentTimeMillis();
                    Log.d(TAG, "Heartbeat sent");
                } else {
                    Log.w(TAG, "Failed to send heartbeat - connection may be unstable");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending heartbeat", e);
            }
        }
    }

    /**
     * Handle connection timeout
     */
    private void handleConnectionTimeout() {
        Log.w(TAG, "Connection timeout detected");
        connectionStable = false;

        if (lastConnectedDevice != null && !isManualDisconnect) {
            startReconnectionProcess();
        }
    }

    /**
     * Start reconnection process
     */
    private void startReconnectionProcess() {
        if (reconnectionAttempts < MAX_RECONNECTION_ATTEMPTS) {
            reconnectionAttempts++;
            Log.d(TAG, "Starting reconnection attempt " + reconnectionAttempts);

            runOnUiThread(() -> {
                Toast.makeText(this, "Connection lost. Attempting to reconnect... (" +
                                reconnectionAttempts + "/" + MAX_RECONNECTION_ATTEMPTS + ")",
                        Toast.LENGTH_SHORT).show();
            });

            reconnectionHandler.postDelayed(reconnectionRunnable, RECONNECTION_DELAY);
        } else {
            Log.w(TAG, "Max reconnection attempts reached");
            runOnUiThread(() -> {
                Toast.makeText(this, "Failed to reconnect after " + MAX_RECONNECTION_ATTEMPTS +
                        " attempts. Please reconnect manually.", Toast.LENGTH_LONG).show();
            });
            resetConnectionState();
        }
    }

    /**
     * Attempt to reconnect to the last connected device
     */
    private void attemptReconnection() {
        if (lastConnectedDevice != null && connectionManager != null) {
            Log.d(TAG, "Attempting to reconnect to " + getDeviceName(lastConnectedDevice));

            try {
                // Stop current connection first
                connectionManager.stop();

                // Wait a moment before reconnecting
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    connectionManager.connectToDevice(lastConnectedDevice);
                }, 1000);

            } catch (Exception e) {
                Log.e(TAG, "Error during reconnection attempt", e);
                startReconnectionProcess(); // Try again
            }
        }
    }

    /**
     * Reset connection state
     */
    private void resetConnectionState() {
        reconnectionAttempts = 0;
        lastConnectedDevice = null;
        connectionStable = false;
        isManualDisconnect = false;

        // Cancel any pending reconnection attempts
        if (reconnectionHandler != null) {
            reconnectionHandler.removeCallbacks(reconnectionRunnable);
        }
    }

    /**
     * Start heartbeat mechanism
     */
    private void startHeartbeat() {
        if (heartbeatHandler != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            heartbeatHandler.post(heartbeatRunnable);
            Log.d(TAG, "Heartbeat mechanism started");
        }
    }

    /**
     * Stop heartbeat mechanism
     */
    private void stopHeartbeat() {
        if (heartbeatHandler != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            Log.d(TAG, "Heartbeat mechanism stopped");
        }
    }

    /**
     * Check if user is logged in using Firebase Auth and SharedPreferences
     */
    private boolean isUserLoggedIn() {
        try {
            // Check Firebase authentication
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser == null) {
                Log.d(TAG, "No Firebase user found");
                return false;
            }

            // Optionally check SharedPreferences for additional validation
            SharedPreferences prefs = getSharedPreferences("MeshUpPrefs", MODE_PRIVATE);
            boolean hasUserData = prefs.contains("user_id") || prefs.contains("user_email");

            Log.d(TAG, "User logged in: " + currentUser.getEmail() +
                    ", SharedPrefs has data: " + hasUserData);

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error checking login status", e);
            return false;
        }
    }

    /**
     * Redirect user to login activity
     */
    private void redirectToLogin() {
        try {
            Log.d(TAG, "Redirecting to login screen");
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Error redirecting to login", e);
            // Fallback - finish the activity
            finish();
        }
    }

    @Override
    public void onConnectionLost(String error) {
        runOnUiThread(() -> {
            try {
                Log.w(TAG, "Connection lost: " + error);
                connectionStable = false;

                // Stop heartbeat when connection is lost
                stopHeartbeat();

                if (!isManualDisconnect) {
                    Toast.makeText(this, "Connection lost: " + error, Toast.LENGTH_SHORT).show();

                    // Start reconnection process if we have a device to reconnect to
                    if (lastConnectedDevice != null) {
                        startReconnectionProcess();
                    }
                } else {
                    Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
                }

                // Optional: Navigate away from chat if connection is lost
                if (navController != null && navController.getCurrentDestination() != null &&
                        navController.getCurrentDestination().getId() == R.id.nav_chat && !isManualDisconnect) {
                    // You might want to navigate back to home or show a reconnection dialog
                    navController.navigate(R.id.nav_home);
                }

                // Restart accepting connections to allow new connections (only if manual disconnect)
                if (isManualDisconnect && connectionManager != null && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    connectionManager.startAcceptingConnections();
                    isManualDisconnect = false; // Reset flag
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling connection lost", e);
            }
        });
    }

    // DeviceConnectionListener implementation
    @Override
    public void onDeviceSelected(BluetoothDevice device) {
        try {
            String deviceName = getDeviceName(device);
            Log.d(TAG, "Device selected: " + deviceName + " (" + device.getAddress() + ")");

            // Store the device for potential reconnection
            lastConnectedDevice = device;

            // Reset reconnection attempts
            reconnectionAttempts = 0;

            // Connect to the selected device
            connectToDevice(device);

            // Navigate to chat after device selection
            if (navController != null && navController.getCurrentDestination() != null &&
                    navController.getCurrentDestination().getId() != R.id.nav_chat) {
                navController.navigate(R.id.nav_chat);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling device selection", e);
            Toast.makeText(this, "Error connecting to device", Toast.LENGTH_SHORT).show();
        }
    }

    private void initializeBluetoothManager() {
        try {
            connectionManager = new BluetoothConnectionManager(this);
            connectionManager.setConnectionListener(this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize BluetoothConnectionManager", e);
            Toast.makeText(this, "Bluetooth initialization failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkPermissionsAndEnableBluetooth() {
        try {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

            if (bluetoothAdapter == null) {
                Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG).show();
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                    ActivityCompat.requestPermissions(this, new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    }, REQUEST_ALL_PERMISSIONS);
                } else {
                    enableBluetooth();
                }
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {

                    ActivityCompat.requestPermissions(this, new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.BLUETOOTH,
                            Manifest.permission.BLUETOOTH_ADMIN
                    }, REQUEST_ALL_PERMISSIONS);
                } else {
                    enableBluetooth();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking permissions and enabling Bluetooth", e);
        }
    }

    private void enableBluetooth() {
        try {
            if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Bluetooth permission not granted", Toast.LENGTH_SHORT).show();
                    return;
                }

                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            } else {
                // Bluetooth is already enabled, start accepting connections
                if (connectionManager != null) {
                    connectionManager.startAcceptingConnections();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error enabling Bluetooth", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_ALL_PERMISSIONS) {
            boolean allPermissionsGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (allPermissionsGranted) {
                enableBluetooth();
            } else {
                Toast.makeText(this, "Permissions are required for Bluetooth functionality", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show();
                if (connectionManager != null) {
                    connectionManager.startAcceptingConnections();
                }
            } else {
                Toast.makeText(this, "Bluetooth is required for this app to function", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ChatFragment.ChatFragmentListener implementation
    @Override
    public BluetoothConnectionManager getConnectionManager() {
        return connectionManager;
    }

    @Override
    public void onSendMessage(String message, BluetoothDevice device) {
        if (connectionManager != null) {
            try {
                Log.d(TAG, "Sending message: " + message + " to device: " + getDeviceName(device));

                // Convert message to bytes and send
                byte[] messageBytes = message.getBytes("UTF-8");
                boolean success = connectionManager.write(messageBytes);

                if (success) {
                    Log.d(TAG, "Message sent successfully");

                    // Update last data activity time
                    lastDataReceived = System.currentTimeMillis();

                    // Also add the sent message to the chat fragment immediately
                    runOnUiThread(() -> {
                        try {
                            NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                                    .findFragmentById(R.id.nav_host_fragment_content_main);

                            if (navHostFragment != null) {
                                Fragment currentFragment = navHostFragment.getChildFragmentManager().getPrimaryNavigationFragment();

                                if (currentFragment instanceof ChatFragment) {
                                    ChatFragment chatFragment = (ChatFragment) currentFragment;
                                    // Add the sent message to the chat display
                                    chatFragment.onMessageSent(true, message);
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error updating UI after sending message", e);
                        }
                    });
                } else {
                    Log.e(TAG, "Failed to send message");
                    Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show();

                    // Connection might be unstable, check for reconnection
                    if (!connectionStable) {
                        handleConnectionTimeout();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);
                Toast.makeText(this, "Error sending message: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.e(TAG, "Connection manager is null");
            Toast.makeText(this, "No connection available", Toast.LENGTH_SHORT).show();
        }
    }

    // BluetoothConnectionManager.BluetoothConnectionListener implementation
    @Override
    public void onConnectionEstablished(BluetoothDevice device) {
        runOnUiThread(() -> {
            try {
                String deviceName = getDeviceName(device);
                Log.d(TAG, "Connection established with: " + deviceName + " (" + device.getAddress() + ")");
                Toast.makeText(this, "Connected to " + deviceName, Toast.LENGTH_SHORT).show();

                // Store connected device and reset reconnection state
                lastConnectedDevice = device;
                reconnectionAttempts = 0;
                connectionStable = true;
                isManualDisconnect = false;
                lastDataReceived = System.currentTimeMillis();

                // Start heartbeat mechanism
                startHeartbeat();

                // Cancel any pending reconnection attempts
                if (reconnectionHandler != null) {
                    reconnectionHandler.removeCallbacks(reconnectionRunnable);
                }

                // Navigate to chat if not already there
                if (navController != null && navController.getCurrentDestination() != null &&
                        navController.getCurrentDestination().getId() != R.id.nav_chat) {
                    navController.navigate(R.id.nav_chat);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling connection established", e);
            }
        });
    }

    @Override
    public void onConnectionFailed(String error) {
        runOnUiThread(() -> {
            Log.e(TAG, "Connection failed: " + error);
            Toast.makeText(this, "Connection failed: " + error, Toast.LENGTH_SHORT).show();

            connectionStable = false;

            // If this was a reconnection attempt, try again
            if (reconnectionAttempts > 0 && lastConnectedDevice != null) {
                startReconnectionProcess();
            }
        });
    }

    @Override
    public void onDataReceived(byte[] buffer, int bytes) {
        if (buffer != null && bytes > 0) {
            try {
                String message = new String(buffer, 0, bytes);

                // Update last data received time
                lastDataReceived = System.currentTimeMillis();

                // Filter out heartbeat messages
                if (HEARTBEAT_MESSAGE.equals(message.trim())) {
                    Log.d(TAG, "Heartbeat received - connection is alive");
                    return;
                }

                Log.d(TAG, "Data received in MainActivity: " + message);

                runOnUiThread(() -> {
                    try {
                        // Find the current ChatFragment and pass the message to it
                        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                                .findFragmentById(R.id.nav_host_fragment_content_main);

                        if (navHostFragment != null) {
                            Fragment currentFragment = navHostFragment.getChildFragmentManager().getPrimaryNavigationFragment();

                            // Safe casting with instanceof check
                            if (currentFragment instanceof ChatFragment) {
                                ChatFragment chatFragment = (ChatFragment) currentFragment;

                                // Get the connected device that sent this message
                                BluetoothDevice connectedDevice = connectionManager != null ?
                                        connectionManager.getConnectedDevice() : null;

                                if (connectedDevice != null) {
                                    // Call the ChatFragment's message received method directly
                                    chatFragment.onMessageReceived(connectedDevice, message);
                                    Log.d(TAG, "Message passed to ChatFragment successfully");
                                } else {
                                    Log.w(TAG, "No connected device found when message received");
                                }
                            } else {
                                // If not currently in chat, store the message or navigate to chat
                                Log.d(TAG, "Message received but not currently in chat fragment");

                                // Store the message for later display
                                storeReceivedMessage(message);

                                // Show notification and optionally navigate to chat
                                if (connectionManager != null && connectionManager.getConnectedDevice() != null) {
                                    String deviceName = getDeviceName(connectionManager.getConnectedDevice());
                                    Toast.makeText(this, "New message from " + deviceName, Toast.LENGTH_SHORT).show();

                                    // Optional: Auto-navigate to chat when message is received
                                    if (navController != null) {
                                        navController.navigate(R.id.nav_chat);
                                    }
                                }
                            }
                        } else {
                            Log.e(TAG, "NavHostFragment is null when processing received message");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error routing received message to ChatFragment", e);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing received data", e);
            }
        }
    }

    // Add this method to store messages when not in chat
    private void storeReceivedMessage(String message) {
        try {
            SharedPreferences prefs = getSharedPreferences("PendingMessages", MODE_PRIVATE);
            String existingMessages = prefs.getString("messages", "");
            String timestamp = String.valueOf(System.currentTimeMillis());
            String messageWithTimestamp = timestamp + ":" + message + "\n";

            prefs.edit()
                    .putString("messages", existingMessages + messageWithTimestamp)
                    .apply();

            Log.d(TAG, "Message stored for later display");
        } catch (Exception e) {
            Log.e(TAG, "Error storing received message", e);
        }
    }

    // Add this method to retrieve stored messages
    public String getStoredMessages() {
        try {
            SharedPreferences prefs = getSharedPreferences("PendingMessages", MODE_PRIVATE);
            String messages = prefs.getString("messages", "");

            // Clear stored messages after retrieving
            prefs.edit().remove("messages").apply();

            return messages;
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving stored messages", e);
            return "";
        }
    }

    /**
     * Helper method to safely get device name with permission checks
     */
    private String getDeviceName(BluetoothDevice device) {
        if (device == null) return "Unknown Device";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    String name = device.getName();
                    return name != null ? name : "Unknown Device";
                }
            } else {
                String name = device.getName();
                return name != null ? name : "Unknown Device";
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception getting device name", e);
        }
        return "Unknown Device";
    }


    private void loadProfileToDrawer() {
        try {
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser == null) return;

            View headerView = binding.navView.getHeaderView(0);
            if (headerView == null) {
                Log.e(TAG, "Navigation header view is null");
                return;
            }

            TextView nameText = headerView.findViewById(R.id.profileName);
            ImageView profileImage = headerView.findViewById(R.id.profileImage);

            if (nameText == null || profileImage == null) {
                Log.e(TAG, "Profile views not found in navigation header");
                return;
            }

            FirebaseDatabase.getInstance().getReference("Users")
                    .child(currentUser.getUid())
                    .get()
                    .addOnSuccessListener(snapshot -> {
                        try {
                            if (snapshot.exists()) {
                                // Try both field names for compatibility
                                String name = snapshot.child("name").getValue(String.class);
                                if (name == null) {
                                    name = snapshot.child("displayName").getValue(String.class);
                                }

                                String base64Image = snapshot.child("profileImageBase64").getValue(String.class);
                                if (base64Image == null) {
                                    base64Image = snapshot.child("avatarUrl").getValue(String.class);
                                }

                                nameText.setText(name != null ? name : "Unknown User");

                                if (base64Image != null && !base64Image.isEmpty()) {
                                    try {
                                        byte[] imageBytes = Base64.decode(base64Image, Base64.DEFAULT);
                                        Glide.with(this)
                                                .asBitmap()
                                                .load(imageBytes)
                                                .circleCrop()
                                                .into(profileImage);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error loading profile image", e);
                                        profileImage.setImageResource(R.mipmap.ic_launcher_round);
                                    }
                                } else {
                                    profileImage.setImageResource(R.mipmap.ic_launcher_round);
                                }
                            } else {
                                Toast.makeText(this, "User profile not found", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing profile data", e);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to load profile", e);
                        Toast.makeText(MainActivity.this, "Failed to load profile", Toast.LENGTH_SHORT).show();
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error in loadProfileToDrawer", e);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        try {
            getMenuInflater().inflate(R.menu.main, menu);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error creating options menu", e);
            return false;
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        try {
            int id = item.getItemId();

            if (navController == null) {
                Log.e(TAG, "NavController is null in onNavigationItemSelected");
                return false;
            }

            if (id == R.id.nav_home) {
                navController.navigate(R.id.nav_home);
            } else if (id == R.id.nav_slideshow) {
                logoutUser();
            } else if (id == R.id.nav_gallery) {
                navController.navigate(R.id.nav_gallery);
            } else if (id == R.id.nav_find_devices) {
                // Add additional checks before navigating to find devices
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                    Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show();
                    return false;
                }
                navController.navigate(R.id.nav_find_devices);
            } else if (id == R.id.nav_chat) {
                navController.navigate(R.id.nav_chat);
            }

            binding.drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in navigation item selection", e);
            Toast.makeText(this, "Navigation error occurred", Toast.LENGTH_SHORT).show();
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            return false;
        }
    }

    private void logoutUser() {
        try {
            // Clean up Bluetooth connections
            if (connectionManager != null) {
                connectionManager.stop();
            }

            // Sign out from Firebase
            FirebaseAuth.getInstance().signOut();

            // Clear SharedPreferences
            SharedPreferences prefs = getSharedPreferences("MeshUpPrefs", MODE_PRIVATE);
            prefs.edit().clear().apply();

            // Navigate to login screen
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();

            Toast.makeText(MainActivity.this, "Logged out successfully", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error during logout", e);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        try {
            return NavigationUI.navigateUp(navController, mAppBarConfiguration) || super.onSupportNavigateUp();
        } catch (Exception e) {
            Log.e(TAG, "Error in onSupportNavigateUp", e);
            return super.onSupportNavigateUp();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (connectionManager != null) {
                connectionManager.stop();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy", e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keep connections alive during pause
        // Individual fragments can manage their own discovery state
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            // Check if user is still logged in when resuming
            if (!isUserLoggedIn()) {
                redirectToLogin();
                return;
            }

            // Resume Bluetooth operations if needed
            if (connectionManager != null && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                // Restart accepting connections if not already connected
                if (connectionManager.getConnectionState() == BluetoothConnectionManager.ConnectionState.NONE) {
                    connectionManager.startAcceptingConnections();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onResume", e);
        }
    }

    // Public method to get connection manager for fragments
    public BluetoothConnectionManager getBluetoothConnectionManager() {
        return connectionManager;
    }

    /**
     * Public method to connect to a specific device
     * Can be called by fragments like FindDevicesFragment
     */
    public void connectToDevice(BluetoothDevice device) {
        if (connectionManager != null) {
            try {
                connectionManager.connectToDevice(device);
            } catch (Exception e) {
                Log.e(TAG, "Error connecting to device", e);
                Toast.makeText(this, "Failed to connect to device", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Public method to check current connection state
     */
    public BluetoothConnectionManager.ConnectionState getConnectionState() {
        try {
            return connectionManager != null ? connectionManager.getConnectionState() : BluetoothConnectionManager.ConnectionState.NONE;
        } catch (Exception e) {
            Log.e(TAG, "Error getting connection state", e);
            return BluetoothConnectionManager.ConnectionState.NONE;
        }
    }

    /**
     * Public method to get currently connected device
     */
    public BluetoothDevice getCurrentlyConnectedDevice() {
        try {
            return connectionManager != null ? connectionManager.getConnectedDevice() : null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting connected device", e);
            return null;
        }
    }
}