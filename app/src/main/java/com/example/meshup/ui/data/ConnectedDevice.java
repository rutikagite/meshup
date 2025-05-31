package com.example.meshup.ui.data;

public class ConnectedDevice {
    private String deviceAddress;
    private String deviceName;
    private String username;
    private int profilePicture;
    private long lastSeen;
    private boolean isOnline;

    // Default constructor for Firebase
    public ConnectedDevice() {}

    public ConnectedDevice(String deviceAddress, String deviceName, String username,
                           int profilePicture, long lastSeen, boolean isOnline) {
        this.deviceAddress = deviceAddress;
        this.deviceName = deviceName;
        this.username = username;
        this.profilePicture = profilePicture;
        this.lastSeen = lastSeen;
        this.isOnline = isOnline;
    }

    // Getters
    public String getDeviceAddress() { return deviceAddress; }
    public String getDeviceName() { return deviceName; }
    public String getUsername() { return username; }
    public int getProfilePicture() { return profilePicture; }
    public long getLastSeen() { return lastSeen; }
    public boolean isOnline() { return isOnline; }

    // Setters
    public void setDeviceAddress(String deviceAddress) { this.deviceAddress = deviceAddress; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public void setUsername(String username) { this.username = username; }
    public void setProfilePicture(int profilePicture) { this.profilePicture = profilePicture; }
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
    public void setOnline(boolean online) { isOnline = online; }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ConnectedDevice that = (ConnectedDevice) obj;
        return deviceAddress != null && deviceAddress.equals(that.deviceAddress);
    }

    @Override
    public int hashCode() {
        return deviceAddress != null ? deviceAddress.hashCode() : 0;
    }
}