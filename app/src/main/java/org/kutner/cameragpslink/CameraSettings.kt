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
    private const val CAMERA_SETTINGS_KEY = "camera_settings"
    private val gson = Gson()

    /**
     * Save settings for a specific camera
     */
    fun saveSettings(context: Context, settings: CameraSettings) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val allSettings = loadAllSettings(prefs).toMutableMap()
        allSettings[settings.deviceAddress] = settings

        val json = gson.toJson(allSettings)
        prefs.edit().putString(CAMERA_SETTINGS_KEY, json).apply()
    }

    /**
     * Get settings for a specific camera
     */
    fun getSettings(context: Context, deviceAddress: String): CameraSettings {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
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
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val allSettings = loadAllSettings(prefs).toMutableMap()
        allSettings.remove(deviceAddress)

        val json = gson.toJson(allSettings)
        prefs.edit().putString(CAMERA_SETTINGS_KEY, json).apply()
    }

    /**
     * Load all camera settings from SharedPreferences
     */
    private fun loadAllSettings(prefs: SharedPreferences): Map<String, CameraSettings> {
        val json = prefs.getString(CAMERA_SETTINGS_KEY, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, CameraSettings>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}