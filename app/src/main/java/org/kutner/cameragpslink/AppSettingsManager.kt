package org.kutner.cameragpslink

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class CameraSettings(
    val deviceAddress: String,
    val protocolVersion: Int = Constants.PROTOCOL_VERSION_CREATORS_APP,
    val connectionMode: Int = 1,
    val quickConnectEnabled: Boolean = false,
    val quickConnectDurationMinutes: Int = 5,
    val lastDisconnectTimestamp: Long? = null,
    val enableHalfShutterPress: Boolean = false,
    val customName: String? = null
)

object AppSettingsManager {
    const val PREFS_NAME = "cameragpslinkPrefs"
    private const val CAMERA_SETTINGS_KEY = "camera_settings"
    private const val KEY_SHOW_LOG = "show_log"
    private const val KEY_SELECTED_LANGUAGE = "selected_language"
    private const val KEY_INSTALL_TIME = "install_time"
    private const val KEY_RATING_PROMPT_TIME = "last_rating_prompt_time"

    private val gson = Gson()

    // In-memory cache using LinkedHashMap to preserve order
    private var cameraSettingsCache: LinkedHashMap<String, CameraSettings>? = null

    // Ensure cache is loaded
    private fun ensureCacheLoaded(context: Context) {
        if (cameraSettingsCache == null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cameraSettingsCache = loadAllCamerasSettingsFromStorage(prefs)
        }
    }

    // Persist cache to storage
    private fun persistCache(context: Context) {
        val cache = cameraSettingsCache ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Convert LinkedHashMap to List to guarantee order in JSON
        val settingsList = cache.values.toList()
        val json = gson.toJson(settingsList)
        prefs.edit().putString(CAMERA_SETTINGS_KEY, json).apply()
    }

    private fun saveCameraSettings(context: Context, settings: CameraSettings) {
        ensureCacheLoaded(context)

        // Update or add to cache
        cameraSettingsCache!![settings.deviceAddress] = settings

        // Persist to storage
        persistCache(context)
    }

    fun getCameraSettings(context: Context, deviceAddress: String): CameraSettings {
        ensureCacheLoaded(context)

        val settings = cameraSettingsCache!![deviceAddress]
            ?: CameraSettings(deviceAddress = deviceAddress)

        return settings.copy(
            connectionMode = settings.connectionMode.coerceAtLeast(1).coerceAtMost(2),
            quickConnectDurationMinutes = settings.quickConnectDurationMinutes.coerceAtLeast(0).coerceAtMost(720),
            quickConnectEnabled = settings.quickConnectEnabled,
            lastDisconnectTimestamp = settings.lastDisconnectTimestamp?.coerceAtLeast(0),
            enableHalfShutterPress = settings.enableHalfShutterPress,
            protocolVersion = if (settings.protocolVersion == 0) Constants.PROTOCOL_VERSION_CREATORS_APP else settings.protocolVersion,
            customName = settings.customName ?: ""
        )
    }

    fun updateCameraSettings(context: Context, deviceAddress: String, mode: Int? = null, quickConnectEnabled: Boolean? = null,
                             durationMinutes: Int? = null, enableHalfShutterPress: Boolean? = null, customName: String? = null) {
        val currentSettings = getCameraSettings(context, deviceAddress)
        val updatedSettings = currentSettings.copy(
            connectionMode = mode ?: currentSettings.connectionMode,
            quickConnectEnabled = quickConnectEnabled ?: currentSettings.quickConnectEnabled,
            quickConnectDurationMinutes = durationMinutes ?: currentSettings.quickConnectDurationMinutes,
            enableHalfShutterPress = enableHalfShutterPress ?: currentSettings.enableHalfShutterPress,
            customName = customName?.trim()?.ifBlank { null }
        )
        saveCameraSettings(context, updatedSettings)
    }

    fun reorderCameras(context: Context, orderedAddresses: List<String>) {
        ensureCacheLoaded(context)

        val oldCache = cameraSettingsCache!!

        // Create new LinkedHashMap with the specified order
        val newCache = LinkedHashMap<String, CameraSettings>()

        // Add cameras in the new order
        orderedAddresses.forEach { address ->
            oldCache[address]?.let { settings ->
                newCache[address] = settings
            }
        }

        // Add any cameras that weren't in the ordered list (shouldn't happen, but safety)
        oldCache.forEach { (address, settings) ->
            if (!newCache.containsKey(address)) {
                newCache[address] = settings
            }
        }

        // Replace cache and persist
        cameraSettingsCache = newCache
        persistCache(context)
    }

