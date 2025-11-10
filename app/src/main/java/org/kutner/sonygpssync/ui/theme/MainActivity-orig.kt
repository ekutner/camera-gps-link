//package org.kutner.sonygpssync.ui.theme
//
//
//import android.Manifest
//import android.annotation.SuppressLint
//import android.bluetooth.*
//import android.bluetooth.le.*
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.content.pm.PackageManager
//import android.location.Location
//import android.os.Build
//import android.os.Bundle
//import android.os.Handler
//import android.os.Looper
//import android.util.Log
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.setContent
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.foundation.layout.*
//import androidx.compose.foundation.lazy.LazyColumn
//import androidx.compose.foundation.lazy.items
//import androidx.compose.material3.*
//import androidx.compose.runtime.*
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.unit.dp
//import androidx.core.content.ContextCompat
//import com.google.android.gms.location.*
//import java.nio.ByteBuffer
//import java.nio.ByteOrder
//import java.text.SimpleDateFormat
//import java.util.*
//import java.util.Calendar
//
//class `MainActivity-orig` : ComponentActivity() {
//
//    companion object {        private const val PERMISSION_REQUEST_CODE = 101
//    }
//
//    private lateinit var bluetoothManager: BluetoothManager
//    private lateinit var bluetoothAdapter: BluetoothAdapter
//    private lateinit var bleScanner: BluetoothLeScanner
//    private lateinit var fusedLocationClient: FusedLocationProviderClient
//    private var bluetoothGatt: BluetoothGatt? = null
//
//    private val devices = mutableStateListOf<BluetoothDevice>()
//    private val statusMessages = mutableStateListOf<String>()
//    private var isScanning by mutableStateOf(false)
//    private var isConnected by mutableStateOf(false)
//    private var currentLocation by mutableStateOf<Location?>(null)
//    private var lastSyncTime by mutableStateOf("")
//
//    // Sony BLE Service and Characteristic UUIDs
//    private val locationServiceUuid = UUID.fromString("8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF")
//    private val locationCharacteristicUuid = UUID.fromString("0000DD11-0000-1000-8000-00805F9B34FB")
//
//    private val handler = Handler(Looper.getMainLooper())
//    private var locationUpdateRunnable: Runnable? = null
//
//    // BroadcastReceiver for pairing events
//    private val pairingReceiver = object : BroadcastReceiver() {
//        @SuppressLint("MissingPermission")
//        override fun onReceive(context: Context, intent: Intent) {
//            when (intent.action) {
//                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
//                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
//                    } else {
//                        @Suppress("DEPRECATION")
//                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
//                    }
//                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
//
//                    when (bondState) {
//                        BluetoothDevice.BOND_BONDING -> {
//                            addStatus("Pairing with ${device?.name}...")
//                        }
//                        BluetoothDevice.BOND_BONDED -> {
//                            addStatus("Paired successfully with ${device?.name}")
//                            // Now discover services
//                            bluetoothGatt?.discoverServices()
//                        }
//                        BluetoothDevice.BOND_NONE -> {
//                            addStatus("Pairing failed or removed")
//                        }
//                    }
//                }
//            }
//        }
//    }
//
//    private val permissionLauncher = registerForActivityResult(
//        ActivityResultContracts.RequestMultiplePermissions()
//    ) { permissions ->
//        if (permissions.all { it.value }) {
//            addStatus("Permissions granted")
//        } else {
//            addStatus("Some permissions denied")
//        }
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        //1. REMOVE the old View Binding lines
//        // binding = ActivityMainBinding.inflate(layoutInflater)
//        // setContentView(binding.root)
//
//        // 2. USE setContent to build the UI with Jetpack Compose
//        setContent {
//            // Assuming you have a Compose theme, otherwise this can be omitted.
//            // For example: YourProjectTheme {
//            Surface(
//                modifier = Modifier.fillMaxSize(),
//                color = MaterialTheme.colorScheme.background
//            ) {
//                MainScreen()
//            }
//            // }
//        }
//
//        // Initialize location client
//        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
//
//        // Initialize Bluetooth components
//        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
//        bluetoothAdapter = bluetoothManager.adapter
//        bleScanner = bluetoothAdapter.bluetoothLeScanner // Make sure bleScanner is initialized
//
//        // 3. REMOVE the old click listener
//        // The button logic is now handled inside the MainScreen composable.
//        /*
//        binding.scanButton.setOnClickListener {
//            if (!isScanning) {
//                startScanning()
//            } else {
//                stopScanning()
//            }
//        }
//        */
//
//        // Request permissions if not already granted.
//        if (!hasPermissions()) {
//            requestPermissions()
//        }
//    }
//
//
//    @Composable
//    fun MainScreen() {
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .padding(16.dp)
//        ) {
//            Text(
//                text = "Sony Camera GPS Sync",
//                style = MaterialTheme.typography.headlineMedium
//            )
//
//            Spacer(modifier = Modifier.height(16.dp))
//
//            // Connection Status
//            Card(
//                modifier = Modifier.fillMaxWidth()
//            ) {
//                Column(modifier = Modifier.padding(16.dp)) {
//                    Text(
//                        text = if (isConnected) "Connected" else "Disconnected",
//                        style = MaterialTheme.typography.titleMedium,
//                        color = if (isConnected)
//                            MaterialTheme.colorScheme.primary
//                        else
//                            MaterialTheme.colorScheme.error
//                    )
//
//                    if (currentLocation != null) {
//                        Text("Lat: %.6f".format(currentLocation!!.latitude))
//                        Text("Lon: %.6f".format(currentLocation!!.longitude))
//                    }
//
//                    if (lastSyncTime.isNotEmpty()) {
//                        Text("Last sync: $lastSyncTime")
//                    }
//                }
//            }
//
//            Spacer(modifier = Modifier.height(16.dp))
//
//            // Control Buttons
//            Row(
//                modifier = Modifier.fillMaxWidth(),
//                horizontalArrangement = Arrangement.SpaceEvenly
//            ) {
//                Button(
//                    onClick = { startScanning() },
//                    enabled = !isScanning && !isConnected
//                ) {
//                    Text(if (isScanning) "Scanning..." else "Scan")
//                }
//
//                Button(
//                    onClick = { disconnect() },
//                    enabled = isConnected
//                ) {
//                    Text("Disconnect")
//                }
//            }
//
//            Spacer(modifier = Modifier.height(16.dp))
//
//            // Device List
//            if (devices.isNotEmpty()) {
//                Text(
//                    text = "Found Cameras:",
//                    style = MaterialTheme.typography.titleMedium
//                )
//
//                LazyColumn(
//                    modifier = Modifier
//                        .weight(1f)
//                        .fillMaxWidth()
//                ) {
//                    items(devices) { device ->
//                        DeviceItem(device) {
//                            connectToDevice(device)
//                        }
//                    }
//                }
//            }
//
//            // Status Log
//            Text(
//                text = "Status Log:",
//                style = MaterialTheme.typography.titleMedium
//            )
//
//            Card(
//                modifier = Modifier
//                    .weight(1f)
//                    .fillMaxWidth()
//            ) {
//                LazyColumn(
//                    modifier = Modifier
//                        .padding(8.dp)
//                        .fillMaxSize(),
//                    reverseLayout = true
//                ) {
//                    items(statusMessages.reversed()) { message ->
//                        Text(
//                            text = message,
//                            style = MaterialTheme.typography.bodySmall
//                        )
//                    }
//                }
//            }
//        }
//    }
//
//    @SuppressLint("MissingPermission")
//    @Composable
//    fun DeviceItem(device: BluetoothDevice, onClick: () -> Unit) {
//        Card(
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(vertical = 4.dp),
//            onClick = onClick
//        ) {
//            Column(modifier = Modifier.padding(16.dp)) {
//                Text(
//                    text = device.name ?: "Unknown",
//                    style = MaterialTheme.typography.titleMedium
//                )
//                Text(
//                    text = device.address,
//                    style = MaterialTheme.typography.bodySmall
//                )
//            }
//        }
//    }
//
//    private fun requestPermissions() {
//        val permissions = mutableListOf(
//            Manifest.permission.ACCESS_FINE_LOCATION,
//            Manifest.permission.ACCESS_COARSE_LOCATION
//        )
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
//            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
//        }
//
//        permissionLauncher.launch(permissions.toTypedArray())
//    }
//
//    @SuppressLint("MissingPermission")
//    private fun startScanning() {
//        if (!hasPermissions()) {
//            addStatus("Missing required permissions")
//            return
//        }
//
//        devices.clear()
//        isScanning = true
//        addStatus("Scanning for Sony cameras...")
//
//        val scanFilter = ScanFilter.Builder()
//            .setManufacturerData(0x012D, byteArrayOf()) // Sony manufacturer ID
//            .build()
//
//        val scanSettings = ScanSettings.Builder()
//            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
//            .build()
//
//        bleScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
//
//        // Stop scanning after 10 seconds
//        handler.postDelayed({
//            stopScanning()
//        }, 10000)
//    }
//
//    @SuppressLint("MissingPermission")
//    private fun stopScanning() {
//        if (isScanning) {
//            bleScanner.stopScan(scanCallback)
//            isScanning = false
//            addStatus("Scan complete. Found ${devices.size} device(s)")
//        }
//    }
//
//    private val scanCallback = object : ScanCallback() {
//        @SuppressLint("MissingPermission")
//        override fun onScanResult(callbackType: Int, result: ScanResult) {
//            val device = result.device
//            if (!devices.contains(device)) {
//                devices.add(device)
//                addStatus("Found: ${device.name ?: "Unknown"}")
//            }
//        }
//
//        override fun onScanFailed(errorCode: Int) {
//            addStatus("Scan failed: $errorCode")
//            isScanning = false
//        }
//    }
//
//    @SuppressLint("MissingPermission")
//    private fun connectToDevice(device: BluetoothDevice) {
//        stopScanning()
//        addStatus("Connecting to ${device.name}...")
//
//        bluetoothGatt = device.connectGatt(
//            this,
//            false,
//            gattCallback,
//            BluetoothDevice.TRANSPORT_LE
//        )
//    }
//
//    // MainActivity.kt
//// ... (keep the existing code from the top of the file)
//// This is the GATT callback object
//    private val gattCallback = object : BluetoothGattCallback() {
//        @SuppressLint("MissingPermission")
//        override fun onConnectionStateChange(
//            gatt: BluetoothGatt,
//            status: Int,
//            newState: Int
//        ) {
//            when (newState) {
//                BluetoothProfile.STATE_CONNECTED -> {
//                    runOnUiThread {
//                        isConnected = true
//                        addStatus("Connected to camera. Discovering services...")
//                        // Discover services after connecting
//                        gatt.discoverServices()
//                    }
//                }
//
//                BluetoothProfile.STATE_DISCONNECTED -> {
//                    runOnUiThread {
//                        isConnected = false
//                        addStatus("Disconnected from camera.")
//                        // Clean up resources
//                        stopLocationUpdates()
//                        bluetoothGatt?.close()
//                        bluetoothGatt = null
//                    }
//                }
//            }
//        }
//
//        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
//            if (status == BluetoothGatt.GATT_SUCCESS) {
//                addStatus("Services discovered. Starting location updates.")
//                // Start sending location data now that services are ready
//                startLocationUpdates()
//            } else {
//                addStatus("Service discovery failed with status: $status")
//            }
//        }
//
//        // You can add onCharacteristicWrite if you need to confirm data was sent
//        override fun onCharacteristicWrite(
//            gatt: BluetoothGatt?,
//            characteristic: BluetoothGattCharacteristic?,
//            status: Int
//        ) {
//            if (status == BluetoothGatt.GATT_SUCCESS) {
//                Log.d("GattCallback", "Location data sent successfully.")
//            } else {
//                addStatus("Failed to write location data. Status: $status")
//            }
//        }
//    }
//
//    // ... (keep the existing code like requestPermissions, startScanning, etc.)
//    @SuppressLint("MissingPermission")
//    private fun startLocationUpdates() {
//        if (!hasPermissions()) {
//            addStatus("Cannot start location updates: missing permissions.")
//            return
//        }
//
//        // Get the initial last known location
//        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
//            if (location != null) {
//                currentLocation = location
//                sendLocationData(location)
//            }
//        }
//
//        // Set up a recurring task to send location updates every 5 seconds
//        locationUpdateRunnable = Runnable {
//            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
//                .addOnSuccessListener { location: Location? ->
//                    if (location != null) {
//                        runOnUiThread {
//                            currentLocation = location
//                            val now = Calendar.getInstance()
//                            lastSyncTime = "%02d:%02d:%02d".format(
//                                now.get(Calendar.HOUR_OF_DAY),
//                                now.get(Calendar.MINUTE),
//                                now.get(Calendar.SECOND)
//                            )
//                        }
//                        sendLocationData(location)
//                        // Schedule the next update
//                        handler.postDelayed(locationUpdateRunnable!!, 5000)
//                    } else {
//                        addStatus("Failed to get current location.")
//                        // Retry after a delay
//                        handler.postDelayed(locationUpdateRunnable!!, 5000)
//                    }
//                }
//        }
//        // Start the first update immediately
//        handler.post(locationUpdateRunnable!!)
//    }
//
//    @SuppressLint("MissingPermission")
//    private fun sendLocationData(location: Location) {
//        val gatt = bluetoothGatt ?: run {
//            addStatus("Cannot send data, not connected.")
//            return
//        }
//        val service = gatt.getService(locationServiceUuid) ?: run {
//            addStatus("Location service not found.")
//            return
//        }
//        val characteristic = service.getCharacteristic(locationCharacteristicUuid) ?: run {
//            addStatus("Location characteristic not found.")
//            return
//        }
//
//        // Create the 19-byte payload for the Sony camera
//        val buffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
//        val status = 0x02 // Status bitmask: 0x02 means location is active
//
//        buffer.put(0x01) // Data Set ID (always 0x01)
//        buffer.put(status.toByte()) // Status bitmask
//        buffer.putInt((location.latitude * 1_000_000).toInt()) // Latitude
//        buffer.putInt((location.longitude * 1_000_000).toInt()) // Longitude
//        buffer.putShort((location.altitude * 10).toInt().toShort()) // Altitude
//        buffer.putInt(0) // Reserved
//        buffer.putInt((System.currentTimeMillis() / 1000).toInt()) // UTC timestamp in seconds
//
//        val payload = buffer.array()
//
//        // Set the value on the characteristic
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            gatt.writeCharacteristic(
//                characteristic,
//                payload,
//                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
//            )
//        } else {
//            @Suppress("DEPRECATION")
//            characteristic.value = payload
//            @Suppress("DEPRECATION")
//            gatt.writeCharacteristic(characteristic)
//        }
//        Log.d("sendLocationData", "Attempting to send ${payload.size} bytes.")
//    }
//
//    private fun stopLocationUpdates() {
//        locationUpdateRunnable?.let {
//            handler.removeCallbacks(it)
//            locationUpdateRunnable = null
//            addStatus("Location updates stopped.")
//        }
//    }
//
//    @SuppressLint("MissingPermission")
//    private fun disconnect() {
//        bluetoothGatt?.disconnect()
//        bluetoothGatt?.close()
//        bluetoothGatt = null
//        isConnected = false
//        stopLocationUpdates()
//    }
//
//    private fun hasPermissions(): Boolean {
//        val permissions = mutableListOf(
//            Manifest.permission.ACCESS_FINE_LOCATION
//        )
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
//            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
//        }
//
//        return permissions.all {
//            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
//        }
//    }
//
//    private fun addStatus(message: String) {
//        handler.post {
//            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
//                .format(Date())
//            statusMessages.add("[$timestamp] $message")
//            if (statusMessages.size > 100) {
//                statusMessages.removeAt(0)
//            }
//            Log.d("SonyCameraGPS", message)
//        }
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        unregisterReceiver(pairingReceiver)
//        disconnect()
//    }
//}
