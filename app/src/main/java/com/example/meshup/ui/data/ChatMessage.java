package com.example.meshup.ui.data;

public class ChatMessage {
    private String id;
    private String senderId;
    private String senderName;
    private String deviceAddress;
    private String content;
    private String messageType;
    private long timestamp;
    private boolean isReceived;

    // Default constructor for Firebase
    public ChatMessage() {}

    public ChatMessage(String id, String senderId, String senderName, String deviceAddress,
                       String content, String messageType, long timestamp, boolean isReceived) {
        this.id = id;
        this.senderId = senderId;
        this.senderName = senderName;
        this.deviceAddress = deviceAddress;
        this.content = content;
        this.messageType = messageType;
        this.timestamp = timestamp;
        this.isReceived = isReceived;
    }

    // Getters
    public String getId() { return id; }
    public String getSenderId() { return senderId; }
    public String getSenderName() { return senderName; }
    public String getDeviceAddress() { return deviceAddress; }
    public String getContent() { return content; }
    public String getMessageType() { return messageType; }
    public long getTimestamp() { return timestamp; }
    public boolean isReceived() { return isReceived; }

    // Setters
    public void setId(String id) { this.id = id; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public void setDeviceAddress(String deviceAddress) { this.deviceAddress = deviceAddress; }
    public void setContent(String content) { this.content = content; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public void setReceived(boolean received) { isReceived = received; }
}