package org.kutner.cameragpslink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

data class CameraConnection(
    val device: BluetoothDevice,
    var gatt: BluetoothGatt? = null,
    var isConnected: Boolean = false,
    var isConnecting: Boolean = false,
    var locationUpdateRunnable: Runnable? = null
) {
    // Override equals and hashCode to ensure updates are detected
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CameraConnection) return false
        return device.address == other.device.address &&
                isConnected == other.isConnected &&
                isConnecting == other.isConnecting
    }

    override fun hashCode(): Int {
        var result = device.address.hashCode()
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + isConnecting.hashCode()
        return result
    }
}

class CameraSyncService : Service() {

    // --- Constants ---
    companion object {
        private const val TAG = "CameraSyncService"
        private const val PREFS_NAME = "cameragpslinkPrefs"
        private const val PREF_KEY_SAVED_CAMERAS = "saved_cameras"
        private const val SONY_MANUFACTURER_ID = 0x012D
        private val PICT_SERVICE_UUID = UUID.fromString("8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF")

        private val TIME_SERVICE_UUID = UUID.fromString("8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF")
        private val TIME_CHARACTERISTIC_UUID = UUID.fromString("0000CC13-0000-1000-8000-00805F9B34FB")
        private val LOCATION_CHARACTERISTIC_UUID = UUID.fromString("0000DD11-0000-1000-8000-00805F9B34FB")
        private val LOCK_LOCATION_ENDPOINT_UUID = UUID.fromString("0000DD30-0000-1000-8000-00805F9B34FB")
        private val ENABLE_LOCATION_UPDATES_UUID = UUID.fromString("0000DD31-0000-1000-8000-00805F9B34FB")
        private val SHUTTER_SERVICE_UUID = UUID.fromString("8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF")
        private val SHUTTER_CHARACTERISTIC_UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        private const val MANUAL_SCAN_PERIOD: Long = 15000
        private const val LOCATION_UPDATE_INTERVAL: Long = 10000
        private const val REQUEST_MTU_SIZE = 517

        const val ACTION_TRIGGER_SHUTTER = "org.kutner.cameragpslink.ACTION_TRIGGER_SHUTTER"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
    }

