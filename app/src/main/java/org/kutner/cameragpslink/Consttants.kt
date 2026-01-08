package org.kutner.cameragpslink

import java.util.UUID

object Constants {
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
    val SONY_DEVICE_TYPE_CAMERA = byteArrayOf(0x03, 0x00)
    const val MANUAL_SCAN_PERIOD: Long = 10000
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
    val REMOTE_CONTROL_STATUS_UUID: UUID = UUID.fromString("0000FF02-0000-1000-8000-00805f9b34fb")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val PROTOCOL_VERSION_CREATORS_APP = 0x65

}

// Remote Control Command Bytes
enum class RemoteControlCommand(val bytes: ByteArray) {
    // Shutter commands
    FULL_SHUTTER_DOWN(byteArrayOf(0x01, 0x09)),
    FULL_SHUTTER_UP(byteArrayOf(0x01, 0x08)),

    HALF_SHUTTER_DOWN(byteArrayOf(0x01, 0x07)),
    HALF_SHUTTER_UP(byteArrayOf(0x01, 0x06)),

    // C1 commands
    C1_DOWN(byteArrayOf(0x01, 0x21)),
    C1_UP(byteArrayOf(0x01, 0x20)),

    // AutoFocus commands
    AF_ON_DOWN(byteArrayOf(0x01, 0x15)),
    AF_ON_UP(byteArrayOf(0x01, 0x14)),

    // Record commands
    RECORD_DOWN(byteArrayOf(0x01, 0x0f)),
    RECORD_UP(byteArrayOf(0x01, 0x0e)),

    // Zoom commands
    ZOOM_TELE_DOWN(byteArrayOf(0x02, 0x45, 0x50)),
    ZOOM_TELE_UP(byteArrayOf(0x02, 0x44, 0x00)),

    ZOOM_WIDE_DOWN(byteArrayOf(0x02, 0x47, 0x50)),
    ZOOM_WIDE_UP(byteArrayOf(0x02, 0x46, 0x00)),

    // Focus commands
    FOCUS_FAR_DOWN(byteArrayOf(0x02, 0x6d.toByte(), 0x50)),
    FOCUS_FAR_UP(byteArrayOf(0x02, 0x6c.toByte(), 0x00)),

    FOCUS_NEAR_DOWN(byteArrayOf(0x02, 0x6b.toByte(), 0x50)),
    FOCUS_NEAR_UP(byteArrayOf(0x02, 0x6a.toByte(), 0x00)),

    // Probe command
//    REMOTE_CONTROL_PROBE(byteArrayOf(0x01, 0x05));
    REMOTE_CONTROL_PROBE(byteArrayOf(0x01, 0x06));
}

// Remote control status bytes
enum class CameraStatus(val bytes: ByteArray) {
    // Focus
    FOCUS_ACQUIRED(byteArrayOf(0x02, 0x3F.toByte(), 0x20)),
    FOCUS_LOST(byteArrayOf(0x02, 0x3F.toByte(), 0x00)),

    // Shutter
    SHUTTER_READY(byteArrayOf(0x02, 0xA0.toByte(), 0x00)),
    SHUTTER_ACTIVE(byteArrayOf(0x02, 0xA0.toByte(), 0x20)),

    // Video
    VIDEO_STARTED(byteArrayOf(0x02, 0xD5.toByte(), 0x20)),
    VIDEO_STOPPED(byteArrayOf(0x02, 0xD5.toByte(), 0x00)),

    REMOTE_CONTROL_DISABLED(byteArrayOf(0x02, 0xC3.toByte(), 0x00));

    companion object {
        fun fromBytes(bytes: ByteArray): CameraStatus? {
            return entries.find { it.bytes.contentEquals(bytes) }
        }
    }
}