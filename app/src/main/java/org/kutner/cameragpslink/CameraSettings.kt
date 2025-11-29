package org.kutner.cameragpslink

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class CameraSettings(
    val deviceAddress: String,
    val quickConnectEnabled: Boolean = false,
    val quickConnectDurationMinutes: Int = 5,
    val lastDisconnectTimestamp: Long? = null
)

object CameraSettingsManager {
    const val PREFS_NAME = "cameragpslinkPrefs"
    private const val CAMERA_SETTINGS_KEY = "camera_settings"
    private const val KEY_SAVED_CAMERAS = "saved_cameras" // Kept for migration
    private const val KEY_SHOW_LOG = "show_log"
    
    private val gson = Gson()

    /**
     * Save settings for a specific camera
     */
    fun saveSettings(context: Context, settings: CameraSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val allSettings = loadAllSettings(prefs).toMutableMap()
        allSettings[settings.deviceAddress] = settings
        saveAllSettings(prefs, allSettings)
    }

    /**
     * Get settings for a specific camera
     */
    fun getSettings(context: Context, deviceAddress: String): CameraSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val allSettings = loadAllSettings(prefs)
        return allSettings[deviceAddress] ?: CameraSettings(deviceAddress = deviceAddress)
    }

    /**
     * Update Quick Connect settings for a camera
     */
    fun updateQuickConnect(context: Context, deviceAddress: String, enabled: Boolean, durationMinutes: Int) {
        val currentSettings = getSettings(context, deviceAddress)
        val updatedSettings = currentSettings.copy(
            quickConnectEnabled = enabled,
            quickConnectDurationMinutes = durationMinutes
        )
        saveSettings(context, updatedSettings)
    }

    /**
     * Update last disconnect timestamp for a camera
     */
    fun updateDisconnectTimestamp(context: Context, deviceAddress: String, timestamp: Long) {
        val currentSettings = getSettings(context, deviceAddress)
        val updatedSettings = currentSettings.copy(lastDisconnectTimestamp = timestamp)
        saveSettings(context, updatedSettings)
    }

    /**
     * Remove all settings for a camera
     */
    fun removeSettings(context: Context, deviceAddress: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val allSettings = loadAllSettings(prefs).toMutableMap()
        if (allSettings.remove(deviceAddress) != null) {
            saveAllSettings(prefs, allSettings)
        }
    }

    private fun saveAllSettings(prefs: SharedPreferences, settings: Map<String, CameraSettings>) {
        val json = gson.toJson(settings)
        prefs.edit().putString(CAMERA_SETTINGS_KEY, json).apply()
    }

    /**
     * Load all camera settings from SharedPreferences, handling migration from legacy list
     */
    private fun loadAllSettings(prefs: SharedPreferences): Map<String, CameraSettings> {
        // 1. Load existing map
        var map = mutableMapOf<String, CameraSettings>()
        val json = prefs.getString(CAMERA_SETTINGS_KEY, null)
        if (json != null) {
            try {
                val type = object : TypeToken<Map<String, CameraSettings>>() {}.type
                map = gson.fromJson<Map<String, CameraSettings>>(json, type).toMutableMap()
            } catch (e: Exception) {
                map = mutableMapOf()
            }
        }

        // 2. Check for legacy list (Migration)
        if (prefs.contains(KEY_SAVED_CAMERAS)) {
            val savedAddresses = prefs.getString(KEY_SAVED_CAMERAS, "") ?: ""
            val editor = prefs.edit()
            
            if (savedAddresses.isNotBlank()) {
                val addresses = savedAddresses.split(",").filter { it.isNotBlank() }
                for (addr in addresses) {
                    if (!map.containsKey(addr)) {
                        map[addr] = CameraSettings(deviceAddress = addr)
                    }
                }
                
                // Save the merged/migrated map immediately
                val newJson = gson.toJson(map)
                editor.putString(CAMERA_SETTINGS_KEY, newJson)
            }
            
            // Remove the legacy key so this doesn't run again
            editor.remove(KEY_SAVED_CAMERAS)
            editor.apply()
        }

        return map
    }

    // --- Saved Cameras Management ---

    fun getSavedCameras(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return loadAllSettings(prefs).keys.toList()
    }

    fun addSavedCamera(context: Context, deviceAddress: String) {
        // Ensuring it exists in the settings map is enough
        val currentSettings = getSettings(context, deviceAddress)
        // Only save if it wasn't already there? getSettings returns default if not present.
        // We should save it to ensure it's persisted in the map.
        saveSettings(context, currentSettings)
    }

    fun removeSavedCamera(context: Context, deviceAddress: String) {
        removeSettings(context, deviceAddress)
    }
    
    // --- UI Preferences ---
    
    fun isShowLogEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SHOW_LOG, false)
    }

    fun setShowLogEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SHOW_LOG, enabled).apply()
    }
}