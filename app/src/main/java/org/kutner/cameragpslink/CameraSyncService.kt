package org.kutner.cameragpslink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
    var locationUpdateRunnable: Runnable? = null,
    var disconnectTimestamp: Long? = null,
    var quickConnectRunnable: Runnable? = null,
    var retries: Long = 0
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
        // All other constants have been moved to Constants.kt
    }

    // --- Service components ---
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notificationHelper: NotificationHelper

    // --- Bluetooth & Location ---
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleScanner: android.bluetooth.le.BluetoothLeScanner
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // --- Multiple camera connections ---
    private val cameraConnections = mutableMapOf<String, CameraConnection>()
    private val autoScanCallbacks = mutableMapOf<String, ScanCallback>()

    // --- State for UI ---
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _connectedCameras = MutableStateFlow<List<CameraConnection>>(emptyList())
    val connectedCameras: StateFlow<List<CameraConnection>> = _connectedCameras

    private val _foundDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val foundDevices: StateFlow<List<BluetoothDevice>> = _foundDevices

    private var lastKnownLocation: Location? = null
    private lateinit var locationCallback: LocationCallback

    private val _isManualScanning = MutableStateFlow(false)
    val isManualScanning: StateFlow<Boolean> = _isManualScanning

    private val _shutterTriggeredFromNotification = MutableStateFlow<String?>(null)
    private val shutterTriggeredFromNotification: StateFlow<String?> = _shutterTriggeredFromNotification

    private val _shutterErrorMessage = MutableStateFlow<String?>(null)
    val shutterErrorMessage: StateFlow<String?> = _shutterErrorMessage

    private var isForegroundServiceStarted = false

    inner class LocalBinder : Binder() {
        fun getService(): CameraSyncService = this@CameraSyncService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
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
        if (intent?.action == Constants.ACTION_TRIGGER_SHUTTER) {
            val deviceAddress = intent.getStringExtra(Constants.EXTRA_DEVICE_ADDRESS)
            if (deviceAddress != null) {
                triggerShutter(deviceAddress, fromNotification = true)
            } else {
                log("Received shutter action without device address.")
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

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        stopAutoScan()
        cameraConnections.values.forEach { connection ->
            stopLocationUpdates(connection)
            cancelQuickConnectTimer(connection.device.address)
            connection.gatt?.close()
        }
        cameraConnections.clear()
        stopService()
        log("Service destroyed.")
    }

    private fun startService() {
        if (!isForegroundServiceStarted) {
            log("Starting foreground service...")
            startForeground(Constants.NOTIFICATION_ID_FOREGROUND_SERVICE, notificationHelper.createSearchingNotification())
            isForegroundServiceStarted = true
        }
        else {
            log("Foreground service already started.")
        }
    }

    private fun stopService() {
        if (isForegroundServiceStarted) {
            log("Stopping foreground service...")
            stopBackgroundLocationFetching()
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundServiceStarted = false
            stopSelf()
        }
    }

    // --- Core Logic ---

    private fun createAutoScanCallback(deviceAddress: String): ScanCallback {
        return object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                log("Found saved device $deviceAddress. Connecting...")
                val connection = cameraConnections[deviceAddress] ?: return
                if (connection.isConnected || connection.isConnecting) {
                    // This is meant to handle scenarios where the device is discovered multiple time
                    // it seems to be quite common when using LOW_POWER mode
                    log("Device ${connection.device.name ?: deviceAddress}  is already connected or connecting. Not connecting again.")
                    return
                }
                stopAutoScan(deviceAddress)
                connectToDevice(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                log("Auto-scan failed for $deviceAddress: $errorCode. Retrying...")
                handler.postDelayed({ startAutoScan(deviceAddress) }, 3000)
            }
        }
    }


    @SuppressLint("MissingPermission")
    fun startAutoScan(deviceAddress: String) {
        val connection = cameraConnections[deviceAddress] ?: return
        if (connection.gatt != null) {

            // Check if deviceAddress is currently connected
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            val isConnected = connectedDevices.contains(connection.device)
            if (isConnected) {
                log("startAutoScan() found previous active gatt connection for $deviceAddress disconnecting it")
                connection.gatt?.disconnect()
                return
            }
            log("startAutoScan() found previous gatt connection for $deviceAddress but it's not connected so destroying it")
            connection.gatt?.close()
            connection.gatt = null
            connection.isConnected = false
            connection.isConnecting = false
        }

        val cameraSettings = CameraSettingsManager.getCameraSettings(this, deviceAddress)

        if (cameraSettings.connectionMode == 1) { // Mode 1
            if (!autoScanCallbacks.containsKey(deviceAddress)) {
                    // Make sure gatt connection is closed just in case we're switching from mode 2 to mode 1

                val scanMode = determineScanMode(connection, cameraSettings)

                val scanModeText = when (scanMode) {
                    ScanSettings.SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY (Quick Connect)"
                    else -> "LOW_POWER"
                }
                log("Connection mode 1: Starting auto-scan for $deviceAddress with scan mode: $scanModeText")

                val callback = createAutoScanCallback(deviceAddress)
                autoScanCallbacks[deviceAddress] = callback

                val scanFilter = ScanFilter.Builder().setDeviceAddress(deviceAddress).build()
                val scanSettings = ScanSettings.Builder().setScanMode(scanMode).build()
                bleScanner.startScan(listOf(scanFilter), scanSettings, callback)

                // Set up quick connect timer if needed
                createQuickConnectTimer(connection, cameraSettings, deviceAddress)

                notificationHelper.updateNotifications(cameraConnections.values, isForegroundServiceStarted) { log(it) }
            }
            else {
                log("Auto-scan already in progress for $deviceAddress.")
            }
        }
        else {
            // connectionMode == 2
            // Cancel quick connect timer in case we switched from mode 1
            cancelQuickConnectTimer(deviceAddress)

            val device: BluetoothDevice =
                bluetoothManager.adapter.getRemoteDevice(deviceAddress)
            connection.gatt = device.connectGatt(
                this,
                true,
                createGattCallback(deviceAddress),
                BluetoothDevice.TRANSPORT_AUTO
            )
            log("Connection mode 2: Created GATT connection with autoConnect=true for $deviceAddress")
        }
    }
    @SuppressLint("MissingPermission")
    private fun stopAutoScan(deviceAddress: String? = null) {
        log("Stopping auto-scan for ${deviceAddress ?: "all"}...")
        if (deviceAddress != null) {
            // Cancel pending BT scan
            autoScanCallbacks[deviceAddress]?.let { callback ->
                try {
                    bleScanner.stopScan(callback)
                    autoScanCallbacks.remove(deviceAddress)
                } catch (e: Exception) {
                    log("Error stopping auto scan for $deviceAddress: ${e.message}")
                }
            }
            cancelQuickConnectTimer(deviceAddress)

            val connection = cameraConnections[deviceAddress]
            connection?.gatt?.close()
            connection?.gatt = null
        }
        else {
            autoScanCallbacks.values.forEach { callback ->
                try {
                    bleScanner.stopScan(callback)
                } catch (e: Exception) {
                    log("Error stopping auto scan: ${e.message}")
                }
            }
            autoScanCallbacks.clear()
            connectedCameras.value.forEach { connection ->
                connection.gatt?.close()
                connection.gatt = null
                connection.isConnected = false
                connection.isConnecting = false
            }
        }
    }

    fun resetAutoScan(deviceAddress: String) {
        stopAutoScan(deviceAddress)
        startAutoScan(deviceAddress)
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        // This method is used both for initiating a connection in mode 1
        // and for adding a new camera in either mode 1 or 2
        if (!hasRequiredPermissions() || !isAdapterAndScannerReady()) return

        val address = device.address

        // Don't connect if already connected or connecting
        cameraConnections[address]?.let { connection ->
            if (connection.isConnected || connection.isConnecting) {
                log("connectToDevice() already connected or connecting to ${device.name ?: address}")
                return
            }
        }

        val connection = cameraConnections.getOrPut(address) {
            CameraConnection(device = device)
        }

        connection.isConnecting = true
        connection.gatt = device.connectGatt(this, false, createGattCallback(address), BluetoothDevice.TRANSPORT_AUTO)


        // Start fetching location in the background so it's
        // ready when the connection is established
        startBackgroundLocationFetching()

        // Save immediately when connecting to a new camera
        if (!CameraSettingsManager.hasSavedCamera(this, address)) {
            CameraSettingsManager.addSavedCamera(this, address)
        }

        updateConnectionsList()
        notificationHelper.updateNotifications(cameraConnections.values, isForegroundServiceStarted) { log(it) }
    }

    private fun createGattCallback(deviceAddress: String): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val connection = cameraConnections[deviceAddress] ?: return
                val cameraSettings = CameraSettingsManager.getCameraSettings(this@CameraSyncService, deviceAddress)

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connection.isConnected = true
                    connection.isConnecting = false

                    if (cameraSettings.connectionMode == 1) {
                        log("Connection mode 1: Connected to ${gatt.device.name ?: deviceAddress}. Requesting MTU...")
                        gatt.requestMtu(Constants.REQUEST_MTU_SIZE)

                    }
                    else {
                        log("Connection mode 2: Connected to ${gatt.device.name ?: deviceAddress}. Discovering services (skipping MTU)...")
                        startBackgroundLocationFetching()       // In mode 2 background location wasn't started in connectToDevice()
                        if (gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
                            handler.postDelayed({ gatt.discoverServices() }, 100)
                        }
                        else {
                            log("Device not bonded. Starting pairing...")
                            gatt.device.createBond()
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("Disconnected from ${gatt.device.name ?: deviceAddress}.")
                    connection.isConnected = false
                    connection.isConnecting = false
                    connection.gatt?.close()
                    connection.gatt = null

                    // Record disconnect timestamp for quick connect
                    val timestamp = System.currentTimeMillis()
                    connection.disconnectTimestamp = timestamp
                    CameraSettingsManager.updateDisconnectTimestamp(this@CameraSyncService, deviceAddress, timestamp)

                    stopLocationUpdates(connection)
                    stopBackgroundLocationFetching()

                    notificationHelper.clearShutterErrorNotification(deviceAddress)

                    // Restart auto-scan for this device
                    startAutoScan(deviceAddress)
                }

                updateConnectionsList()
                notificationHelper.updateNotifications(cameraConnections.values, isForegroundServiceStarted) { log(it) }
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
                    log("MTU set to $mtu for ${gatt.device.name ?: deviceAddress}. Discovering services...")
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
                val connection = cameraConnections[deviceAddress] ?: return

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connection.retries = 0

                    when (characteristic.uuid) {
                        Constants.LOCK_LOCATION_ENDPOINT_UUID -> {
                            log("Endpoint locked for $deviceAddress. Enabling location updates...")
                            enableLocationUpdates(gatt, deviceAddress)
                        }
                        Constants.ENABLE_LOCATION_UPDATES_UUID -> {
                            log("Location updates enabled for $deviceAddress. Syncing time...")
                            // The first location update is sometimes ignored by the camera so we
                            // start the update schedule at a 500ms delay, which will send another update
                            sendLocationData(deviceAddress)
                            handler.postDelayed({ startLocationUpdates(deviceAddress) }, 500)
                            synchronizeTime(gatt, deviceAddress)
                        }
//                        Constants.TIME_CHARACTERISTIC_UUID -> {
//                            log("Time synced for $deviceAddress. Starting location data stream.")
//                            startLocationUpdates(deviceAddress)
//                        }
                    }
                } else {
                    log("Write failed for ${characteristic.uuid} on $deviceAddress with status $status")

                    when (characteristic.uuid) {
                        Constants.ENABLE_LOCATION_UPDATES_UUID -> {
                            if (connection.retries < Constants.MAX_BT_RETRIES) {
                                connection.retries++
                                handler.postDelayed({ enableLocationUpdates(gatt, deviceAddress) },500 * connection.retries)
                            }
                        }
                        Constants.LOCK_LOCATION_ENDPOINT_UUID -> {
                            if (connection.retries < Constants.MAX_BT_RETRIES) {
                                handler.postDelayed({ lockLocationEndpoint(gatt, deviceAddress) },500 * connection.retries)
                                connection.retries++
                            }
                        }
                        Constants.SHUTTER_CHARACTERISTIC_UUID -> {
                            if (status == 144) {
                                // Check if this shutter was triggered from notification
                                if (_shutterTriggeredFromNotification.value == deviceAddress) {
                                    _shutterTriggeredFromNotification.value = null
                                    notificationHelper.showShutterErrorNotification(deviceAddress, connection)
                                } else {
                                    _shutterErrorMessage.value = Constants.ERROR_SHUTTER_MESSAGE_LONG
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    private fun determineScanMode(connection: CameraConnection, cameraSettings: CameraSettings): Int {
        if (!cameraSettings.quickConnectEnabled) {
            return ScanSettings.SCAN_MODE_LOW_POWER
        }

        // If duration is 0, always use low latency
        if (cameraSettings.quickConnectDurationMinutes == 0) {
            return ScanSettings.SCAN_MODE_LOW_LATENCY
        }

        // Check if we're within the quick connect window
        val disconnectTime = connection.disconnectTimestamp
        if (disconnectTime != null) {
            val elapsedMinutes = (System.currentTimeMillis() - disconnectTime) / (60 * 1000)
            if (elapsedMinutes < cameraSettings.quickConnectDurationMinutes) {
                return ScanSettings.SCAN_MODE_LOW_LATENCY
            }
        }

        return ScanSettings.SCAN_MODE_LOW_POWER
    }


    private fun createQuickConnectTimer(connection: CameraConnection, cameraSettings: CameraSettings, deviceAddress: String) {
        // Cancel any existing timer
        cancelQuickConnectTimer(deviceAddress)

        if (!cameraSettings.quickConnectEnabled || cameraSettings.quickConnectDurationMinutes == 0) {
            return
        }

        val disconnectTime = connection.disconnectTimestamp ?: return
        val elapsedMillis = System.currentTimeMillis() - disconnectTime
        val durationMillis = cameraSettings.quickConnectDurationMinutes * 60 * 1000L
        val remainingMillis = durationMillis - elapsedMillis

        if (remainingMillis > 0) {
            log("Quick Connect timer set for $deviceAddress: ${remainingMillis / 1000} seconds remaining")
            val runnable = Runnable {
                log("Quick Connect period expired for $deviceAddress, switching to low power scan")
                // Restart scan with low power mode
                resetAutoScan(deviceAddress)
            }
            connection.quickConnectRunnable = runnable
            handler.postDelayed(runnable, remainingMillis)
        }
    }

    private fun cancelQuickConnectTimer(deviceAddress: String) {
        val connection = cameraConnections[deviceAddress]
        connection?.quickConnectRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
            connection.quickConnectRunnable = null
        }
    }


    // --- Public Functions for UI to Call ---

    @SuppressLint("MissingPermission")
    fun forgetDevice(deviceAddress: String) {
        log("Forgetting device $deviceAddress...")

        cameraConnections[deviceAddress]?.let { connection ->
            // Cancel quick connect timer
            connection.quickConnectRunnable?.let { runnable ->
                handler.removeCallbacks(runnable)
            }

            // Stop all device related activities
            stopLocationUpdates(connection)
            stopAutoScan(deviceAddress)
            
            notificationHelper.cancel(connection.device.address.hashCode())
            notificationHelper.clearShutterErrorNotification(deviceAddress)

            // Remove from connections map
            cameraConnections.remove(deviceAddress)

            // Remove all camera settings (Quick Connect + disconnect timestamp)
            CameraSettingsManager.removeSavedCamera(this, deviceAddress)

            // Update UI
            updateConnectionsList()

            log("Device $deviceAddress forgotten successfully")

            // Stop service if no more cameras
            if (cameraConnections.isEmpty()) {
                log("No more cameras, stopping service")
                stopService()
            }
        } ?: log("Device $deviceAddress not found in connections")
        
        notificationHelper.updateNotifications(cameraConnections.values, isForegroundServiceStarted) { log(it) }
    }

    fun onSettingsChanged(deviceAddress: String) {
        val connection = cameraConnections[deviceAddress]
        connection?.let {
            if (!connection.isConnecting && !connection.isConnected) {
                // Don't interrupt an active connection but if there isn't one
                // we rest the auto scan to apply the new settings
                resetAutoScan(deviceAddress)
            }
        }
    }

    // --- New device scan ---
    @SuppressLint("MissingPermission")
    fun startManualScan() {
        if (!hasRequiredPermissions() || !isAdapterAndScannerReady()) return
        if (_isManualScanning.value) return

        log("Starting manual scan for new cameras...")
        _isManualScanning.value = true
        _foundDevices.value = emptyList()

        val scanFilter = ScanFilter.Builder().setManufacturerData(Constants.SONY_MANUFACTURER_ID, byteArrayOf()).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner.startScan(listOf(scanFilter), scanSettings, manualScanCallback)

        handler.postDelayed({ stopManualScan() }, Constants.MANUAL_SCAN_PERIOD)
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
        val lockChar = gatt.getService(Constants.PICT_SERVICE_UUID)?.getCharacteristic(Constants.LOCK_LOCATION_ENDPOINT_UUID)
        if (lockChar == null) {
            log("Lock Location Endpoint characteristic (dd30) not found for $deviceAddress!")
            return
        }
        writeCharacteristic(gatt, lockChar, byteArrayOf(0x01), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationUpdates(gatt: BluetoothGatt, deviceAddress: String) {
        val enableChar = gatt.getService(Constants.PICT_SERVICE_UUID)?.getCharacteristic(Constants.ENABLE_LOCATION_UPDATES_UUID)
        if (enableChar == null) {
            log("Enable Location Updates characteristic (dd31) not found for $deviceAddress!")
            return
        }
        writeCharacteristic(gatt, enableChar, byteArrayOf(0x01), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun synchronizeTime(gatt: BluetoothGatt, deviceAddress: String) {
        val timeChar = gatt.getService(Constants.TIME_SERVICE_UUID)?.getCharacteristic(Constants.TIME_CHARACTERISTIC_UUID)
        if (timeChar == null) {
            log("Time characteristic not found for $deviceAddress! Skipping sync.")
            return
        }
        log("Syncing time for $deviceAddress...")

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
        if (!hasRequiredPermissions()) {
            log("Cannot start location updates: Permissions not granted")
            return
        }

        val connection = cameraConnections[deviceAddress] ?: let {
            log("Cannot start location updates: Camera $deviceAddress not connected")
            return
        }

        log("Starting to stream location data to $deviceAddress...")

        val runnable = object : Runnable {
            override fun run() {
                sendLocationData(deviceAddress)
                handler.postDelayed(this, Constants.LOCATION_UPDATE_INTERVAL)
            }
        }

        connection.locationUpdateRunnable = runnable
        handler.post(runnable)
    }

    @SuppressLint("MissingPermission")
    private fun sendLocationData(deviceAddress: String) {
        val  location = lastKnownLocation
        if (location == null) {
            log("No pre-fetched location data to send to $deviceAddress skipping location update.")
            return
        }

        val connection = cameraConnections[deviceAddress] ?: let {
            log("Cannot send location data: Camera $deviceAddress not in connections list")
            return
        }
        val gatt = connection.gatt ?: let {
            log("Cannot send location data: Camera $deviceAddress GATT not connected")
            return
        }

        val locationChar = gatt.getService(Constants.PICT_SERVICE_UUID)?.getCharacteristic(Constants.LOCATION_CHARACTERISTIC_UUID)
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

        writeCharacteristic(gatt, locationChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        log("Location data sent to $deviceAddress")
    }

    @SuppressLint("MissingPermission")
    fun triggerShutter(deviceAddress: String? = null, fromNotification: Boolean = false) {
        if (deviceAddress != null) {
            // Trigger specific camera
            val connection = cameraConnections[deviceAddress]
            if (connection == null || !connection.isConnected) {
                log("Cannot trigger shutter: Camera $deviceAddress not connected")
                return
            }

            val gatt = connection.gatt ?: return
            val shutterChar = gatt.getService(Constants.SHUTTER_SERVICE_UUID)?.getCharacteristic(Constants.SHUTTER_CHARACTERISTIC_UUID)
            if (shutterChar == null) {
                log("Shutter characteristic not found for $deviceAddress")
                return
            }

            // Track if this shutter was triggered from notification
            if (fromNotification) {
                _shutterTriggeredFromNotification.value = deviceAddress
            }

            log("Triggering shutter for ${connection.device.name ?: deviceAddress}...")
            writeCharacteristic(gatt, shutterChar, byteArrayOf(0x01, 0x09), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            handler.postDelayed({
                writeCharacteristic(gatt, shutterChar, byteArrayOf(0x01, 0x08), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                log("Shutter triggered for ${connection.device.name ?: deviceAddress}")
            }, 100)
        }
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


    private fun initializeBluetoothAndLocation() {
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter.bluetoothLeScanner

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // Define the callback that will receive location updates
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    log("Pre-fetched current location: Lat=${location.latitude}, Lon=${location.longitude}")
                    lastKnownLocation = location
                }
            }
        }
    }
    @SuppressLint("MissingPermission")
    private fun startBackgroundLocationFetching() {
        if (!hasRequiredPermissions()) {
            log("Cannot start location updates: Permissions not granted")
            return
        }
        // Fetch last known location so we have something immediately and then start fetching more accurate updates
        fusedLocationClient.getLastLocation().addOnSuccessListener { location ->
            if (lastKnownLocation == null) {
                lastKnownLocation = location
                log("Pre-fetched last location: Lat=${location.latitude}, Lon=${location.longitude}")
            }
        }
        log("Starting background location fetching")
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, Constants.LOCATION_UPDATE_INTERVAL)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun stopBackgroundLocationFetching() {
        log("Stopping background location fetching")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        lastKnownLocation = null
    }

    // --- Utility Methods ---
    fun clearShutterError() {
        _shutterErrorMessage.value = null
    }

    @SuppressLint("MissingPermission")
    private fun loadSavedCameras() {
        val savedAddresses = CameraSettingsManager.getSavedCameras(this)
        if (savedAddresses.isNotEmpty()) {
            savedAddresses.forEach { address ->
                if (address.isNotBlank()) {
                    try {
                        if (!::bluetoothAdapter.isInitialized) {
                            log("Bluetooth adapter not initialized yet, deferring camera loading")
                            return@forEach
                        }
                        val device = bluetoothAdapter.getRemoteDevice(address)
                        val connection = CameraConnection(device = device)

                        // Load saved camera settings
                        val cameraSettings = CameraSettingsManager.getCameraSettings(this, address)
                        connection.disconnectTimestamp = cameraSettings.lastDisconnectTimestamp

                        cameraConnections[address] = connection
                        log("Loaded saved camera: ${device.name ?: address}")

                        // Log if within quick connect period
                        if (connection.disconnectTimestamp != null) {
                            val scanMode = determineScanMode(connection, cameraSettings)
                            if (scanMode == ScanSettings.SCAN_MODE_LOW_LATENCY) {
                                log("Camera $address is within Quick Connect period")
                            }
                        }
                    } catch (e: Exception) {
                        log("Failed to load saved camera $address: ${e.message}")
                    }
                }
            }
            updateConnectionsList()
        }
    }
    private fun updateConnectionsList() {
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
            log("Stopping location updates for ${connection.device.address}.")
            handler.removeCallbacks(it)
            connection.locationUpdateRunnable = null
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val currentLog = _log.value.toMutableList()
        currentLog.add(0, "[${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())}] $message")
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