    // --- Service components ---
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())

    // --- Bluetooth & Location ---
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleScanner: android.bluetooth.le.BluetoothLeScanner
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sharedPreferences: SharedPreferences

    // --- Multiple camera connections ---
    private val cameraConnections = mutableMapOf<String, CameraConnection>()
    private val autoScanCallbacks = mutableMapOf<String, ScanCallback>()

    // --- State for UI ---
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _rememberedDevice = MutableStateFlow<String?>(null)
    val rememberedDevice: StateFlow<String?> = _rememberedDevice

    private val _connectedCameras = MutableStateFlow<List<CameraConnection>>(emptyList())
    val connectedCameras: StateFlow<List<CameraConnection>> = _connectedCameras

    private val _foundDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val foundDevices: StateFlow<List<BluetoothDevice>> = _foundDevices

    private val _isManualScanning = MutableStateFlow(false)
    val isManualScanning: StateFlow<Boolean> = _isManualScanning

    private var isForegroundServiceStarted = false

    inner class LocalBinder : Binder() {
        fun getService(): CameraSyncService = this@CameraSyncService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initializeBluetoothAndLocation()
        loadSavedCameras()
        if (cameraConnections.isNotEmpty()) {
            startService()
        }
        log("Service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("Service received start command.")

        // Always start foreground immediately to avoid ANR
        startService()

        // Handle shutter action from notification
        if (intent?.action == ACTION_TRIGGER_SHUTTER) {
            val deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
            if (deviceAddress != null) {
                triggerShutter(deviceAddress)
            } else {
                // Trigger all connected cameras
                cameraConnections.values.forEach { connection ->
                    if (connection.isConnected) {
                        triggerShutter(connection.device.address)
                    }
                }
            }
            return START_STICKY
        }

        if (!hasRequiredPermissions()) {
            log("Permissions not granted. Stopping.")
            stopService()
            return START_NOT_STICKY
        }

        // Try to reconnect to saved cameras if we have any
        if (cameraConnections.isNotEmpty()) {
            cameraConnections.values.forEach { connection ->
                if (!connection.isConnected && !connection.isConnecting) {
                    startAutoScan(connection.device.address)
                }
            }
        } else {
            // No saved cameras, stop the service
            log("No saved cameras, stopping service.")
            stopService()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoScans()
        cameraConnections.values.forEach { connection ->
            stopLocationUpdates(connection)
            connection.gatt?.close()
        }
        cameraConnections.clear()
        stopService()
        log("Service destroyed.")
    }

    private fun startService() {
        if (!isForegroundServiceStarted) {
            log("Starting foreground service...")
            startForeground(1, createSearchingNotification())
            isForegroundServiceStarted = true
        }
        else {
            log("Foreground service already started.")
        }
    }

    private fun stopService() {
        if (isForegroundServiceStarted) {
            log("Stopping foreground service...")
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            isForegroundServiceStarted = false
            stopSelf()
        }
    }

    private fun createNotification(): Notification {
        val connectedCount = cameraConnections.values.count { it.isConnected }
        val channelId = if (connectedCount > 0) "camera_sync_channel_high" else "camera_sync_channel_low"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val highChannel = NotificationChannel(
                "camera_sync_channel_high",
                "Camera Link - Connected",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(highChannel)

            val lowChannel = NotificationChannel(
                "camera_sync_channel_low",
                "Camera Link - Searching",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(lowChannel)
        }

        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        val priority = if (connectedCount > 0) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW

        val title = when {
            connectedCount == 0 -> "Searching for cameras..."
            connectedCount == 1 -> "Connected to 1 camera"
            else -> "Connected to $connectedCount cameras"
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // Add shutter button if at least one camera is connected
        if (connectedCount > 0) {
            val shutterIntent = Intent(this, CameraSyncService::class.java).apply {
                action = ACTION_TRIGGER_SHUTTER
            }
            val shutterPendingIntent = PendingIntent.getService(
                this,
                1,
                shutterIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                R.drawable.appicon,
                "Shutter All",
                shutterPendingIntent
            )
        }

        return builder.build()
    }

    private fun createSearchingNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val lowChannel = NotificationChannel(
                "camera_sync_channel_low",
                "Camera GPS Link - Searching",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(lowChannel)
        }

        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        val connectedCount = cameraConnections.values.count { it.isConnected || it.isConnecting }

        return NotificationCompat.Builder(this, "camera_sync_channel_low")
//            .setContentTitle(if (connectedCount > 0) "Connected to camera" else "Searching for cameras...")
            .setContentText("Searching for cameras...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createCameraNotification(connection: CameraConnection, notificationId: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val highChannel = NotificationChannel(
                "camera_sync_channel_high",
                "Camera Link - Connected",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(highChannel)
        }

        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        val shutterIntent = Intent(this, CameraSyncService::class.java).apply {
            action = ACTION_TRIGGER_SHUTTER
            putExtra(EXTRA_DEVICE_ADDRESS, connection.device.address)
        }
        val shutterPendingIntent = PendingIntent.getService(
            this,
            notificationId,
            shutterIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cameraName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            connection.device.name ?: "Camera"
        } else {
            "Camera"
        }

        return NotificationCompat.Builder(this, "camera_sync_channel_high")
            .setContentTitle("Connected to $cameraName")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.appicon,
                "Shutter",
                shutterPendingIntent
            )
            .build()
    }

    private fun showPermissionsRequiredNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(2, notification)
    }

    private fun createAutoScanCallback(deviceAddress: String): ScanCallback {
        return object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                log("Found saved device $deviceAddress. Connecting...")
                bleScanner.stopScan(this)
                autoScanCallbacks.remove(deviceAddress)
                connectToDevice(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                log("Auto-scan failed for $deviceAddress: $errorCode. Retrying...")
                handler.postDelayed({ startAutoScan(deviceAddress) }, 3000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAutoScan(deviceAddress: String) {
        if (!hasRequiredPermissions() || !isAdapterAndScannerReady()) return

        val connection = cameraConnections[deviceAddress] ?: return
        if (connection.isConnected || connection.isConnecting) return

        if (!autoScanCallbacks.containsKey(deviceAddress)) {
            log("Starting auto-scan for $deviceAddress")

            val callback = createAutoScanCallback(deviceAddress)
            autoScanCallbacks[deviceAddress] = callback

            val scanFilter = ScanFilter.Builder().setDeviceAddress(deviceAddress).build()
            val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
            bleScanner.startScan(listOf(scanFilter), scanSettings, callback)

            updateNotification()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAutoScans() {
        autoScanCallbacks.values.forEach { callback ->
            try {
                bleScanner.stopScan(callback)
            } catch (e: Exception) {
                log("Error stopping auto scan: ${e.message}")
            }
        }
        autoScanCallbacks.clear()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (isForegroundServiceStarted) {
            // Update or cancel the main searching notification
//            notificationManager.notify(1, createSearchingNotification())

            // Create individual notifications for each connected camera
            cameraConnections.values.forEachIndexed { index, connection ->
                if (connection.isConnected || connection.isConnecting) {
                    notificationManager.notify(connection.device.address.hashCode(), createCameraNotification(connection, connection.device.address.hashCode()))
                    log("Created notification for ${connection.device.address.hashCode()}")
                } else {
                    notificationManager.cancel(connection.device.address.hashCode())
                    log("Cancelled notification for ${connection.device.address.hashCode()}")
                }
            }
        }
        else {
            log("Service not started cancelling all notifications")
            notificationManager.cancelAll()
        }
    }
    private fun cancelNotification(notificationId: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
        log("Cancelled notification with ID $notificationId")
    }

    // --- Public Functions for UI to Call ---
    @SuppressLint("MissingPermission")
    fun startManualScan() {
        if (!hasRequiredPermissions() || !isAdapterAndScannerReady()) return
        if (_isManualScanning.value) return

        log("Starting manual scan for new cameras...")
        _isManualScanning.value = true
        _foundDevices.value = emptyList()

        val scanFilter = ScanFilter.Builder().setManufacturerData(SONY_MANUFACTURER_ID, byteArrayOf()).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner.startScan(listOf(scanFilter), scanSettings, manualScanCallback)

        handler.postDelayed({ stopManualScan() }, MANUAL_SCAN_PERIOD)
    }


    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        val address = device.address

        // Don't connect if already connected or connecting
        cameraConnections[address]?.let { connection ->
            if (connection.isConnected || connection.isConnecting) {
                log("Already connected or connecting to ${device.name ?: address}")
                return
            }
        }

        log("Connecting to ${device.name ?: device.address}...")

        val connection = cameraConnections.getOrPut(address) {
            CameraConnection(device = device)
        }

        connection.isConnecting = true
        connection.gatt = device.connectGatt(this, false, createGattCallback(address), BluetoothDevice.TRANSPORT_LE)

        // Save immediately when connecting to a new camera
        saveCameras()
        updateConnectionsList()
        startService()
        updateNotification()
    }

    fun forgetDevice(deviceAddress: String) {
        log("Forgetting device $deviceAddress...")

        cameraConnections[deviceAddress]?.let { connection ->
            // Stop location updates
            stopLocationUpdates(connection)

            // Close GATT connection
            connection.gatt?.let { gatt ->
                try {
                    gatt.disconnect()
                    gatt.close()
                    log("Closed GATT connection for $deviceAddress")
                } catch (e: Exception) {
                    log("Error closing GATT: ${e.message}")
                }
            }
            connection.gatt = null
            cancelNotification(connection.device.address.hashCode())

            // Stop auto-scan for this device
            autoScanCallbacks[deviceAddress]?.let { callback ->
                try {
                    bleScanner.stopScan(callback)
                    log("Stopped auto-scan for $deviceAddress")
                } catch (e: Exception) {
                    log("Error stopping auto-scan: ${e.message}")
                }
                autoScanCallbacks.remove(deviceAddress)
            }

            // Remove from connections map
            cameraConnections.remove(deviceAddress)

            // Save updated camera list
            saveCameras()

            // Update UI
            updateConnectionsList()


            // Check if any camera is still connected

            log("Device $deviceAddress forgotten successfully")

            // Stop service if no more cameras
            if (cameraConnections.isEmpty()) {
                log("No more cameras, stopping service")
                stopService()
            }
        } ?: log("Device $deviceAddress not found in connections")
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    fun triggerShutter(deviceAddress: String? = null) {
        if (deviceAddress != null) {
            // Trigger specific camera
            val connection = cameraConnections[deviceAddress]
            if (connection == null || !connection.isConnected) {
                log("Cannot trigger shutter: Camera $deviceAddress not connected")
                return
            }

            val gatt = connection.gatt ?: return
            val shutterChar = gatt.getService(SHUTTER_SERVICE_UUID)?.getCharacteristic(SHUTTER_CHARACTERISTIC_UUID)
            if (shutterChar == null) {
                log("Shutter characteristic not found for $deviceAddress")
                return
            }

            log("Triggering shutter for ${connection.device.name ?: deviceAddress}...")
            writeCharacteristic(gatt, shutterChar, byteArrayOf(0x01, 0x09), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            handler.postDelayed({
                writeCharacteristic(gatt, shutterChar, byteArrayOf(0x01, 0x08), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                log("Shutter triggered for ${connection.device.name ?: deviceAddress}")
            }, 100)
        }
    }

    // --- Core Logic ---
    private fun createGattCallback(deviceAddress: String): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val connection = cameraConnections[deviceAddress] ?: return

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connection.isConnected = true
                    connection.isConnecting = false
                    log("Connected to ${gatt.device.name ?: deviceAddress}. Requesting MTU...")
                    handler.postDelayed({ gatt.requestMtu(REQUEST_MTU_SIZE) }, 600)

                    // Update UI immediately
                    startService()      // make sure the service is started
                    updateConnectionsList()
                    updateNotification()

                    // Stop auto-scan for this device
                    autoScanCallbacks[deviceAddress]?.let { callback ->
                        bleScanner.stopScan(callback)
                        autoScanCallbacks.remove(deviceAddress)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("Disconnected from ${gatt.device.name ?: deviceAddress}.")
                    connection.isConnected = false
                    connection.isConnecting = false
                    stopLocationUpdates(connection)

                    // Update UI immediately
                    updateConnectionsList()
                    updateNotification()

                    // Restart auto-scan for this device
                    handler.postDelayed({
                        startAutoScan(deviceAddress)
                    }, 1000)
                }
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                log("MTU set to $mtu for ${gatt.device.name ?: deviceAddress}.")
                if (gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
                    gatt.discoverServices()
                } else {
                    log("Device not bonded. Starting pairing...")
                    gatt.device.createBond()
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                log("Services discovered for ${gatt.device.name ?: deviceAddress}. Locking endpoint...")
                lockLocationEndpoint(gatt, deviceAddress)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    when (characteristic.uuid) {
                        LOCK_LOCATION_ENDPOINT_UUID -> {
                            log("Endpoint locked for $deviceAddress. Enabling location updates...")
                            enableLocationUpdates(gatt, deviceAddress)
                        }
                        ENABLE_LOCATION_UPDATES_UUID -> {
                            log("Location updates enabled for $deviceAddress. Syncing time...")
                            synchronizeTime(gatt, deviceAddress)
                        }
                        TIME_CHARACTERISTIC_UUID -> {
                            log("Time synced for $deviceAddress. Starting location data stream.")
                            startLocationUpdates(deviceAddress)
                        }
                    }
                } else {
                    log("Write failed for ${characteristic.uuid} on $deviceAddress with status $status")
                }
            }
        }
    }

    private val manualScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val currentDevices = _foundDevices.value.toMutableList()
            if (currentDevices.none { it.address == result.device.address } &&
                !cameraConnections.containsKey(result.device.address)) {
                log("Found manual device: ${result.device.name}")
                currentDevices.add(result.device)
                _foundDevices.value = currentDevices
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Manual scan failed: $errorCode")
            _isManualScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopManualScan() {
        if (_isManualScanning.value && isAdapterAndScannerReady()) {
            log("Manual scan stopped.")
            _isManualScanning.value = false
            bleScanner.stopScan(manualScanCallback)
        }
    }

    // --- Characteristic Writes ---
    @SuppressLint("MissingPermission")
    private fun lockLocationEndpoint(gatt: BluetoothGatt, deviceAddress: String) {
        val lockChar = gatt.getService(PICT_SERVICE_UUID)?.getCharacteristic(LOCK_LOCATION_ENDPOINT_UUID)
        if (lockChar == null) {
            log("Lock Location Endpoint characteristic (dd30) not found for $deviceAddress!")
            return
        }
        writeCharacteristic(gatt, lockChar, byteArrayOf(0x01), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationUpdates(gatt: BluetoothGatt, deviceAddress: String) {
        val enableChar = gatt.getService(PICT_SERVICE_UUID)?.getCharacteristic(ENABLE_LOCATION_UPDATES_UUID)
        if (enableChar == null) {
            log("Enable Location Updates characteristic (dd31) not found for $deviceAddress!")
            return
        }
        writeCharacteristic(gatt, enableChar, byteArrayOf(0x01), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun synchronizeTime(gatt: BluetoothGatt, deviceAddress: String) {
        val timeChar = gatt.getService(TIME_SERVICE_UUID)?.getCharacteristic(TIME_CHARACTERISTIC_UUID)
        if (timeChar == null) {
            log("Time characteristic not found for $deviceAddress! Skipping sync.")
            startLocationUpdates(deviceAddress)
            return
        }
        val cal = Calendar.getInstance()
        val localTimezone = TimeZone.getDefault()
        val currentTimeMillis = System.currentTimeMillis()
        val dstActive = if (localTimezone.inDaylightTime(Date(currentTimeMillis))) {
            1.toShort()
        } else {
            0.toShort()
        }

        var (tzOffsetHours, tzOffsetMinutes) = getRawUtcOffset()
        if (tzOffsetHours < 0 ) {
            tzOffsetHours = 24 + tzOffsetHours
        }
        val payload = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x0c, 0x00, 0x00.toByte()))
            .putShort(cal.get(Calendar.YEAR).toShort())
            .put((cal.get(Calendar.MONTH) + 1).toByte())
            .put(cal.get(Calendar.DAY_OF_MONTH).toByte())
            .put(cal.get(Calendar.HOUR_OF_DAY).toByte())
            .put(cal.get(Calendar.MINUTE).toByte())
            .put(cal.get(Calendar.SECOND).toByte())
            .put(dstActive.toByte())
            .put(tzOffsetHours.toByte())
            .put(tzOffsetMinutes.toByte())
            .array()

        writeCharacteristic(gatt, timeChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    fun getRawUtcOffset(): Pair<Int, Int> {
        val timeZone: TimeZone = TimeZone.getDefault()
        val rawOffsetMillis: Int = timeZone.rawOffset

        val totalSeconds = (rawOffsetMillis / 1000)

        val sign = if (totalSeconds < 0) -1 else 1
        val absSeconds = abs(totalSeconds)

        val hours = (absSeconds / 3600) * sign
        val minutes = (absSeconds % 3600) / 60

        return Pair(hours, minutes)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(deviceAddress: String) {
        if (!hasRequiredPermissions()) return

        val connection = cameraConnections[deviceAddress] ?: return

        log("Starting to stream location data to $deviceAddress...")

        val runnable = object : Runnable {
            override fun run() {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            sendLocationData(location, deviceAddress)
                        }
                        handler.postDelayed(this, LOCATION_UPDATE_INTERVAL)
                    }
            }
        }

        connection.locationUpdateRunnable = runnable
        handler.post(runnable)
    }

    @SuppressLint("MissingPermission")
    private fun sendLocationData(location: Location, deviceAddress: String) {
        val connection = cameraConnections[deviceAddress] ?: return
        val gatt = connection.gatt ?: return

        val locationChar = gatt.getService(PICT_SERVICE_UUID)?.getCharacteristic(LOCATION_CHARACTERISTIC_UUID)
        if (locationChar == null) {
            log("Location characteristic not found for $deviceAddress.")
            return
        }

        val buffer = ByteBuffer.allocate(95).order(ByteOrder.BIG_ENDIAN)
        val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val localTimezone = TimeZone.getDefault()
        val currentTimeMillis = System.currentTimeMillis()

        buffer.putShort(0x005D)
        buffer.put(byteArrayOf(0x08, 0x02, 0xFC.toByte()))
        buffer.put(0x03.toByte())
        buffer.put(byteArrayOf(0x00, 0x00, 0x10, 0x10, 0x10))
        buffer.putInt((location.latitude * 10_000_000).toInt())
        buffer.putInt((location.longitude * 10_000_000).toInt())
        buffer.putShort(utcCalendar.get(Calendar.YEAR).toShort())
        buffer.put((utcCalendar.get(Calendar.MONTH) + 1).toByte())
        buffer.put(utcCalendar.get(Calendar.DAY_OF_MONTH).toByte())
        buffer.put(utcCalendar.get(Calendar.HOUR_OF_DAY).toByte())
        buffer.put(utcCalendar.get(Calendar.MINUTE).toByte())
        buffer.put(utcCalendar.get(Calendar.SECOND).toByte())
        buffer.position(91)
        buffer.putShort((localTimezone.rawOffset / 60000).toShort())
        val dstOffset = if (localTimezone.inDaylightTime(Date(currentTimeMillis))) {
            (localTimezone.dstSavings / 60000).toShort()
        } else {
            0.toShort()
        }
        buffer.putShort(dstOffset)

        val payload = buffer.array()

        writeCharacteristic(gatt, locationChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, payload: ByteArray, writeType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, payload, writeType)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    // --- Camera Management ---
    private fun saveCameras() {
        val addresses = cameraConnections.keys.joinToString(",")
        sharedPreferences.edit()
            .putString(PREF_KEY_SAVED_CAMERAS, addresses)
            .commit()
        _rememberedDevice.value = if (addresses.isNotEmpty()) addresses else null
    }

    @SuppressLint("MissingPermission")
    private fun loadSavedCameras() {
        val savedAddresses = sharedPreferences.getString(PREF_KEY_SAVED_CAMERAS, "") ?: ""
        if (savedAddresses.isNotEmpty()) {
            savedAddresses.split(",").forEach { address ->
                if (address.isNotBlank()) {
                    try {
                        if (!::bluetoothAdapter.isInitialized) {
                            log("Bluetooth adapter not initialized yet, deferring camera loading")
                            return@forEach
                        }
                        val device = bluetoothAdapter.getRemoteDevice(address)
                        cameraConnections[address] = CameraConnection(device = device)
                        log("Loaded saved camera: ${device.name ?: address}")
                    } catch (e: Exception) {
                        log("Failed to load saved camera $address: ${e.message}")
                    }
                }
            }
            _rememberedDevice.value = savedAddresses
            updateConnectionsList()
        }
    }

    private fun updateConnectionsList() {
        // Create a completely new list with new references to force update
        val newList = cameraConnections.values.map { conn ->
            CameraConnection(
                device = conn.device,
                gatt = conn.gatt,
                isConnected = conn.isConnected,
                isConnecting = conn.isConnecting,
                locationUpdateRunnable = conn.locationUpdateRunnable
            )
        }
        _connectedCameras.value = newList
        log("Updated connections list: ${cameraConnections.size} cameras, connected: ${newList.count { it.isConnected }}, details: ${newList.map { "${it.device.address}: connected=${it.isConnected}, connecting=${it.isConnecting}" }}")
    }

    // --- Utility Methods ---
    private fun initializeBluetoothAndLocation() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter.bluetoothLeScanner
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    private fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_ADMIN)
        }
        return requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun isAdapterAndScannerReady(): Boolean {
        if (!::bluetoothAdapter.isInitialized || !bluetoothAdapter.isEnabled) {
            log("Bluetooth adapter not initialized or not enabled.")
            return false
        }
        if (!::bleScanner.isInitialized) {
            log("BLE scanner not initialized.")
            return false
        }
        return true
    }

    private fun stopLocationUpdates(connection: CameraConnection) {
        connection.locationUpdateRunnable?.let {
            handler.removeCallbacks(it)
            connection.locationUpdateRunnable = null
            log("Location updates stopped for ${connection.device.address}.")
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val currentLog = _log.value.toMutableList()
        currentLog.add(0, "[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $message")
        if (currentLog.size > 200) {
            currentLog.removeAt(currentLog.size - 1)
        }
        _log.value = currentLog
    }

    fun clearLog() {
        _log.value = emptyList()
        log("Log cleared.")
    }

    fun getLogAsString(): String {
        return _log.value.joinToString("\n")
    }

}

