package com.example.meshup;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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

    private AppBarConfiguration mAppBarConfiguration;
    private ActivityMainBinding binding;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothConnectionManager connectionManager;
    private DatabaseReference chatRoomsRef;
    private NavController navController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

    @Override
    public void onConnectionLost(String error) {
        runOnUiThread(() -> {
            try {
                Log.w(TAG, "Connection lost: " + error);
                Toast.makeText(this, "Connection lost: " + error, Toast.LENGTH_SHORT).show();

                // Optional: Navigate away from chat if connection is lost
                if (navController != null && navController.getCurrentDestination() != null &&
                        navController.getCurrentDestination().getId() == R.id.nav_chat) {
                    // You might want to navigate back to home or show a reconnection dialog
                    navController.navigate(R.id.nav_home);
                }

                // Optional: Restart accepting connections to allow new connections
                if (connectionManager != null && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    connectionManager.startAcceptingConnections();
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
                // Convert message to bytes and send
                byte[] messageBytes = message.getBytes();
                boolean success = connectionManager.write(messageBytes);
                if (!success) {
                    Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);
                Toast.makeText(this, "Error sending message", Toast.LENGTH_SHORT).show();
            }
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
        });
    }

    @Override
    public void onDataReceived(byte[] buffer, int bytes) {
        if (buffer != null && bytes > 0) {
            try {
                String message = new String(buffer, 0, bytes);
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
                                }
                            } else {
                                // If not currently in chat, you might want to show a notification
                                // or store the message for later display
                                Log.d(TAG, "Message received but not currently in chat fragment");

                                // Optional: Show notification that a message was received
                                if (connectionManager != null && connectionManager.getConnectedDevice() != null) {
                                    String deviceName = getDeviceName(connectionManager.getConnectedDevice());
                                    Toast.makeText(this, "New message from " + deviceName, Toast.LENGTH_SHORT).show();
                                }
                            }
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
                                String name = snapshot.child("displayName").getValue(String.class);
                                String base64Image = snapshot.child("avatarUrl").getValue(String.class);

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
            getSharedPreferences("MeshUpPrefs", MODE_PRIVATE).edit().clear().apply();

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