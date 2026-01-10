package org.kutner.cameragpslink

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
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
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.StateFlow
import org.kutner.cameragpslink.composables.BondingErrorDialog
//import com.google.android.play.core.review.ReviewManagerFactory
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
        NotificationHelper(context = this).clearBootNotification()

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
                        service = service,
                        connectedCameras = service.connectedCameras,
                        foundDevices = service.foundDevices,
                        isManualScanning = service.isManualScanning,
                        shutterErrorMessage = service.shutterErrorMessage,
                        showBondingErrorDialog = service.showBondingErrorDialog,
                        onStartScan = { service.startManualScan() },
                        onCancelScan = { service.stopManualScan() },
                        onConnectToDevice = {
                            // Ensure the service is started as a service, not just bound
                            startCameraService()
                            service.stopManualScan()
                            service.connectToDevice(it.device, it)
                        },
                        onTriggerShutter = { address -> service.triggerShutter(address) },
                        onForgetDevice = { address -> service.forgetDevice(address) },
                        onCameraSettings = { address, mode, enabled, duration, autoFocus ->
                            AppSettingsManager.updateCameraSettings(this, address, mode, enabled, duration, autoFocus)
                            service.onSettingsChanged(address)
                        },
                        onRemoteCommand = { address, command ->
                            service.sendRemoteCommand(address, command)
                        },
                        onClearLog = { service.clearLog() },
                        onShareLog = { shareLog(service.getLogAsString()) },
                        onRateApp = { launchReviewFlow() },
                        onDismissShutterError = { service.clearShutterError() },
                        onDismissBondingError = { service.dismissBondingErrorDialog() }
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
        startForegroundService(serviceIntent)
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
            bindService(intent, connection, BIND_AUTO_CREATE)
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

    private fun launchReviewFlow() {
//        val manager = ReviewManagerFactory.create(this)
//        val request = manager.requestReviewFlow()
//        request.addOnCompleteListener { task ->
//            if (task.isSuccessful) {
//                val reviewInfo = task.result
//                val flow = manager.launchReviewFlow(this, reviewInfo)
//                flow.addOnCompleteListener { _ ->
//                    // The flow has finished. The API does not indicate whether the user
//                    // reviewed or not, or even whether the review dialog was shown.
//                }
//            }
//            // If the task fails, we simply do nothing (fail silently)
//        }
        try {
            // Try to open the Play Store app directly
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            // This flag is optional but recommended for external links
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Fallback to the browser if the Play Store app is not installed
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun MainScreen(
    log: StateFlow<List<String>>,
    service: CameraSyncService,
    connectedCameras: StateFlow<List<CameraConnection>>,
    foundDevices: StateFlow<List<FoundDevice>>,
    isManualScanning: StateFlow<Boolean>,
    shutterErrorMessage: StateFlow<String?>,
    showBondingErrorDialog: StateFlow<String?>,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onConnectToDevice: (FoundDevice) -> Unit,
    onTriggerShutter: (String) -> Unit,
    onForgetDevice: (String) -> Unit,
    onCameraSettings: (String, Int, Boolean, Int, Boolean) -> Unit,
    onRemoteCommand: (String, RemoteControlCommand) -> Unit,
    onClearLog: () -> Unit,
    onShareLog: () -> Unit,
    onRateApp: () -> Unit,
    onDismissShutterError: () -> Unit,
    onDismissBondingError: () -> Unit
) {
    val context = LocalContext.current
    
    val logMessages by log.collectAsState()
    val cameras by connectedCameras.collectAsState()
    val scanning by isManualScanning.collectAsState()
    val devices by foundDevices.collectAsState()
    val errorMessage by shutterErrorMessage.collectAsState()
    val bondingErrorDeviceAddress by showBondingErrorDialog.collectAsState()

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
    if (bondingErrorDeviceAddress != null) {
        val cameraName = cameras.find { it.device.address == bondingErrorDeviceAddress }?.device?.name
            ?: context.getString(R.string.unknown_camera_name)

        BondingErrorDialog(
            cameraName = cameraName,
            onDismiss = onDismissBondingError
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.app_name)) },
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
                            text = { Text(context.getString(R.string.menu_language)) },
                            onClick = {
                                showLanguageDialog = true
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.menu_help)) },
                            onClick = {
                                val helpIntent = Intent(Intent.ACTION_VIEW, context.getString(R.string.help_page_url).toUri())
                                context.startActivity(helpIntent)
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Help,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.menu_rate_app)) },
                            onClick = {
                                showMenu = false
                                onRateApp()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Star,
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
                        LogCard(
                            logMessages = logMessages,
                            modifier = Modifier.weight(1f),
                            onClearLog = onClearLog,
                            onShareLog = onShareLog
                        )
                    }
                }
            } else {
                if (showLog) {
                    // When log is shown, use BoxWithConstraints to manage space
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val minLogHeight = 400.dp
                        val maxCameraHeight = maxHeight - minLogHeight

                        Column(modifier = Modifier.fillMaxSize()) {
                            // Connected cameras - limited to leave room for log
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = maxCameraHeight)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(cameras, key = { it.device.address }) { connection ->
                                    ConnectedCameraCard(
                                        cameraName = connection.device.name ?: context.getString(R.string.unknown_camera_name),
                                        cameraAddress = connection.device.address,
                                        isBonded = connection.isBonded,
                                        isConnected = connection.isConnected,
                                        isConnecting = connection.isConnecting,
                                        service = service,
                                        onShutter = { onTriggerShutter(connection.device.address) },
                                        onDisconnect = { onForgetDevice(connection.device.address) },
                                        onCameraSettings = { connectionMode, quickConnectEnabled, duration, autoFocus ->
                                            onCameraSettings(connection.device.address, connectionMode, quickConnectEnabled, duration, autoFocus)
                                        },
                                        onRemoteCommand = onRemoteCommand
                                    )
                                }
                            }

                            // Log section - fills all remaining space
                            LogCard(
                                logMessages = logMessages,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(),
                                onClearLog = onClearLog,
                                onShareLog = onShareLog
                            )
                        }
                    }
                } else {
                    // When log is hidden, cameras take full height
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(cameras, key = { it.device.address }) { connection ->
                            ConnectedCameraCard(
                                cameraName = connection.device.name ?: context.getString(R.string.unknown_camera_name),
                                cameraAddress = connection.device.address,
                                isBonded = connection.isBonded,
                                isConnected = connection.isConnected,
                                isConnecting = connection.isConnecting,
                                service = service,
                                onShutter = { onTriggerShutter(connection.device.address) },
                                onDisconnect = { onForgetDevice(connection.device.address) },
                                onCameraSettings = { connectionMode, quickConnectEnabled, duration, autoFocus ->
                                    onCameraSettings(connection.device.address, connectionMode, quickConnectEnabled, duration, autoFocus)
                                },
                                onRemoteCommand = onRemoteCommand
                            )
                        }
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
                    onCancelScan = onCancelScan,
                    onRefresh = { service.startManualScan() }
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
