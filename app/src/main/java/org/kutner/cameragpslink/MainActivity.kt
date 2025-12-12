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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.StateFlow
import org.kutner.cameragpslink.composables.ConnectedCameraCard
import org.kutner.cameragpslink.composables.LogCard
import org.kutner.cameragpslink.composables.SearchDialog
import org.kutner.cameragpslink.ui.theme.CameraGpsLinkTheme
import org.kutner.cameragpslink.composables.LanguageSelectionDialog


class MainActivity : AppCompatActivity() {

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
            val savedCameras = AppSettingsManager.getSavedCameras(this)
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
            val savedCameras = AppSettingsManager.getSavedCameras(this)
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

        // Initialize the app language before setting content
        val savedLanguage = AppSettingsManager.getSelectedLanguage(this)
        val localeList = if (savedLanguage.isNotEmpty()) {
            LocaleListCompat.forLanguageTags(savedLanguage)
        } else {
            // Explicitly pass an empty list to reset to system default locale
            LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(localeList)

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
                            AppSettingsManager.updateCameraSettings(this, address, mode, enabled, duration)
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

    var showLog by remember { mutableStateOf(AppSettingsManager.isShowLogEnabled(context)) }
    var showMenu by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    // Handle back press to close menu when focusable is false
    BackHandler(enabled = showMenu) {
        showMenu = false
    }

    // Show error dialog when there's an error message
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissShutterError,
            title = { Text(context.getString(R.string.error_shutter_title)) },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = onDismissShutterError) {
                    Text(context.getString(R.string.button_ok))
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
                        onDismissRequest = { showMenu = false },
                        properties = PopupProperties(focusable = false)
                    ) {
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.menu_show_log)) },
                            onClick = {
                                showLog = !showLog
                                AppSettingsManager.setShowLogEnabled(context, showLog)
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
                            text = { Text(context.getString(R.string.menu_clear_log)) },
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
                            text = { Text(context.getString(R.string.menu_share_log)) },
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
//                        DropdownMenuItem(
//                            text = { Text(context.getString(R.string.menu_language)) },
//                            onClick = {
//                                showLanguageDialog = true
//                                showMenu = false
//                            },
//                            leadingIcon = {
//                                Icon(
//                                    imageVector = Icons.Default.Language,
//                                    contentDescription = null
//                                )
//                            }
//                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.menu_help)) },
                            onClick = {
                                val helpIntent = Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.help_page_url)))
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
                                    cameraName = connection.device.name ?: context.getString(R.string.unknown_camera_name),
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

            // Language selection dialog
            if (showLanguageDialog) {
                LanguageSelectionDialog(
                    onDismiss = { showLanguageDialog = false },
                    onLanguageSelected = { languageCode ->
                        val serviceIntent = Intent(context, CameraSyncService::class.java)
                        context.stopService(serviceIntent)

                        LanguageManager.setLanguage(context, languageCode)
                    }
                )
            }
        }
    }
}
