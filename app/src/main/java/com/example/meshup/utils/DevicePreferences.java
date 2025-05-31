package com.example.meshup.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.UUID;

/**
 * Utility class for managing device-specific preferences
 */
public class DevicePreferences {
    private static final String PREF_NAME = "device_prefs";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_NAME = "device_name";

    /**
     * Get unique device ID, creating one if not already present
     *
     * @param context Application context
     * @return Device ID
     */
    public static String getDeviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String deviceId = prefs.getString(KEY_DEVICE_ID, null);

        if (deviceId == null) {
            // Generate a device ID using a combination of Android ID and UUID
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            deviceId = UUID.nameUUIDFromBytes(androidId.getBytes()).toString();

            // Save for future use
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }

        return deviceId;
    }

    /**
     * Get device name, using default if not set
     *
     * @param context Application context
     * @return Device name
     */
    public static String getDeviceName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String deviceName = prefs.getString(KEY_DEVICE_NAME, null);

        if (deviceName == null) {
            // Use Android device model as default name
            deviceName = android.os.Build.MODEL;

            // Save for future use
            prefs.edit().putString(KEY_DEVICE_NAME, deviceName).apply();
        }

        return deviceName;
    }

    /**
     * Set custom device name
     *
     * @param context Application context
     * @param name New device name
     */
    public static void setDeviceName(Context context, String name) {
        if (name != null && !name.isEmpty()) {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_DEVICE_NAME, name).apply();
        }
    }
}