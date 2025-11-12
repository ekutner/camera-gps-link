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

class CameraSyncService : Service() {

    // --- Constants ---
    companion object {
        private const val TAG = "CameraSyncService"
        private const val PREFS_NAME = "cameragpslinkPrefs"
        private const val PREF_KEY_DEVICE_ADDRESS = "device_address"
        private const val PREF_KEY_DEVICE_NAME = "device_name"
        private const val SONY_MANUFACTURER_ID = 0x012D
        private val PICT_SERVICE_UUID = UUID.fromString("8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF")

        private val TIME_SERVICE_UUID = UUID.fromString("8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF")
        private val TIME_CHARACTERISTIC_UUID = UUID.fromString("0000CC13-0000-1000-8000-00805F9B34FB")
        private val LOCATION_CHARACTERISTIC_UUID = UUID.fromString("0000DD11-0000-1000-8000-00805F9B34FB")
        private val LOCK_LOCATION_ENDPOINT_UUID = UUID.fromString("0000DD30-0000-1000-8000-00805F9B34FB")
        private val ENABLE_LOCATION_UPDATES_UUID = UUID.fromString("0000DD31-0000-1000-8000-00805F9B34FB")
        private val SHUTTER_SERVICE_UUID = UUID.fromString("8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF")
        private val SHUTTER_CHARACTERISTIC_UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        private const val MANUAL_SCAN_PERIOD: Long = 10000
        private const val LOCATION_UPDATE_INTERVAL: Long = 5000
        private const val REQUEST_MTU_SIZE = 517

        const val ACTION_TRIGGER_SHUTTER = "org.kutner.cameragpslink.ACTION_TRIGGER_SHUTTER"
    }

