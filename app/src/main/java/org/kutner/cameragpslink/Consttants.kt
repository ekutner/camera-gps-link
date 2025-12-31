package org.kutner.cameragpslink

import java.util.UUID

object Constants {
    // Global Constants
    // APP_NAME must remain here as it is used in a string formatting below,
    // but its value is defined in strings.xml
    const val APP_NAME = "Camera Gps Link"

    // Notification Channels
    const val NOTIFICATION_CHANNEL_HIGH = "camera_gps_link_channel_high"
    const val NOTIFICATION_CHANNEL_LOW = "camera_gps_link_channel_low"
    const val NOTIFICATION_CHANNEL_CAMERA_ERROR = "camera_gps_link_error_channel"
    const val NOTIFICATION_CHANNEL_BOOT = "camera_gps_link_boot_channel"

    // Notification IDs
    const val NOTIFICATION_ID_FOREGROUND_SERVICE = 1
    const val NOTIFICATION_ID_PERMISSIONS_REQUIRED = 2
    const val NOTIFICATION_ID_BOOT = 3
    // Reserve some IDs to prevent conflicts with the static notifications
    const val NOTIFICATION_ID_CONNECTED_CAMERA_OFFSET = 10
    const val NOTIFICATION_ID_SHUTTER_ERROR_OFFSET = 11

    const val ACTION_TRIGGER_SHUTTER = "org.kutner.cameragpslink.ACTION_TRIGGER_SHUTTER"

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
    val REMOTE_CONTROL_SERVICE_UUID = UUID.fromString("8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF")
    val REMOTE_CONTROL_CHARACTERISTIC_UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
    val REMOTE_CONTROL_STATUS_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // REMOTE_CONTROL_STATUS_UUID 3-byte sequences
    object StatusPackets {
        // Focus
        val FOCUS_ACQUIRED = byteArrayOf(0x02.toByte(), 0x3F.toByte(), 0x20.toByte())
        val FOCUS_LOST = byteArrayOf(0x02.toByte(), 0x3F.toByte(), 0x00.toByte())

        // Shutter
        val SHUTTER_READY = byteArrayOf(0x02.toByte(), 0xA0.toByte(), 0x00.toByte())
        val SHUTTER_ACTIVE = byteArrayOf(0x02.toByte(), 0xA0.toByte(), 0x20.toByte())

        // Video
        val VIDEO_STARTED = byteArrayOf(0x02.toByte(), 0xD5.toByte(), 0x20.toByte())
        val VIDEO_STOPPED = byteArrayOf(0x02.toByte(), 0xD5.toByte(), 0x00.toByte())

        val REMOTE_CONTROL_DISABLED = byteArrayOf(0x02.toByte(), 0xC3.toByte(), 0x00.toByte())

    }
}