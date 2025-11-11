package org.kutner.sonygpssync

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

class CameraSyncService : Service() {

    // --- Constants ---
    companion object {
        private const val TAG = "CameraSyncService"
        private const val PREFS_NAME = "SonyGpsSyncPrefs"
        private const val PREF_KEY_DEVICE_ADDRESS = "device_address"
        private const val PREF_KEY_DEVICE_NAME = "device_name"
        private const val SONY_MANUFACTURER_ID = 0x012D
        private val PICT_SERVICE_UUID = UUID.fromString("8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF")
        private val TIME_CHARACTERISTIC_UUID = UUID.fromString("0000DD02-0000-1000-8000-00805F9B34FB")
        private val LOCATION_CHARACTERISTIC_UUID = UUID.fromString("0000DD11-0000-1000-8000-00805F9B34FB")
        private val LOCK_LOCATION_ENDPOINT_UUID = UUID.fromString("0000DD30-0000-1000-8000-00805F9B34FB")
        private val ENABLE_LOCATION_UPDATES_UUID = UUID.fromString("0000DD31-0000-1000-8000-00805F9B34FB")
        private val SHUTTER_SERVICE_UUID = UUID.fromString("8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF")
        private val SHUTTER_CHARACTERISTIC_UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        private const val MANUAL_SCAN_PERIOD: Long = 10000
        private const val LOCATION_UPDATE_INTERVAL: Long = 5000
        private const val REQUEST_MTU_SIZE = 517
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

    private var _connected = false
    private var _autoScanning = false



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


        var message = ""
        if (_connected) {
            val savedName = sharedPreferences.getString(PREF_KEY_DEVICE_NAME, null)
            message = "Connected to $savedName"
        }
        else {
            message = "Searching for saved camera..."
        }
        startForeground(1, createNotification(message, true))
        startAutoScan()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        bluetoothGatt?.close()
        bluetoothGatt = null
        log("Service destroyed.")
    }

    private fun createNotification(contentText: String, isOngoing: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("camera_sync_channel", "Camera Sync", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        return NotificationCompat.Builder(this, "camera_sync_channel")
            .setContentTitle("Sony Camera Sync")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.appicon)
            .setContentIntent(pendingIntent)
            .setOngoing(isOngoing)
            .setAutoCancel(!isOngoing)
            .build()
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
                updateNotification("Connected to ${gatt.device.name}")
                _connected = true
                log("Connected. Requesting MTU...")
                handler.postDelayed({ gatt.requestMtu(REQUEST_MTU_SIZE) }, 600)
                saveDeviceAndStartService(gatt.device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected.")
                _connected = false
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
        val timeChar = gatt.getService(PICT_SERVICE_UUID)?.getCharacteristic(TIME_CHARACTERISTIC_UUID)
        if (timeChar == null) {
            log("Time characteristic not found! Skipping sync.")
            startLocationUpdates()
            return
        }
        val cal = Calendar.getInstance()
        val offset = TimeZone.getDefault().getOffset(cal.timeInMillis) / 60000
        val payload = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(cal.get(Calendar.YEAR).toShort())
            .put((cal.get(Calendar.MONTH) + 1).toByte())
            .put(cal.get(Calendar.DAY_OF_MONTH).toByte())
            .put(cal.get(Calendar.HOUR_OF_DAY).toByte())
            .put(cal.get(Calendar.MINUTE).toByte())
            .put(cal.get(Calendar.SECOND).toByte())
            .putShort(offset.toShort()).array()

        writeCharacteristic(gatt, timeChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
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
        buffer.putShort((localTimezone.rawOffset / 60 / 1000).toShort())
        buffer.putShort((localTimezone.dstSavings / 60 / 1000).toShort())

        val payload = buffer.array()

        writeCharacteristic(gatt, locationChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
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