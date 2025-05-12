package com.example.meshup;

public class UserModel {
    private String userId;
    private String displayName;
    private String status;
    private String avatarUrl;

    // Required empty constructor for Firestore
    public UserModel() {}

    public UserModel(String userId, String displayName, String avatarUrl) {
        this.userId = userId;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
    }

    // Getters and setters
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
}