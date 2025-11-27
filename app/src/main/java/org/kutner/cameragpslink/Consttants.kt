package org.kutner.cameragpslink

object Constants {
    // Notification Channels
    const val CHANNEL_CAMERA_SYNC_HIGH = "camera_sync_channel_high"
    const val CHANNEL_CAMERA_SYNC_LOW = "camera_sync_channel_low"
    const val CHANNEL_CAMERA_ERROR = "camera_error_channel"
    const val CHANNEL_CAMERA_SYNC_BOOT = "camera_sync_boot_channel"

    // Notification Channel Names
    const val CHANNEL_NAME_CONNECTED = "Camera Link - Connected"
    const val CHANNEL_NAME_SEARCHING = "Camera Link - Searching"
    const val CHANNEL_NAME_ERRORS = "Camera Errors"
    const val CHANNEL_NAME_BOOT = "Camera Sync Boot"

    // Error Messages
    const val ERROR_SHUTTER_TITLE = "Shutter Release Failed"
    const val ERROR_SHUTTER_MESSAGE = "Enable \"Bluetooth Rmt Ctrl\" in the camera settings!"
    const val ERROR_SHUTTER_MESSAGE_LONG = "Enable \"Bluetooth Rmt Ctrl\" in the camera settings to use remote shutter control."

    // Notification Messages
    const val NOTIFICATION_SEARCHING = "Searching for cameras..."
    const val NOTIFICATION_CONNECTED_SINGLE = "Connected to 1 camera"
    const val NOTIFICATION_CONNECTED_MULTIPLE = "Connected to %d cameras"
    const val NOTIFICATION_CONNECTED_TO = "Connected to %s"
    const val NOTIFICATION_SHUTTER_FAILED = "Shutter Failed - %s"
    const val NOTIFICATION_BOOT_TITLE = "Camera Gps Link"
    const val NOTIFICATION_BOOT_MESSAGE = "Tap to connect to your saved camera"

    // Action Labels
    const val ACTION_SHUTTER = "Shutter"
    const val ACTION_SHUTTER_ALL = "Shutter All"

    // Bluetooth Error Codes
    const val GATT_ERROR_REMOTE_CONTROL_DISABLED = 144

    // Default Camera Name
    const val DEFAULT_CAMERA_NAME = "Camera"
    const val UNKNOWN_CAMERA_NAME = "Unknown Camera"
}