package org.kutner.cameragpslink

import java.util.UUID

object Constants {
    // Global Constants
    const val APP_NAME = "Camera Gps Link"

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
    const val CHANNEL_NAME_SHUTTER_ERROR = "Camera Shutter Error"

    // Notification IDs
    const val NOTIFICATION_ID_FOREGROUND_SERVICE = 1
    const val NOTIFICATION_ID_PERMISSIONS_REQUIRED = 2
    const val NOTIFICATION_ID_SHUTTER_ERROR_OFFSET = 1


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
    const val NOTIFICATION_BOOT_MESSAGE = "Tap to activate $APP_NAME after the phone has rebooted"
    const val NOTIFICATION_SEARCHING_TITLE = "Searching for cameras..."
    const val NOTIFICATION_SEARCHING_MESSAGE = "Do not remove this notification!"
    const val NOTIFICATION_SEARCH_LONG_MESSAGE = "Do not remove this notification!\nIt is required for the app to connect to the camera while in the background."
    const val HELP_PAGE_URL= "https://github.com/ekutner/camera-gps-link?tab=readme-ov-file#camera-gps-link"

    // Action Labels
    const val ACTION_SHUTTER = "Shutter"
    const val ACTION_TRIGGER_SHUTTER = "org.kutner.cameragpslink.ACTION_TRIGGER_SHUTTER"


    // Default Camera Name
    const val DEFAULT_CAMERA_NAME = "Camera"
    const val UNKNOWN_CAMERA_NAME = "Unknown Camera"

    // Bluetooth Related Constants
    const val EXTRA_DEVICE_ADDRESS = "device_address"
    const val SONY_MANUFACTURER_ID = 0x012D
    const val MANUAL_SCAN_PERIOD: Long = 15000
    const val LOCATION_UPDATE_INTERVAL: Long = 10000
    const val REQUEST_MTU_SIZE = 517

    const val MAX_BT_RETRIES = 5
    val TIME_SERVICE_UUID = UUID.fromString("8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF")
    val TIME_CHARACTERISTIC_UUID = UUID.fromString("0000CC13-0000-1000-8000-00805F9B34FB")
    val PICT_SERVICE_UUID = UUID.fromString("8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF")
    val LOCATION_CHARACTERISTIC_UUID = UUID.fromString("0000DD11-0000-1000-8000-00805F9B34FB")
    val LOCK_LOCATION_ENDPOINT_UUID = UUID.fromString("0000DD30-0000-1000-8000-00805F9B34FB")
    val ENABLE_LOCATION_UPDATES_UUID = UUID.fromString("0000DD31-0000-1000-8000-00805F9B34FB")
    val SHUTTER_SERVICE_UUID = UUID.fromString("8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF")
    val SHUTTER_CHARACTERISTIC_UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")

}