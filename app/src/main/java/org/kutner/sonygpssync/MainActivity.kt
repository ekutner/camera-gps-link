package org.kutner.sonygpssync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {

    private var cameraSyncService: CameraSyncService? by mutableStateOf(null)
    private var isBound by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as CameraSyncService.LocalBinder
            cameraSyncService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            cameraSyncService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            bindToService()
        } else {
            // You can show a rationale to the user here
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasRequiredPermissions()) {
            bindToService()
        } else {
            requestRequiredPermissions()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                if (isBound) {
                    cameraSyncService?.let { service ->
                        MainScreen(
                            status = service.status,
                            log = service.log,
                            rememberedDevice = service.rememberedDevice,
                            foundDevices = service.foundDevices,
                            isManualScanning = service.isManualScanning,
                            isConnected = service.isConnected,
                            onForgetDevice = { service.forgetDevice() },
                            onStartScan = { service.startManualScan() },
                            onConnectToDevice = { service.connectToDevice(it) },
                            onTriggerShutter = { service.triggerShutter() }
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun bindToService() {
        Intent(this, CameraSyncService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun MainScreen(
    status: StateFlow<String>,
    log: StateFlow<List<String>>,
    rememberedDevice: StateFlow<String?>,
    foundDevices: StateFlow<List<BluetoothDevice>>,
    isManualScanning: StateFlow<Boolean>,
    isConnected: StateFlow<Boolean>,
    onForgetDevice: () -> Unit,
    onStartScan: () -> Unit,
    onConnectToDevice: (BluetoothDevice) -> Unit,
    onTriggerShutter: () -> Unit
) {
    val currentStatus by status.collectAsState()
    val logMessages by log.collectAsState()
    val rememberedDeviceAddress by rememberedDevice.collectAsState()
    val scanning by isManualScanning.collectAsState()
    val devices by foundDevices.collectAsState()
    val connected by isConnected.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Sony Camera Sync") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            Text(currentStatus)
            Spacer(Modifier.height(16.dp))

            // Large Shutter Button
            Button(
                onClick = onTriggerShutter,
                enabled = connected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("SHUTTER", fontSize = 24.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = onStartScan, enabled = rememberedDeviceAddress == null) {
                    Text(if (scanning) "Scanning..." else "Scan for New Camera")
                }
                Button(onClick = onForgetDevice, enabled = rememberedDeviceAddress != null) {
                    Text("Disconnect & Forget")
                }
            }
            if (scanning || devices.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(devices) { device ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onConnectToDevice(device) }) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(device.name ?: "Unknown Device")
                                Text(device.address)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(logMessages) { message ->
                    Text(message)
                }
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        content()
    }
}