package org.kutner.cameragpslink

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import org.kutner.cameragpslink.composables.ConnectedCameraCard
import org.kutner.cameragpslink.composables.FoundCameraCard
import org.kutner.cameragpslink.composables.LogCard
import org.kutner.cameragpslink.composables.SearchDialog
import org.kutner.cameragpslink.composables.CameraSettingsDialog
import org.kutner.cameragpslink.ui.theme.CameraGpsLinkTheme

class MainActivity : ComponentActivity() {

    private var cameraSyncService: CameraSyncService? = null
    private val isBound: MutableState<Boolean> = mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as CameraSyncService.LocalBinder
            cameraSyncService = binder.getService()
            isBound.value = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound.value = false
            cameraSyncService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            bindToService()
            // Only start service if we have saved cameras
            val savedCameras = CameraSettingsManager.getSavedCameras(this)
            if (savedCameras.isNotEmpty()) {
                startCameraService()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dismissBootNotification()

        if (hasRequiredPermissions()) {
            // Only start service if we have saved cameras
            val savedCameras = CameraSettingsManager.getSavedCameras(this)
            if (savedCameras.isNotEmpty()) {
                startCameraService()
            }
            bindToService()
        } else {
            requestRequiredPermissions()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound.value) {
            unbindService(connection)
            isBound.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CameraGpsLinkTheme {
                val bound = isBound.value
                val service = cameraSyncService

                if (bound && service != null) {
                    MainScreen(
                        log = service.log,
                        connectedCameras = service.connectedCameras,
                        foundDevices = service.foundDevices,
                        isManualScanning = service.isManualScanning,
                        shutterErrorMessage = service.shutterErrorMessage,
                        onStartScan = { service.startManualScan() },
                        onCancelScan = { service.stopManualScan() },
                        onConnectToDevice = {
                            // Ensure the service is started as a service, not just bound
                            startCameraService()
                            service.stopManualScan()
                            service.connectToDevice(it)
                        },
                        onTriggerShutter = { address -> service.triggerShutter(address) },
                        onForgetDevice = { address -> service.forgetDevice(address) },
                        onCameraSettings = { address, mode, enabled, duration ->
                            CameraSettingsManager.updateCameraSettings(this, address, mode, enabled, duration)
                            service.onSettingsChanged(address)
                        },
                        onClearLog = { service.clearLog() },
                        onShareLog = { shareLog(service.getLogAsString()) },
                        onDismissShutterError = { service.clearShutterError() }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    private fun startCameraService() {
        val serviceIntent = Intent(this, CameraSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
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

    private fun shareLog(logText: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Camera GPS Link Log")
            putExtra(Intent.EXTRA_TEXT, logText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Log"))
    }
    private fun dismissBootNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(1000)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun MainScreen(
    log: StateFlow<List<String>>,
    connectedCameras: StateFlow<List<CameraConnection>>,
    foundDevices: StateFlow<List<BluetoothDevice>>,
    isManualScanning: StateFlow<Boolean>,
    shutterErrorMessage: StateFlow<String?>,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onConnectToDevice: (BluetoothDevice) -> Unit,
    onTriggerShutter: (String) -> Unit,
    onForgetDevice: (String) -> Unit,
    onCameraSettings: (String, Int, Boolean, Int) -> Unit,
    onClearLog: () -> Unit,
    onShareLog: () -> Unit,
    onDismissShutterError: () -> Unit
) {
    val context = LocalContext.current
    
    val logMessages by log.collectAsState()
    val cameras by connectedCameras.collectAsState()
    val scanning by isManualScanning.collectAsState()
    val devices by foundDevices.collectAsState()
    val errorMessage by shutterErrorMessage.collectAsState()

    var showLog by remember { mutableStateOf(CameraSettingsManager.isShowLogEnabled(context)) }
    var showMenu by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }

    // Show error dialog when there's an error message
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissShutterError,
            title = { Text(Constants.ERROR_SHUTTER_TITLE) },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = onDismissShutterError) {
                    Text("OK")
                }
            }
        )
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(Constants.APP_NAME) },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu"
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Show Log") },
                            onClick = {
                                showLog = !showLog
                                CameraSettingsManager.setShowLogEnabled(context, showLog)
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (showLog) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear Log") },
                            onClick = {
                                onClearLog()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share Log") },
                            onClick = {
                                onShareLog()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Help") },
                            onClick = {
                                val helpIntent = Intent(Intent.ACTION_VIEW, Uri.parse(Constants.HELP_PAGE_URL))
                                context.startActivity(helpIntent)
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Help,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    showSearchDialog = true
                    onStartScan()
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Camera"
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // Main content
            if (cameras.isEmpty()) {
                // Empty state - show when no cameras are connected/saved
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .then(if (showLog) Modifier.weight(1f) else Modifier.fillMaxSize())
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Press \"+\" to add a camera",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    // Log section - fills remaining space
                    if (showLog) {
                        LogCard(logMessages, Modifier.weight(1f))
                    }
                }
            }
            else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Connected cameras
                    if (cameras.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
//                                .then(if (showLog) Modifier.weight(1f) else Modifier.fillMaxSize())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(cameras, key = { it.device.address }) { connection ->
                                var showSettings by remember { mutableStateOf(false) }

                                ConnectedCameraCard(
                                    cameraName = connection.device.name ?: "Unknown Camera",
                                    cameraAddress = connection.device.address,
                                    isConnected = connection.isConnected,
                                    isConnecting = connection.isConnecting,
                                    onShutter = { onTriggerShutter(connection.device.address) },
                                    onDisconnect = { onForgetDevice(connection.device.address) },
                                    onCameraSettings = { connectionMode, quickConnectEnabled, duration ->
                                        onCameraSettings(connection.device.address, connectionMode, quickConnectEnabled, duration)
                                    }
                                )
                            }
                        }
                    }

                    // Log section - fills remaining space
                    if (showLog) {
                        LogCard(logMessages, Modifier.weight(1f))
                    }
                }
            }

            // Search dialog
            if (showSearchDialog) {
                SearchDialog(
                    isScanning = scanning,
                    foundDevices = devices,
                    onDismiss = { showSearchDialog = false },
                    onConnect = { device ->
                        onConnectToDevice(device)
                        showSearchDialog = false
                    },
                    onCancelScan = onCancelScan
                )
            }
        }
    }
}
