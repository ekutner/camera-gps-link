package org.kutner.cameragpslink

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class CameraSettings(
    val deviceAddress: String,
    val connectionMode: Int = 1, // 1 = Mode 1, 2 = Mode 2
    val quickConnectEnabled: Boolean = false,
    val quickConnectDurationMinutes: Int = 5,
    val lastDisconnectTimestamp: Long? = null
)

object AppSettingsManager {
    const val PREFS_NAME = "cameragpslinkPrefs"
    private const val CAMERA_SETTINGS_KEY = "camera_settings"
    private const val KEY_SHOW_LOG = "show_log"
    private const val KEY_SELECTED_LANGUAGE = "selected_language"

    private val gson = Gson()

    fun saveCameraSettings(context: Context, settings: CameraSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val allSettings = loadAllCamerasSettings(prefs).toMutableMap()
        allSettings[settings.deviceAddress] = settings
        saveAllSettings(prefs, allSettings)
    }

    fun getCameraSettings(context: Context, deviceAddress: String): CameraSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val allSettings = loadAllCamerasSettings(prefs)
        val settings = allSettings[deviceAddress] ?: CameraSettings(deviceAddress = deviceAddress)
        return settings.copy(
            connectionMode = settings.connectionMode.coerceAtLeast(1).coerceAtMost(2), // Ensure connectionMode is between 1 and 2 ?:)
            quickConnectDurationMinutes = settings.quickConnectDurationMinutes.coerceAtLeast(0).coerceAtMost(720),
            quickConnectEnabled = settings.quickConnectEnabled.coerceAtLeast(false).coerceAtMost(true),
            lastDisconnectTimestamp = settings.lastDisconnectTimestamp?.coerceAtLeast(0)
        )
    }

    // Renamed and updated to handle all settings
    fun updateCameraSettings(context: Context, deviceAddress: String, mode: Int, quickConnectEnabled: Boolean, durationMinutes: Int) {
        val currentSettings = getCameraSettings(context, deviceAddress)
        val updatedSettings = currentSettings.copy(
            connectionMode = mode,
            quickConnectEnabled = quickConnectEnabled,
            quickConnectDurationMinutes = durationMinutes
        )
        saveCameraSettings(context, updatedSettings)
    }

    fun updateDisconnectTimestamp(context: Context, deviceAddress: String, timestamp: Long) {
        val currentSettings = getCameraSettings(context, deviceAddress)
        val updatedSettings = currentSettings.copy(lastDisconnectTimestamp = timestamp)
        saveCameraSettings(context, updatedSettings)
    }

    fun removeSettings(context: Context, deviceAddress: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val allSettings = loadAllCamerasSettings(prefs).toMutableMap()
        if (allSettings.remove(deviceAddress) != null) {
            saveAllSettings(prefs, allSettings)
        }
    }

    private fun saveAllSettings(prefs: SharedPreferences, settings: Map<String, CameraSettings>) {
        val json = gson.toJson(settings)
        prefs.edit().putString(CAMERA_SETTINGS_KEY, json).apply()
    }

    private fun loadAllCamerasSettings(prefs: SharedPreferences): Map<String, CameraSettings> {
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
        return map
    }

    // --- Saved Cameras Management ---
    fun getSavedCameras(context: Context): List<String> = getSavedCamerasMap(context).keys.toList()

    // Helper to avoid re-loading for list
    private fun getSavedCamerasMap(context: Context): Map<String, CameraSettings> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return loadAllCamerasSettings(prefs)
    }

    fun addSavedCamera(context: Context, deviceAddress: String) {
        val currentSettings = getCameraSettings(context, deviceAddress)
        saveCameraSettings(context, currentSettings)
    }

    fun removeSavedCamera(context: Context, deviceAddress: String) {
        removeSettings(context, deviceAddress)
    }

    fun hasSavedCamera(context: Context, deviceAddress: String): Boolean {
        return getSavedCameras(context).contains(deviceAddress)
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

    fun getSelectedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_LANGUAGE, "") ?: ""
    }

    fun setSelectedLanguage(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_LANGUAGE, languageCode).apply()
    }
}