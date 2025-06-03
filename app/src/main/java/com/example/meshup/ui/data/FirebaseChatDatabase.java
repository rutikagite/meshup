package com.example.meshup.ui.data;

import android.util.Log;

import androidx.annotation.NonNull;

import com.example.meshup.UserModel;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FirebaseChatDatabase {

    private static final String TAG = "FirebaseChatDatabase";
    private static final String USERS_NODE = "Users";
    private static final String CHATS_NODE = "Chats";
    private static final String MESSAGES_NODE = "Messages";
    private static final String CONNECTED_DEVICES_NODE = "ConnectedDevices";

    private FirebaseDatabase database;
    private DatabaseReference usersRef;
    private DatabaseReference chatsRef;
    private DatabaseReference messagesRef;
    private DatabaseReference connectedDevicesRef;
    private FirebaseAuth auth;
    private String currentUserId;

    // Listeners
    private ChatMessageListener messageListener;
    private ConnectedDevicesListener devicesListener;
    private UserProfileListener userProfileListener;
    private Map<String, ValueEventListener> messageListeners = new HashMap<>();

    public interface ChatMessageListener {
        void onMessageReceived(ChatMessage message);
        void onMessagesLoaded(List<ChatMessage> messages);
        void onMessageSent(boolean success, String error);
    }

    public interface ConnectedDevicesListener {
        void onDevicesUpdated(List<ConnectedDevice> devices);
        void onDeviceStatusChanged(ConnectedDevice device);
    }

    public interface UserProfileListener {
        void onUserProfileLoaded(UserModel user);
        void onUserProfileUpdated(boolean success, String error);
    }

    public FirebaseChatDatabase() {
        database = FirebaseDatabase.getInstance();
        auth = FirebaseAuth.getInstance();

        usersRef = database.getReference(USERS_NODE);
        chatsRef = database.getReference(CHATS_NODE);
        messagesRef = database.getReference(MESSAGES_NODE);
        connectedDevicesRef = database.getReference(CONNECTED_DEVICES_NODE);

        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser != null) {
            currentUserId = currentUser.getUid();
        }
    }

    // Helper method to get current user
    private FirebaseUser getCurrentUser() {
        return auth.getCurrentUser();
    }

    // User Profile Management
    public void saveUserProfile(UserModel user, UserProfileListener listener) {
        if (currentUserId == null) {
            if (listener != null) {
                listener.onUserProfileUpdated(false, "User not authenticated");
            }
            return;
        }

        usersRef.child(currentUserId)
                .setValue(user)
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "User profile saved successfully");
                    if (listener != null) {
                        listener.onUserProfileUpdated(true, null);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to save user profile", e);
                    if (listener != null) {
                        listener.onUserProfileUpdated(false, e.getMessage());
                    }
                });
    }

    public void loadUserProfile(String userId, UserProfileListener listener) {
        usersRef.child(userId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            UserModel user = snapshot.getValue(UserModel.class);
                            if (listener != null && user != null) {
                                listener.onUserProfileLoaded(user);
                            }
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Failed to load user profile", error.toException());
                    }
                });
    }

    public void loadCurrentUserProfile(UserProfileListener listener) {
        if (currentUserId != null) {
            loadUserProfile(currentUserId, listener);
        }
    }

    public void startListeningForMessages(String deviceAddress, ChatMessageListener listener) {
        FirebaseUser currentUser = getCurrentUser();
        if (currentUser == null) return;

        String chatPath = "chats/" + currentUser.getUid() + "/" + deviceAddress + "/messages";
        DatabaseReference messagesRef = database.getReference(chatPath);

        // Remove existing listener for this device if any
        stopListeningForMessages(deviceAddress);

        ValueEventListener messageListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<ChatMessage> messages = new ArrayList<>();
                for (DataSnapshot messageSnapshot : snapshot.getChildren()) {
                    try {
                        ChatMessage message = messageSnapshot.getValue(ChatMessage.class);
                        if (message != null) {
                            messages.add(message);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing message", e);
                    }
                }

                // Sort messages by timestamp
                messages.sort((m1, m2) -> Long.compare(m1.getTimestamp(), m2.getTimestamp()));

                if (listener != null) {
                    listener.onMessagesLoaded(messages);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to listen for messages: " + error.getMessage());
            }
        };

        messagesRef.addValueEventListener(messageListener);
        messageListeners.put(deviceAddress, messageListener);

        Log.d(TAG, "Started listening for messages from device: " + deviceAddress);
    }

    // Method to stop listening for messages from a specific device
    public void stopListeningForMessages(String deviceAddress) {
        ValueEventListener listener = messageListeners.get(deviceAddress);
        FirebaseUser currentUser = getCurrentUser();
        if (listener != null && currentUser != null) {
            String chatPath = "chats/" + currentUser.getUid() + "/" + deviceAddress + "/messages";
            DatabaseReference messagesRef = database.getReference(chatPath);
            messagesRef.removeEventListener(listener);
            messageListeners.remove(deviceAddress);
            Log.d(TAG, "Stopped listening for messages from device: " + deviceAddress);
        }
    }

    // Method to notify about new messages (triggers real-time updates)
    public void notifyNewMessage(String deviceAddress, ChatMessage message) {
        FirebaseUser currentUser = getCurrentUser();
        if (currentUser == null) return;

        // Update a "last_message" field that triggers listeners on other devices
        String chatPath = "chats/" + currentUser.getUid() + "/" + deviceAddress;
        DatabaseReference chatRef = database.getReference(chatPath);

        Map<String, Object> updates = new HashMap<>();
        updates.put("last_message_time", message.getTimestamp());
        updates.put("last_message_content", message.getContent());
        updates.put("last_message_sender", message.getSenderName());

        chatRef.updateChildren(updates)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "New message notification sent"))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to send new message notification", e));
    }

    // Chat Message Management
    public void sendMessage(ChatMessage message, ChatMessageListener listener) {
        FirebaseUser currentUser = getCurrentUser();
        if (currentUser == null) {
            if (listener != null) {
                listener.onMessageSent(false, "User not authenticated");
            }
            return;
        }

        String messageId = message.getId();
        String deviceAddress = message.getDeviceAddress();

        // Save message for sender
        String senderChatPath = "chats/" + currentUser.getUid() + "/" + deviceAddress + "/messages/" + messageId;
        DatabaseReference senderMessageRef = database.getReference(senderChatPath);

        senderMessageRef.setValue(message)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Message saved for sender");

                    // Now save message for receiver (if we can identify the receiver)
                    saveMessageForReceiver(message, listener);

                    if (listener != null) {
                        listener.onMessageSent(true, null);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to save message for sender", e);
                    if (listener != null) {
                        listener.onMessageSent(false, e.getMessage());
                    }
                });
    }

    // Method to save message for receiver
    private void saveMessageForReceiver(ChatMessage message, ChatMessageListener listener) {
        FirebaseUser currentUser = getCurrentUser();
        if (currentUser == null) return;

        // You'll need to implement a way to identify the receiver's user ID
        // This could be done by storing device-to-user mappings when devices connect

        String receiverUserId = getReceiverUserId(message.getDeviceAddress());
        if (receiverUserId != null && !receiverUserId.equals(currentUser.getUid())) {

            // Create a message from receiver's perspective
            ChatMessage receiverMessage = new ChatMessage(
                    message.getId(),
                    message.getSenderId(),
                    message.getSenderName(),
                    getCurrentUserDeviceAddress(), // Your device address from receiver's perspective
                    message.getContent(),
                    message.getMessageType(), // Using getMessageType() instead of getType()
                    message.getTimestamp(),
                    true // This is received for the receiver
            );

            String receiverChatPath = "chats/" + receiverUserId + "/" + getCurrentUserDeviceAddress() + "/messages/" + message.getId();
            DatabaseReference receiverMessageRef = database.getReference(receiverChatPath);

            receiverMessageRef.setValue(receiverMessage)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Message saved for receiver");
                        // Trigger the listener callback for the receiver
                        if (listener != null) {
                            listener.onMessageReceived(receiverMessage);
                        }
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "Failed to save message for receiver", e));
        }
    }

    // Helper method to get receiver's user ID from device address
    private String getReceiverUserId(String deviceAddress) {
        // You'll need to implement this based on how you store device-to-user mappings
        // For now, return null - you'll need to store this mapping when devices connect
        return null;
    }

    // Helper method to get current user's device address
    private String getCurrentUserDeviceAddress() {
        // Return your device's Bluetooth address
        // You'll need to pass this from the ChatFragment
        return null;
    }

    public void loadChatMessages(String deviceAddress, ChatMessageListener listener) {
        if (currentUserId == null) return;

        String chatRoomId = createChatRoomId(currentUserId, deviceAddress);

        messagesRef.child(chatRoomId)
                .orderByChild("timestamp")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<ChatMessage> messages = new ArrayList<>();
                        for (DataSnapshot messageSnapshot : snapshot.getChildren()) {
                            ChatMessage message = messageSnapshot.getValue(ChatMessage.class);
                            if (message != null) {
                                messages.add(message);
                            }
                        }

                        if (listener != null) {
                            listener.onMessagesLoaded(messages);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Failed to load messages", error.toException());
                    }
                });
    }

    public void listenForNewMessages(String deviceAddress, ChatMessageListener listener) {
        if (currentUserId == null) return;

        this.messageListener = listener;
        String chatRoomId = createChatRoomId(currentUserId, deviceAddress);

        messagesRef.child(chatRoomId)
                .orderByChild("timestamp")
                .limitToLast(1)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        for (DataSnapshot messageSnapshot : snapshot.getChildren()) {
                            ChatMessage message = messageSnapshot.getValue(ChatMessage.class);
                            if (message != null && !message.getSenderId().equals(currentUserId)) {
                                if (listener != null) {
                                    listener.onMessageReceived(message);
                                }
                            }
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Failed to listen for new messages", error.toException());
                    }
                });
    }

    // Connected Devices Management
    public void saveConnectedDevice(ConnectedDevice device) {
        if (currentUserId == null) return;

        connectedDevicesRef.child(currentUserId)
                .child(device.getDeviceAddress())
                .setValue(device)
                .addOnSuccessListener(unused -> Log.d(TAG, "Connected device saved"))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to save connected device", e));
    }

    public void loadConnectedDevices(ConnectedDevicesListener listener) {
        if (currentUserId == null) return;

        this.devicesListener = listener;

        connectedDevicesRef.child(currentUserId)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<ConnectedDevice> devices = new ArrayList<>();
                        for (DataSnapshot deviceSnapshot : snapshot.getChildren()) {
                            ConnectedDevice device = deviceSnapshot.getValue(ConnectedDevice.class);
                            if (device != null) {
                                devices.add(device);
                            }
                        }

                        if (listener != null) {
                            listener.onDevicesUpdated(devices);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Failed to load connected devices", error.toException());
                    }
                });
    }

    public void updateDeviceStatus(String deviceAddress, boolean isOnline) {
        if (currentUserId == null) return;

        Map<String, Object> updates = new HashMap<>();
        updates.put("online", isOnline);
        updates.put("lastSeen", System.currentTimeMillis());

        connectedDevicesRef.child(currentUserId)
                .child(deviceAddress)
                .updateChildren(updates)
                .addOnSuccessListener(unused -> Log.d(TAG, "Device status updated"))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to update device status", e));
    }

    public void removeConnectedDevice(String deviceAddress) {
        if (currentUserId == null) return;

        connectedDevicesRef.child(currentUserId)
                .child(deviceAddress)
                .removeValue()
                .addOnSuccessListener(unused -> Log.d(TAG, "Connected device removed"))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to remove connected device", e));
    }

    // Chat Room Management
    private void updateChatRoom(String chatRoomId, ChatMessage lastMessage) {
        Map<String, Object> chatRoomData = new HashMap<>();
        chatRoomData.put("lastMessage", lastMessage.getContent());
        chatRoomData.put("lastMessageTime", lastMessage.getTimestamp());
        chatRoomData.put("lastMessageSender", lastMessage.getSenderName());

        chatsRef.child(chatRoomId).updateChildren(chatRoomData);
    }

    public void deleteChatHistory(String deviceAddress) {
        if (currentUserId == null) return;

        String chatRoomId = createChatRoomId(currentUserId, deviceAddress);

        messagesRef.child(chatRoomId)
                .removeValue()
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "Chat history deleted");
                    // Also remove from chats node
                    chatsRef.child(chatRoomId).removeValue();
                })
                .addOnFailureListener(e -> Log.e(TAG, "Failed to delete chat history", e));
    }

    // Utility Methods
    private String createChatRoomId(String userId1, String userId2) {
        // Create a consistent chat room ID regardless of order
        return userId1.compareTo(userId2) < 0 ?
                userId1 + "_" + userId2 :
                userId2 + "_" + userId1;
    }

    // Online Presence Management
    public void setUserOnline() {
        if (currentUserId == null) return;

        DatabaseReference presenceRef = database.getReference("presence").child(currentUserId);
        Map<String, Object> presenceData = new HashMap<>();
        presenceData.put("online", true);
        presenceData.put("lastSeen", System.currentTimeMillis());

        presenceRef.setValue(presenceData);

        // Set offline when user disconnects - Fixed for older Android versions
        Map<String, Object> offlineData = new HashMap<>();
        offlineData.put("online", false);
        offlineData.put("lastSeen", System.currentTimeMillis());
        presenceRef.onDisconnect().updateChildren(offlineData);
    }

    public void setUserOffline() {
        if (currentUserId == null) return;

        DatabaseReference presenceRef = database.getReference("presence").child(currentUserId);
        Map<String, Object> presenceData = new HashMap<>();
        presenceData.put("online", false);
        presenceData.put("lastSeen", System.currentTimeMillis());

        presenceRef.setValue(presenceData);
    }

    // Clean up listeners
    public void removeListeners() {
        // Note: Listeners are stored as references and would need proper cleanup
        // This is a simplified version
        for (Map.Entry<String, ValueEventListener> entry : messageListeners.entrySet()) {
            String deviceAddress = entry.getKey();
            ValueEventListener listener = entry.getValue();
            FirebaseUser currentUser = getCurrentUser();
            if (currentUser != null) {
                String chatPath = "chats/" + currentUser.getUid() + "/" + deviceAddress + "/messages";
                DatabaseReference messagesRef = database.getReference(chatPath);
                messagesRef.removeEventListener(listener);
            }
        }
        messageListeners.clear();
        Log.d(TAG, "Removing listeners");
    }

    // Get current user ID
    public String getCurrentUserId() {
        return currentUserId;
    }

    // Check if user is authenticated
    public boolean isUserAuthenticated() {
        return currentUserId != null;
    }
}