    fun updateDisconnectTimestamp(context: Context, deviceAddress: String, timestamp: Long) {
        val currentSettings = getCameraSettings(context, deviceAddress)
        val updatedSettings = currentSettings.copy(lastDisconnectTimestamp = timestamp)
        saveCameraSettings(context, updatedSettings)
    }

    fun removeSettings(context: Context, deviceAddress: String) {
        ensureCacheLoaded(context)

        if (cameraSettingsCache!!.remove(deviceAddress) != null) {
            persistCache(context)
        }
    }

    private fun loadAllCamerasSettingsFromStorage(prefs: SharedPreferences): LinkedHashMap<String, CameraSettings> {
        val json = prefs.getString(CAMERA_SETTINGS_KEY, null) ?: return LinkedHashMap()

        try {
            // Try to parse as list first (new format - maintains order)
            val listType = object : TypeToken<List<CameraSettings>>() {}.type
            val list = gson.fromJson<List<CameraSettings>>(json, listType)
            if (list != null) {
                // Convert list to LinkedHashMap, preserving order from the list
                val map = LinkedHashMap<String, CameraSettings>()
                list.forEach { settings ->
                    map[settings.deviceAddress] = settings
                }

                return map
            }
        } catch (e: Exception) {
            // Not a list, try as map
        }

        try {
            // Try to parse as map (old format)
            val mapType = object : TypeToken<LinkedHashMap<String, CameraSettings>>() {}.type
            val oldMap = gson.fromJson<LinkedHashMap<String, CameraSettings>>(json, mapType)

            if (oldMap != null) {
                // Migrate to new format by persisting as list
                val settingsList = oldMap.values.toList()
                val newJson = gson.toJson(settingsList)
                prefs.edit().putString(CAMERA_SETTINGS_KEY, newJson).apply()

                return oldMap
            }
        } catch (e: Exception) {
            // Parsing failed
        }

        return LinkedHashMap()
    }

    // --- Saved Cameras Management ---
    fun getSavedCameras(context: Context): List<String> {
        ensureCacheLoaded(context)
        // Return keys as a list - LinkedHashMap preserves insertion/reorder order
        return cameraSettingsCache!!.keys.toList()
    }

    fun addSavedCamera(context: Context, deviceAddress: String, protocolVersion: Int) {
        val newSettings = CameraSettings(
            deviceAddress = deviceAddress,
            protocolVersion = protocolVersion
        )
        saveCameraSettings(context, newSettings)
    }

    fun removeSavedCamera(context: Context, deviceAddress: String) {
        removeSettings(context, deviceAddress)
    }

    fun hasSavedCamera(context: Context, deviceAddress: String): Boolean {
        ensureCacheLoaded(context)
        return cameraSettingsCache!!.containsKey(deviceAddress)
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

    // --- Camera Name Resolution ---
    fun getCameraName(context: Context, deviceAddress: String, deviceName: String?, useUnknownName: Boolean=true): String {
        val settings = getCameraSettings(context, deviceAddress)
        return if (settings.customName != null)
            settings.customName.ifEmpty { deviceName ?: if (useUnknownName) context.getString(R.string.unknown_camera_name)  else "" }
        else
            deviceName ?: context.getString(R.string.unknown_camera_name)

    }

    // --- Rating Prompt Storage ---
    fun getInstallTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedTime = prefs.getLong(KEY_INSTALL_TIME, 0L)

        return if (savedTime == 0L) {
            val currentTime = System.currentTimeMillis()
            prefs.edit().putLong(KEY_INSTALL_TIME, currentTime).apply()
            currentTime
        } else {
            savedTime
        }
    }

    fun setRatingPromptTime(context: Context, time: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_RATING_PROMPT_TIME, time).apply()
    }

    fun getRatingPromptTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_RATING_PROMPT_TIME, 0L)
    }
}