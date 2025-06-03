package com.example.meshup.ui.chat;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.meshup.R;
import com.example.meshup.ui.data.FirebaseChatDatabase;
import com.example.meshup.ui.data.ChatMessage;
import com.example.meshup.ui.data.ConnectedDevice;
import com.example.meshup.ui.devicediscovery.BluetoothConnectionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ChatFragment extends Fragment implements
        BluetoothConnectionManager.BluetoothConnectionListener,
        FirebaseChatDatabase.ChatMessageListener,
        FirebaseChatDatabase.ConnectedDevicesListener {

    private static final String TAG = "ChatFragment";
    private static final String PREFS_NAME = "ChatPrefs";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PROFILE_PICTURE = "profile_picture";
    private static final String KEY_USER_ID = "user_id";
    private static final String MESSAGE_TYPE_TEXT = "text";
    private static final String MESSAGE_TYPE_USER_INFO = "user_info";
    private static final String MESSAGE_SEPARATOR = "|||";

    // Views
    private RecyclerView recyclerViewConnectedDevices;
    private RecyclerView recyclerViewChatMessages;
    private EditText editTextMessage;
    private ImageButton buttonSendMessage;
    private TextView textViewNoDevices;
    private TextView textViewChatWith;

    // Data
    private ConnectedDevicesAdapter connectedDevicesAdapter;
    private ChatMessagesAdapter chatMessagesAdapter;
    private List<ConnectedDevice> connectedDevicesList;
    private List<ChatMessage> chatMessagesList;
    private BluetoothConnectionManager connectionManager;
    private FirebaseChatDatabase firebaseDatabase;
    private SharedPreferences sharedPreferences;
    private Handler mainHandler;

    // Current chat
    private ConnectedDevice currentChatDevice;
    private String currentUserName;
    private String currentUserId;
    private int currentProfilePicture;

    // Listener interface for parent activity
    public interface ChatFragmentListener {
        BluetoothConnectionManager getConnectionManager();
        void onSendMessage(String message, BluetoothDevice device);
    }

    private ChatFragmentListener chatFragmentListener;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        try {
            chatFragmentListener = (ChatFragmentListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context + " must implement ChatFragmentListener");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_chat, container, false);
        initializeViews(view);
        initializeData();
        setupRecyclerViews();
        setupListeners();
        loadUserProfile();
        loadConnectedDevices();
        return view;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopListeningForMessages();
        if (firebaseDatabase != null) {
            firebaseDatabase.removeListeners();
        }
    }

    // Enhanced onPause to stop listening temporarily
    @Override
    public void onPause() {
        super.onPause();
        if (firebaseDatabase != null) {
            firebaseDatabase.setUserOffline();
        }
        // Don't stop listening here if you want to receive messages in background
        // stopListeningForMessages();
    }

    // Enhanced onResume to restart listening
    @Override
    public void onResume() {
        super.onResume();

        // Ensure connection manager is properly set up
        if (chatFragmentListener != null) {
            connectionManager = chatFragmentListener.getConnectionManager();
            if (connectionManager != null) {
                connectionManager.setConnectionListener(this);
            }
        }

        if (firebaseDatabase != null) {
            firebaseDatabase.setUserOnline();
        }

        // CRITICAL: Always restart listening for ALL device messages, not just current chat
        if (firebaseDatabase != null && firebaseDatabase.isUserAuthenticated()) {
            // Listen for messages from all connected devices
            for (ConnectedDevice device : connectedDevicesList) {
                firebaseDatabase.startListeningForMessages(device.getDeviceAddress(), this);
            }
        }

        refreshConnectedDevices();
    }
    private void initializeViews(View view) {
        recyclerViewConnectedDevices = view.findViewById(R.id.recyclerView_connected_devices);
        recyclerViewChatMessages = view.findViewById(R.id.recyclerView_chat_messages);
        editTextMessage = view.findViewById(R.id.editText_message);
        buttonSendMessage = view.findViewById(R.id.button_send_message);
        textViewNoDevices = view.findViewById(R.id.textView_no_devices);
        textViewChatWith = view.findViewById(R.id.textView_chat_with);
    }

    private void updateConnectedDeviceInfo(BluetoothDevice device, String senderName) {
        ConnectedDevice connectedDevice = findConnectedDevice(device.getAddress());
        if (connectedDevice != null && !connectedDevice.getUsername().equals(senderName)) {
            connectedDevice.setUsername(senderName);
            connectedDevice.setLastSeen(System.currentTimeMillis());
            connectedDevice.setOnline(true);

            int index = connectedDevicesList.indexOf(connectedDevice);
            if (index != -1) {
                connectedDevicesAdapter.notifyItemChanged(index);
            }

            // Update in Firebase
            if (firebaseDatabase != null && firebaseDatabase.isUserAuthenticated()) {
                firebaseDatabase.saveConnectedDevice(connectedDevice);
            }
        }
    }

    // Add this method to your ChatFragment class, alongside the other BluetoothConnectionListener methods

    @Override
    public void onConnectionLost(String error) {
        mainHandler.post(() -> {
            try {
                Log.w(TAG, "Connection lost: " + error);
                Toast.makeText(getContext(), "Connection lost: " + error, Toast.LENGTH_SHORT).show();

                // Find and update the affected device status
                if (connectionManager != null) {
                    BluetoothDevice lostDevice = connectionManager.getConnectedDevice();
                    if (lostDevice != null) {
                        // Update device status to offline
                        ConnectedDevice connectedDevice = findConnectedDevice(lostDevice.getAddress());
                        if (connectedDevice != null) {
                            connectedDevice.setOnline(false);
                            connectedDevice.setLastSeen(System.currentTimeMillis());

                            int index = connectedDevicesList.indexOf(connectedDevice);
                            if (index != -1) {
                                connectedDevicesAdapter.notifyItemChanged(index);
                            }

                            // Update in Firebase
                            if (firebaseDatabase != null && firebaseDatabase.isUserAuthenticated()) {
                                firebaseDatabase.updateDeviceStatus(lostDevice.getAddress(), false);
                            }

                            // If this was the current chat device, disable chat input
                            if (currentChatDevice != null &&
                                    currentChatDevice.getDeviceAddress().equals(lostDevice.getAddress())) {

                                textViewChatWith.setText("Connection lost with " + currentChatDevice.getUsername());
                                editTextMessage.setEnabled(false);
                                editTextMessage.setHint("Device disconnected");
                                buttonSendMessage.setEnabled(false);

                                // Optionally clear current chat device
                                // currentChatDevice = null;
                            }
                        }
                    }
                }

                // Show reconnection hint
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Try reconnecting from Find Devices", Toast.LENGTH_LONG).show();
                }

            } catch (Exception e) {
                Log.e(TAG, "Error handling connection lost", e);
            }
        });
    }

    private void initializeData() {
        connectedDevicesList = new ArrayList<>();
        chatMessagesList = new ArrayList<>();
        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize database and preferences
        firebaseDatabase = new FirebaseChatDatabase();
        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Get connection manager from parent activity
        if (chatFragmentListener != null) {
            connectionManager = chatFragmentListener.getConnectionManager();
            if (connectionManager != null) {
                connectionManager.setConnectionListener(this);
            }
        }
    }

    private void setupRecyclerViews() {
        // Connected devices RecyclerView
        connectedDevicesAdapter = new ConnectedDevicesAdapter(connectedDevicesList, this::onDeviceSelected);
        recyclerViewConnectedDevices.setLayoutManager(
                new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        recyclerViewConnectedDevices.setAdapter(connectedDevicesAdapter);

        // Chat messages RecyclerView
        chatMessagesAdapter = new ChatMessagesAdapter(chatMessagesList, currentUserId);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setStackFromEnd(true); // Start from bottom
        recyclerViewChatMessages.setLayoutManager(layoutManager);
        recyclerViewChatMessages.setAdapter(chatMessagesAdapter);
    }

    private void setupListeners() {
        buttonSendMessage.setOnClickListener(v -> sendMessage());

        editTextMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                buttonSendMessage.setEnabled(s.toString().trim().length() > 0 && currentChatDevice != null);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        editTextMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
    }

    private void loadUserProfile() {
        currentUserName = sharedPreferences.getString(KEY_USERNAME, "User");
        currentUserId = sharedPreferences.getString(KEY_USER_ID, generateUserId());
        currentProfilePicture = sharedPreferences.getInt(KEY_PROFILE_PICTURE, android.R.drawable.ic_menu_myplaces);

        // Save user ID if it was generated
        if (!sharedPreferences.contains(KEY_USER_ID)) {
            sharedPreferences.edit().putString(KEY_USER_ID, currentUserId).apply();
        }

        // Update adapter with current user ID
        if (chatMessagesAdapter != null) {
            chatMessagesAdapter = new ChatMessagesAdapter(chatMessagesList, currentUserId);
            recyclerViewChatMessages.setAdapter(chatMessagesAdapter);
        }
    }

    private String generateUserId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void loadConnectedDevices() {
        if (firebaseDatabase != null && firebaseDatabase.isUserAuthenticated()) {
            firebaseDatabase.loadConnectedDevices(new FirebaseChatDatabase.ConnectedDevicesListener() {
                @Override
                public void onDevicesUpdated(List<ConnectedDevice> devices) {
                    mainHandler.post(() -> {
                        connectedDevicesList.clear();
                        connectedDevicesList.addAll(devices);
                        connectedDevicesAdapter.notifyDataSetChanged();
                        updateDevicesVisibility();

                        // CRITICAL: Set up message listeners for all devices
                        for (ConnectedDevice device : devices) {
                            firebaseDatabase.startListeningForMessages(device.getDeviceAddress(), ChatFragment.this);
                        }
                    });
                }

                @Override
                public void onDeviceStatusChanged(ConnectedDevice device) {
                    mainHandler.post(() -> {
                        ConnectedDevice existingDevice = findConnectedDevice(device.getDeviceAddress());
                        if (existingDevice != null) {
                            int index = connectedDevicesList.indexOf(existingDevice);
                            if (index != -1) {
                                connectedDevicesList.set(index, device);
                                connectedDevicesAdapter.notifyItemChanged(index);
                            }
                        }
                    });
                }
            });
        } else {
            loadConnectedDevicesFromPrefs();
        }
        updateDevicesVisibility();
    }

    private void loadConnectedDevicesFromPrefs() {
        // Fallback method - can be removed if Firebase is always available
        connectedDevicesList.clear();
        connectedDevicesAdapter.notifyDataSetChanged();
    }

    private void refreshConnectedDevices() {
        if (connectionManager != null) {
            BluetoothDevice connectedDevice = connectionManager.getConnectedDevice();
            if (connectedDevice != null) {
                addOrUpdateConnectedDevice(connectedDevice, "Unknown User", android.R.drawable.ic_menu_myplaces);
            }
        }
    }

    private void addOrUpdateConnectedDevice(BluetoothDevice device, String username, int profilePicture) {
        // Check for permission before accessing device name
        String deviceName = "Unknown Device";
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            deviceName = device.getName() != null ? device.getName() : "Unknown Device";
        }

        ConnectedDevice connectedDevice = findConnectedDevice(device.getAddress());

        if (connectedDevice == null) {
            // Add new device
            connectedDevice = new ConnectedDevice(
                    device.getAddress(),
                    deviceName,
                    username,
                    profilePicture,
                    System.currentTimeMillis(),
                    true
            );
            connectedDevicesList.add(0, connectedDevice); // Add to top
            connectedDevicesAdapter.notifyItemInserted(0);
        } else {
            // Update existing device
            connectedDevice.setUsername(username);
            connectedDevice.setProfilePicture(profilePicture);
            connectedDevice.setLastSeen(System.currentTimeMillis());
            connectedDevice.setOnline(true);

            int index = connectedDevicesList.indexOf(connectedDevice);
            if (index != -1) {
                connectedDevicesAdapter.notifyItemChanged(index);
            }
        }

        // Save to Firebase
        if (firebaseDatabase != null && firebaseDatabase.isUserAuthenticated()) {
            firebaseDatabase.saveConnectedDevice(connectedDevice);
        }

        updateDevicesVisibility();
    }

    private ConnectedDevice findConnectedDevice(String deviceAddress) {
        for (ConnectedDevice device : connectedDevicesList) {
            if (device.getDeviceAddress().equals(deviceAddress)) {
                return device;
            }
        }
        return null;
    }

    private void updateDevicesVisibility() {
        if (connectedDevicesList.isEmpty()) {
            textViewNoDevices.setVisibility(View.VISIBLE);
            recyclerViewConnectedDevices.setVisibility(View.GONE);
        } else {
            textViewNoDevices.setVisibility(View.GONE);
            recyclerViewConnectedDevices.setVisibility(View.VISIBLE);
        }
    }

    private void onDeviceSelected(ConnectedDevice device) {
        // Stop listening to previous chat messages
        stopListeningForMessages();

        currentChatDevice = device;
        textViewChatWith.setText("Chat with " + device.getUsername());
        textViewChatWith.setVisibility(View.VISIBLE);

        // Load chat history for this specific device
        loadChatHistory(device.getDeviceAddress());

        // Enable message input
        editTextMessage.setEnabled(true);
        editTextMessage.setHint("Type a message to " + device.getUsername());
        buttonSendMessage.setEnabled(editTextMessage.getText().toString().trim().length() > 0);

        // Mark device as selected in adapter
        connectedDevicesAdapter.setSelectedDevice(device);
    }

    private void sendMessage() {
        String messageText = editTextMessage.getText().toString().trim();
        if (messageText.isEmpty() || currentChatDevice == null) {
            Log.w(TAG, "Cannot send message: empty text or no device selected");
            return;
        }

        Log.d(TAG, "Sending message: " + messageText + " to " + currentChatDevice.getUsername());

        // Generate unique message ID
        String messageId = generateMessageId();

        // Create message object
        ChatMessage message = new ChatMessage(
                messageId,
                currentUserId,
                currentUserName,
                currentChatDevice.getDeviceAddress(),
                messageText,
                MESSAGE_TYPE_TEXT,
                System.currentTimeMillis(),
                false // Not received, it's sent
        );

        // Add to local list immediately for better UX
        chatMessagesList.add(message);
        chatMessagesAdapter.notifyItemInserted(chatMessagesList.size() - 1);
        recyclerViewChatMessages.scrollToPosition(chatMessagesList.size() - 1);
        Log.d(TAG, "Added sent message to local display");

        // Clear input immediately for better UX
        editTextMessage.setText("");

        // Send via Firebase (for persistence and sync)
        if (firebaseDatabase != null && firebaseDatabase.isUserAuthenticated()) {
            firebaseDatabase.sendMessage(message, new FirebaseChatDatabase.ChatMessageListener() {
                @Override
                public void onMessageReceived(ChatMessage message) {}

                @Override
                public void onMessagesLoaded(List<ChatMessage> messages) {}

                @Override
                public void onMessageSent(boolean success, String error) {
                    if (!success) {
                        Log.e(TAG, "Failed to save sent message to Firebase: " + error);
                        // You might want to show an error indicator on the message
                    } else {
                        Log.d(TAG, "Successfully saved sent message to Firebase");
                    }
                }
            });
        }

        // Send via Bluetooth
        boolean bluetoothSent = sendMessageViaBluetooth(messageText);
        if (!bluetoothSent) {
            Log.w(TAG, "Failed to send message via Bluetooth");
            Toast.makeText(getContext(), "Message may not have been delivered", Toast.LENGTH_SHORT).show();
        }
    }

    public void onMessageReceived(BluetoothDevice device, String message) {
        Log.d(TAG, "PUBLIC onMessageReceived called - Device: " + device.getAddress() + ", Message: " + message);

        if (mainHandler != null) {
            mainHandler.post(() -> {
                processReceivedMessage(device, message);
            });
        } else {
            // Fallback
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    processReceivedMessage(device, message);
                });
            }
        }
    }

    private boolean sendMessageViaBluetooth(String messageText) {
        if (connectionManager != null && currentChatDevice != null) {
            BluetoothDevice bluetoothDevice = connectionManager.getConnectedDevice();
            if (bluetoothDevice != null && bluetoothDevice.getAddress().equals(currentChatDevice.getDeviceAddress())) {
                // Create formatted message with user info
                String formattedMessage = MESSAGE_TYPE_TEXT + MESSAGE_SEPARATOR +
                        currentUserName + MESSAGE_SEPARATOR +
                        currentUserId + MESSAGE_SEPARATOR +
                        messageText;

                if (chatFragmentListener != null) {
                    chatFragmentListener.onSendMessage(formattedMessage, bluetoothDevice);
                    return true; // Message sent successfully
                }
            }
        }
        return false; // Failed to send message
    }

    private String generateMessageId() {
        return UUID.randomUUID().toString();
    }

    // Firebase ChatMessageListener implementation
    @Override
    public void onMessageReceived(ChatMessage message) {
        mainHandler.post(() -> {
            Log.d(TAG, "Firebase message received: " + message.getContent() + " from " + message.getSenderName());

            // Check if message already exists to avoid duplicates
            boolean messageExists = false;
            for (ChatMessage existingMessage : chatMessagesList) {
                if (existingMessage.getId().equals(message.getId())) {
                    messageExists = true;
                    break;
                }
            }

            // If this message is for the current chat device, display it
            if (currentChatDevice != null &&
                    message.getDeviceAddress().equals(currentChatDevice.getDeviceAddress()) &&
                    !messageExists) {

                chatMessagesList.add(message);
                chatMessagesAdapter.notifyItemInserted(chatMessagesList.size() - 1);
                recyclerViewChatMessages.scrollToPosition(chatMessagesList.size() - 1);
                Log.d(TAG, "Displayed Firebase message in current chat");
            }

            // Update device status regardless of current chat
            updateDeviceWithNewMessage(message.getDeviceAddress());
        });
    }

    // New method to update device status when new message arrives
    private void updateDeviceWithNewMessage(String deviceAddress) {
        ConnectedDevice device = findConnectedDevice(deviceAddress);
        if (device != null) {
            device.setLastSeen(System.currentTimeMillis());
            device.setOnline(true); // Mark as online since they just sent a message

            int index = connectedDevicesList.indexOf(device);
            if (index != -1) {
                connectedDevicesAdapter.notifyItemChanged(index);
            }
        }
    }

    @Override
    public void onMessagesLoaded(List<ChatMessage> messages) {
        mainHandler.post(() -> {
            chatMessagesList.clear();
            chatMessagesList.addAll(messages);
            chatMessagesAdapter.notifyDataSetChanged();
            if (!chatMessagesList.isEmpty()) {
                recyclerViewChatMessages.scrollToPosition(chatMessagesList.size() - 1);
            }
        });
    }

    @Override
    public void onMessageSent(boolean success, String error) {
        mainHandler.post(() -> {
            if (!success && error != null) {
                Toast.makeText(getContext(), "Failed to send message: " + error, Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Message send failed: " + error);
            }
        });
    }

    // Firebase ConnectedDevicesListener implementation
    @Override
    public void onDevicesUpdated(List<ConnectedDevice> devices) {
        mainHandler.post(() -> {
            connectedDevicesList.clear();
            connectedDevicesList.addAll(devices);
            connectedDevicesAdapter.notifyDataSetChanged();
            updateDevicesVisibility();
        });
    }

    @Override
    public void onDeviceStatusChanged(ConnectedDevice device) {
        mainHandler.post(() -> {
            ConnectedDevice existingDevice = findConnectedDevice(device.getDeviceAddress());
            if (existingDevice != null) {
                int index = connectedDevicesList.indexOf(existingDevice);
                if (index != -1) {
                    connectedDevicesList.set(index, device);
                    connectedDevicesAdapter.notifyItemChanged(index);
                }
            }
        });
    }

    // BluetoothConnectionListener implementation
    public void onDeviceConnected(BluetoothDevice device) {
        mainHandler.post(() -> {
            String deviceName = "Unknown Device";
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                deviceName = device.getName();
            }
            Log.d(TAG, "Device connected: " + deviceName + " (" + device.getAddress() + ")");
            addOrUpdateConnectedDevice(device, "Unknown User", android.R.drawable.ic_menu_myplaces);

            // Send user info message
            sendUserInfoMessage(device);
        });
    }

    public void onDeviceDisconnected(BluetoothDevice device) {
        mainHandler.post(() -> {
            String deviceName = "Unknown Device";
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                deviceName = device.getName();
            }
            Log.d(TAG, "Device disconnected: " + deviceName + " (" + device.getAddress() + ")");

            // Update device status to offline
            ConnectedDevice connectedDevice = findConnectedDevice(device.getAddress());
            if (connectedDevice != null) {
                connectedDevice.setOnline(false);
                connectedDevice.setLastSeen(System.currentTimeMillis());

                int index = connectedDevicesList.indexOf(connectedDevice);
                if (index != -1) {
                    connectedDevicesAdapter.notifyItemChanged(index);
                }

                // Update in Firebase
                if (firebaseDatabase != null && firebaseDatabase.isUserAuthenticated()) {
                    firebaseDatabase.updateDeviceStatus(device.getAddress(), false);
                }
            }
        });
    }

    @Override
    public void onConnectionEstablished(BluetoothDevice device) {
        mainHandler.post(() -> {
            String deviceName = "Unknown Device";
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                deviceName = device.getName();
            }
            Log.d(TAG, "Connection established with: " + deviceName + " (" + device.getAddress() + ")");
            addOrUpdateConnectedDevice(device, "Unknown User", android.R.drawable.ic_menu_myplaces);
        });
    }

    public void onConnectionFailed(BluetoothDevice device, String error) {
        mainHandler.post(() -> {
            Log.e(TAG, "Connection failed to " + device.getAddress() + ": " + error);
            Toast.makeText(getContext(), "Connection failed: " + error, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onConnectionFailed(String error) {
        mainHandler.post(() -> {
            Log.e(TAG, "Connection failed: " + error);
            Toast.makeText(getContext(), "Connection failed: " + error, Toast.LENGTH_SHORT).show();
        });
    }

    // Add the missing abstract method implementation for onDataReceived
    @Override
    public void onDataReceived(byte[] data, int length) {
        if (data != null && length > 0) {
            String message = new String(data, 0, length);
            mainHandler.post(() -> {
                Log.d(TAG, "Data received: " + message);
                // Process the received data as a message
                // Note: You might need to determine which device sent this data
                // This implementation assumes you have a way to get the current connected device
                if (connectionManager != null) {
                    BluetoothDevice connectedDevice = connectionManager.getConnectedDevice();
                    if (connectedDevice != null) {
                        processReceivedMessage(connectedDevice, message);
                    }
                }
            });
        }
    }

    private void sendUserInfoMessage(BluetoothDevice device) {
        String userInfoMessage = MESSAGE_TYPE_USER_INFO + MESSAGE_SEPARATOR +
                currentUserName + MESSAGE_SEPARATOR +
                currentUserId + MESSAGE_SEPARATOR +
                currentProfilePicture;

        if (chatFragmentListener != null) {
            chatFragmentListener.onSendMessage(userInfoMessage, device);
        }
    }

    private void processReceivedMessage(BluetoothDevice device, String receivedMessage) {
        try {
            String[] parts = receivedMessage.split("\\" + MESSAGE_SEPARATOR);
            if (parts.length < 4) {
                Log.w(TAG, "Invalid message format received: " + receivedMessage);
                return;
            }

            String messageType = parts[0];
            String senderName = parts[1];
            String senderId = parts[2];

            if (MESSAGE_TYPE_USER_INFO.equals(messageType)) {
                // Handle user info message
                int profilePicture = Integer.parseInt(parts[3]);
                addOrUpdateConnectedDevice(device, senderName, profilePicture);

            } else if (MESSAGE_TYPE_TEXT.equals(messageType)) {
                // Handle text message
                String messageContent = parts[3];

                // Create unique message ID
                String messageId = generateUniqueMessageId(senderId, messageContent, System.currentTimeMillis());

                ChatMessage chatMessage = new ChatMessage(
                        messageId,
                        senderId,
                        senderName,
                        device.getAddress(),
                        messageContent,
                        MESSAGE_TYPE_TEXT,
                        System.currentTimeMillis(),
                        true // This is a received message
                );

                Log.d(TAG, "Processing received message: " + messageContent + " from " + senderName);

                // CRITICAL: Display message immediately on receiver's screen
                displayReceivedMessage(chatMessage, device.getAddress());

                // Save to Firebase for persistence - but don't wait for it
                if (firebaseDatabase != null && firebaseDatabase.isUserAuthenticated()) {
                    firebaseDatabase.sendMessage(chatMessage, new FirebaseChatDatabase.ChatMessageListener() {
                        @Override
                        public void onMessageReceived(ChatMessage message) {}

                        @Override
                        public void onMessagesLoaded(List<ChatMessage> messages) {}

                        @Override
                        public void onMessageSent(boolean success, String error) {
                            if (!success) {
                                Log.e(TAG, "Failed to save received message to Firebase: " + error);
                            } else {
                                Log.d(TAG, "Successfully saved received message to Firebase");
                            }
                        }
                    });
                }

                // Update connected device info
                updateConnectedDeviceInfo(device, senderName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing received message: " + receivedMessage, e);
        }
    }

    private void displayReceivedMessage(ChatMessage chatMessage, String deviceAddress) {
        // ALWAYS display the message if it's from any connected device
        // Not just the currently selected chat device

        // First, check if message already exists to avoid duplicates
        boolean messageExists = chatMessagesList.stream()
                .anyMatch(msg -> msg.getId().equals(chatMessage.getId()) ||
                        (msg.getContent().equals(chatMessage.getContent()) &&
                                msg.getSenderId().equals(chatMessage.getSenderId()) &&
                                Math.abs(msg.getTimestamp() - chatMessage.getTimestamp()) < 2000));

        if (!messageExists) {
            // If this device is currently selected for chat, show immediately
            if (currentChatDevice != null && currentChatDevice.getDeviceAddress().equals(deviceAddress)) {
                chatMessagesList.add(chatMessage);
                chatMessagesAdapter.notifyItemInserted(chatMessagesList.size() - 1);
                recyclerViewChatMessages.scrollToPosition(chatMessagesList.size() - 1);
                Log.d(TAG, "Added received message to current chat display");
            } else {
                // Message is from a different device - update that device's status
                // to show there's a new message waiting
                updateDeviceWithNewMessage(deviceAddress);
                Log.d(TAG, "Message received from non-active chat device: " + deviceAddress);
            }
        } else {
            Log.d(TAG, "Message already exists, skipping duplicate");
        }
    }

    // New method to broadcast new messages to Firebase listeners
    private void broadcastNewMessage(ChatMessage message) {
        // This method ensures that when a message is saved to Firebase,
        // all devices listening to that chat will be notified
        if (firebaseDatabase != null && firebaseDatabase.isUserAuthenticated()) {
            // Trigger a notification in Firebase that will cause other devices to reload messages
            firebaseDatabase.notifyNewMessage(message.getDeviceAddress(), message);
        }
    }


    // Helper method to generate unique message IDs
    private String generateUniqueMessageId(String senderId, String content, long timestamp) {
        return senderId + "_" + content.hashCode() + "_" + timestamp;
    }

    // Also make sure your loadChatHistory method is robust
    private void loadChatHistory(String deviceAddress) {
        chatMessagesList.clear();
        chatMessagesAdapter.notifyDataSetChanged();

        if (firebaseDatabase != null && firebaseDatabase.isUserAuthenticated()) {
            Log.d(TAG, "Loading chat history for device: " + deviceAddress);

            // Load existing messages
            firebaseDatabase.loadChatMessages(deviceAddress, this);

            // IMPORTANT: Start listening for new messages in real-time
            firebaseDatabase.startListeningForMessages(deviceAddress, this);
        } else {
            Log.w(TAG, "Firebase database not available or user not authenticated");
        }
    }

    private void stopListeningForMessages() {
        if (firebaseDatabase != null && currentChatDevice != null) {
            firebaseDatabase.stopListeningForMessages(currentChatDevice.getDeviceAddress());
        }
    }

    // Public methods for external access
    public void clearChatHistory() {
        if (currentChatDevice != null) {
            chatMessagesList.clear();
            chatMessagesAdapter.notifyDataSetChanged();

            if (firebaseDatabase != null && firebaseDatabase.isUserAuthenticated()) {
                firebaseDatabase.deleteChatHistory(currentChatDevice.getDeviceAddress());
            }
        }
    }

    public void removeConnectedDevice(ConnectedDevice device) {
        connectedDevicesList.remove(device);
        connectedDevicesAdapter.notifyDataSetChanged();
        updateDevicesVisibility();

        if (firebaseDatabase != null && firebaseDatabase.isUserAuthenticated()) {
            firebaseDatabase.removeConnectedDevice(device.getDeviceAddress());
        }

        // Clear chat if this was the current chat device
        if (currentChatDevice != null && currentChatDevice.equals(device)) {
            currentChatDevice = null;
            chatMessagesList.clear();
            chatMessagesAdapter.notifyDataSetChanged();
            textViewChatWith.setVisibility(View.GONE);
            editTextMessage.setEnabled(false);
            editTextMessage.setHint("Select a device to start chatting");
            buttonSendMessage.setEnabled(false);
        }
    }

    public boolean hasConnectedDevices() {
        return !connectedDevicesList.isEmpty();
    }

    public ConnectedDevice getCurrentChatDevice() {
        return currentChatDevice;
    }
}