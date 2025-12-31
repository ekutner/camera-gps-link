package org.kutner.cameragpslink

enum class RemoteCommand(val commandBytes: ByteArray, val releaseBytes: ByteArray? = null) {
    // Shutter commands
    FULL_SHUTTER_BUTTON(byteArrayOf(0x01, 0x09), byteArrayOf(0x01, 0x08)),
    // Half-shutter commands (for shutter half-press to focus)
    HALF_SHUTTER_BUTTON(byteArrayOf(0x01, 0x07), byteArrayOf(0x01, 0x06)),

    // C1 commands
    C1_BUTTON(byteArrayOf(0x01, 0x21), byteArrayOf(0x01, 0x20)),

    // AutoFocus commands
    AF_ON_BUTTON(byteArrayOf(0x01, 0x15), byteArrayOf(0x01, 0x14)),

    // Record commands (now with press and release)
    RECORD_BUTTON(byteArrayOf(0x01, 0x0f), byteArrayOf(0x01, 0x0e)),

    // Zoom commands (corrected)
    ZOOM_TELE_BUTTON(byteArrayOf(0x02, 0x45, 0x50), byteArrayOf(0x02, 0x44, 0x00)),
    ZOOM_WIDE_BUTTON(byteArrayOf(0x02, 0x47, 0x50), byteArrayOf(0x02, 0x46, 0x00)),

    // Focus commands (corrected)
    FOCUS_FAR_BUTTON(byteArrayOf(0x02, 0x6d.toByte(), 0x50), byteArrayOf(0x02, 0x6c.toByte(), 0x00)),
    FOCUS_NEAR_BUTTON(byteArrayOf(0x02, 0x6b.toByte(), 0x50), byteArrayOf(0x02, 0x6a.toByte(), 0x00)),


    REMOTE_CONTROL_PROBE(byteArrayOf(0x01, 0x05));

    /**
     * Returns true if this command has an automatic release command
     */
    fun hasRelease(): Boolean = releaseBytes != null
}