    // --- Service components ---
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())

    // --- Bluetooth & Location ---
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleScanner: android.bluetooth.le.BluetoothLeScanner
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var bluetoothGatt: BluetoothGatt? = null
    private lateinit var sharedPreferences: SharedPreferences
    private var locationUpdateRunnable: Runnable? = null

    // --- State for UI ---
    private val _status = MutableStateFlow("Initializing...")
    val status: StateFlow<String> = _status

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _rememberedDevice = MutableStateFlow<String?>(null)
    val rememberedDevice: StateFlow<String?> = _rememberedDevice

    private val _foundDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val foundDevices: StateFlow<List<BluetoothDevice>> = _foundDevices

    private val _isManualScanning = MutableStateFlow(false)
    val isManualScanning: StateFlow<Boolean> = _isManualScanning

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private var _autoScanning = false
    private var isForegroundServiceStarted = false



    inner class LocalBinder : Binder() {
        fun getService(): CameraSyncService = this@CameraSyncService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _rememberedDevice.value = sharedPreferences.getString(PREF_KEY_DEVICE_ADDRESS, null)
        initializeBluetoothAndLocation()
        log("Service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("Service received start command.")

        // Handle shutter action from notification
        if (intent?.action == ACTION_TRIGGER_SHUTTER) {
            triggerShutter()
            return START_STICKY
        }

        if (!hasRequiredPermissions()) {
            log("Permissions not granted. Posting notification and stopping.")
            showPermissionsRequiredNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        val savedAddress = sharedPreferences.getString(PREF_KEY_DEVICE_ADDRESS, null)
        if (savedAddress == null) {
            log("No saved device, stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Only start foreground service if not already started
        if (!isForegroundServiceStarted) {
            var message = ""
            if (_isConnected.value) {
                val savedName = sharedPreferences.getString(PREF_KEY_DEVICE_NAME, null)
                message = "Connected to $savedName"
            } else {
                message = "Searching for saved camera..."
            }
            startForeground(1, createNotification(message, true))
            isForegroundServiceStarted = true
            if (!_isConnected.value) {
                startAutoScan()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isForegroundServiceStarted = false
        log("Service destroyed.")
    }

    private fun createNotification(contentText: String, isOngoing: Boolean): Notification {
        // Use different channels for connected vs searching states
        val channelId = if (_isConnected.value) "camera_sync_channel_high" else "camera_sync_channel_low"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Create high priority channel for connected state
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

            // Create low priority channel for searching state
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

        val priority = if (_isConnected.value) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW

        val builder = NotificationCompat.Builder(this, channelId)
//            .setContentTitle("Camera Gps Link")
//            .setContentText(contentText)
            .setContentTitle(contentText)
            .setSmallIcon(R.drawable.ic_notification) // Use monochrome icon for status bar
            .setContentIntent(pendingIntent)
            .setOngoing(isOngoing)
            .setAutoCancel(!isOngoing)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // Add shutter button if connected
        if (_isConnected.value) {
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
                "Shutter",
                shutterPendingIntent
            )
        }

        return builder.build()
    }

    private fun showPermissionsRequiredNotification() {
        val notification = createNotification("Permissions required. Tap to open the app.", false)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(2, notification) // Use a different ID
    }

    private fun updateNotification(contentText: String) {
        _status.value = contentText
        val notification = createNotification(contentText, true)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Cancel the existing notification to ensure channel switch takes effect
        notificationManager.cancel(1)

        // Post the new notification
        notificationManager.notify(1, notification)
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
        stopManualScan()
        log("Connecting to ${device.name ?: device.address}...")
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun forgetDevice() {
        disconnectFromDevice(true)
    }

    @SuppressLint("MissingPermission")
    fun triggerShutter() {
        val gatt = bluetoothGatt
        if (gatt == null) {
            log("Cannot trigger shutter: Not connected to camera")
            return
        }

        val shutterChar = gatt.getService(SHUTTER_SERVICE_UUID)?.getCharacteristic(SHUTTER_CHARACTERISTIC_UUID)
        if (shutterChar == null) {
            log("Shutter characteristic not found!")
            return
        }

        log("Triggering shutter...")
        // Send 0x0109 (start shutter)
        writeCharacteristic(gatt, shutterChar, byteArrayOf(0x01, 0x09), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

        // Send 0x0108 (release shutter) after a short delay
        handler.postDelayed({
            writeCharacteristic(gatt, shutterChar, byteArrayOf(0x01, 0x08), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            log("Shutter triggered")
        }, 100)
    }

    // --- Core Logic ---
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _isConnected.value = true
                updateNotification("Connected to ${gatt.device.name}")
                log("Connected. Requesting MTU...")
                handler.postDelayed({ gatt.requestMtu(REQUEST_MTU_SIZE) }, 600)
                saveDeviceAndStartService(gatt.device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected.")
                _isConnected.value = false
                disconnectFromDevice(false)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU set to $mtu.")
            if (gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
                gatt.discoverServices()
            } else {
                log("Device not bonded. Starting pairing...")
                gatt.device.createBond()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            log("Services discovered. Saving device and locking endpoint...")
//            saveDeviceAndStartService(gatt.device)
            lockLocationEndpoint(gatt)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic.uuid) {
                    LOCK_LOCATION_ENDPOINT_UUID -> {
                        log("Endpoint locked. Enabling location updates...")
                        enableLocationUpdates(gatt)
                    }
                    ENABLE_LOCATION_UPDATES_UUID -> {
                        log("Location updates enabled. Syncing time...")
                        synchronizeTime(gatt)
                    }
                    TIME_CHARACTERISTIC_UUID -> {
                        log("Time synced. Starting location data stream.")
                        startLocationUpdates()
                    }
                }
            } else {
                log("Write failed for ${characteristic.uuid} with status $status")
                disconnectFromDevice(false)
            }
        }
    }

    private val autoScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            log("Found saved device. Connecting...")
            _autoScanning = false
            bleScanner.stopScan(this)
            connectToDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
//            log("Auto-scan failed: $errorCode. Retrying...")
//            updateNotification("Auto-scan failed. Retrying...")
            handler.postDelayed({ startAutoScan() }, 3000)
        }
    }

    private val manualScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val currentDevices = _foundDevices.value.toMutableList()
            if (currentDevices.none { it.address == result.device.address }) {
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
    private fun stopManualScan() {
        if (_isManualScanning.value && isAdapterAndScannerReady()) {
            log("Manual scan stopped.")
            _isManualScanning.value = false
            bleScanner.stopScan(manualScanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAutoScan() {
        if (!hasRequiredPermissions() || !isAdapterAndScannerReady() || bluetoothGatt != null) return
        val savedAddress = sharedPreferences.getString(PREF_KEY_DEVICE_ADDRESS, null) ?: return

        if (!_autoScanning) {
            log("Starting auto-scan for $savedAddress")
            _autoScanning = true
            updateNotification("Searching for saved camera...")
        }

        val scanFilter = ScanFilter.Builder().setDeviceAddress(savedAddress).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        bleScanner.startScan(listOf(scanFilter), scanSettings, autoScanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectFromDevice(forget: Boolean) {
        stopLocationUpdates()
        bluetoothGatt?.close()
        bluetoothGatt = null

        if (forget) {
            log("Forgetting device...")
            sharedPreferences.edit().clear().apply()
            _rememberedDevice.value = null
            isForegroundServiceStarted = false
            stopSelf()
        } else {
            updateNotification("Disconnected. Searching for camera...")
            startAutoScan()
        }
    }

    private fun saveDeviceAndStartService(device: BluetoothDevice) {
        val address = device.address
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            device.name
        } else {
            "Sony Camera"
        }
        sharedPreferences.edit()
            .putString(PREF_KEY_DEVICE_ADDRESS, address)
            .putString(PREF_KEY_DEVICE_NAME, name)
            .apply()
        _rememberedDevice.value = address
        log("Saved device: $name ($address)")

        val intent = Intent(this, CameraSyncService::class.java)
        startService(intent)
    }

    // --- Characteristic Writes ---
    @SuppressLint("MissingPermission")
    private fun lockLocationEndpoint(gatt: BluetoothGatt) {
        val lockChar = gatt.getService(PICT_SERVICE_UUID)?.getCharacteristic(LOCK_LOCATION_ENDPOINT_UUID)
        if (lockChar == null) {
            log("Lock Location Endpoint characteristic (dd30) not found!")
            disconnectFromDevice(false)
            return
        }
        writeCharacteristic(gatt, lockChar, byteArrayOf(0x01), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationUpdates(gatt: BluetoothGatt) {
        val enableChar = gatt.getService(PICT_SERVICE_UUID)?.getCharacteristic(ENABLE_LOCATION_UPDATES_UUID)
        if (enableChar == null) {
            log("Enable Location Updates characteristic (dd31) not found!")
            disconnectFromDevice(false)
            return
        }
        writeCharacteristic(gatt, enableChar, byteArrayOf(0x01), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun synchronizeTime(gatt: BluetoothGatt) {
        val timeChar = gatt.getService(TIME_SERVICE_UUID)?.getCharacteristic(TIME_CHARACTERISTIC_UUID)
        if (timeChar == null) {
            log("Time characteristic not found! Skipping sync.")
            startLocationUpdates()
            return
        }
        val cal = Calendar.getInstance()
        // Calculate total offset including DST in minutes
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

    /**
     * Gets the RAW time zone offset from UTC (Standard Time offset, ignoring DST).
     * @return A Pair of (hours: Int, minutes: Int) for the raw offset.
     */
    fun getRawUtcOffset(): Pair<Int, Int> {
        val timeZone: TimeZone = TimeZone.getDefault()
        // getRawOffset() returns the offset in milliseconds without DST applied.
        val rawOffsetMillis: Int = timeZone.rawOffset

        val totalSeconds = (rawOffsetMillis / 1000)

        val sign = if (totalSeconds < 0) -1 else 1
        val absSeconds = abs(totalSeconds)

        val hours = (absSeconds / 3600) * sign
        val minutes = (absSeconds % 3600) / 60

        return Pair(hours, minutes)
    }
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasRequiredPermissions()) return
        log("Starting to stream location data...")
        locationUpdateRunnable = Runnable {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        sendLocationData(location)
                    }
                    handler.postDelayed(locationUpdateRunnable!!, LOCATION_UPDATE_INTERVAL)
                }
        }.also { handler.post(it) }
    }

    @SuppressLint("MissingPermission")
    private fun sendLocationData(location: Location) {
        val gatt = bluetoothGatt ?: return
        val locationChar = gatt.getService(PICT_SERVICE_UUID)?.getCharacteristic(LOCATION_CHARACTERISTIC_UUID)
        if (locationChar == null) {
            log("Location characteristic not found.")
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
        // Raw offset in minutes (standard time offset)
        buffer.putShort((localTimezone.rawOffset / 60000).toShort())
        // DST offset in minutes (0 if not in DST, typically 60 if in DST)
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

    private fun stopLocationUpdates() {
        locationUpdateRunnable?.let {
            handler.removeCallbacks(it)
            locationUpdateRunnable = null
            log("Location updates stopped.")
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
}