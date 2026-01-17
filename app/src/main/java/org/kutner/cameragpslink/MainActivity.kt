package org.kutner.cameragpslink

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.LaunchedEffect
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
import org.kutner.cameragpslink.composables.LogCard
import org.kutner.cameragpslink.composables.SearchDialog
import org.kutner.cameragpslink.ui.theme.CameraGpsLinkTheme
import org.kutner.cameragpslink.composables.LanguageSelectionDialog
import org.kutner.cameragpslink.composables.ReorderableCameraList
import org.kutner.cameragpslink.composables.RemoteControlDialog

class MainActivity : AppCompatActivity() {

    private var cameraSyncService: CameraSyncService? = null
    private val isBound: MutableState<Boolean> = mutableStateOf(false)
    private val deepLinkCameraAddress: MutableState<String?> = mutableStateOf(null)

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

        handleIntent(intent)

        setContent {
            CameraGpsLinkTheme {
                val bound = isBound.value
                val service = cameraSyncService
                val deepLinkAddress = deepLinkCameraAddress.value

                if (bound && service != null) {
                    MainScreen(
                        log = service.log,
                        service = service,
                        connectedCameras = service.connectedCameras,
                        foundDevices = service.foundDevices,
                        isManualScanning = service.isManualScanning,
                        shutterErrorMessage = service.shutterErrorMessage,
                        showBondingErrorDialog = service.showBondingErrorDialog,
                        deepLinkCameraAddress = deepLinkAddress,
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
                        onCameraSettings = { address, mode, enabled, duration, autoFocus, customName ->
                            AppSettingsManager.updateCameraSettings(this, address, mode, enabled, duration, autoFocus, customName)
                            service.onSettingsChanged(address)
                        },
                        onRemoteCommand = { address, command ->
                            service.sendRemoteCommand(address, command)
                        },
                        onAddToHomeScreen = { address, cameraName ->
                            createRemoteControlShortcut(address, cameraName)
                        },
                        onClearLog = { service.clearLog() },
                        onShareLog = { shareLog(service.getLogAsString()) },
                        onRateApp = { launchReviewFlow() },
                        onDismissShutterError = { service.clearShutterError() },
                        onDismissBondingError = { service.dismissBondingErrorDialog() },
                        onDismissDeepLink = { deepLinkCameraAddress.value = null }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Check if the activity was launched from the Recent Apps list
        val isFromHistory = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (isFromHistory) {
            return
        }

        intent?.data?.let { uri ->
            if (uri.scheme == "cameragpslink" && uri.host == "remote") {
                val cameraAddress = uri.getQueryParameter("address")
                deepLinkCameraAddress.value = cameraAddress

                // Clear the data from the intent so it isn't processed again
                // if handleIntent is called by a configuration change (like rotation)
                intent.data = null
            }
        }
    }

    private fun createRemoteControlShortcut(cameraAddress: String, cameraName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = getSystemService(ShortcutManager::class.java)

            if (shortcutManager.isRequestPinShortcutSupported) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("cameragpslink://remote?address=$cameraAddress")
                    setClass(this@MainActivity, MainActivity::class.java)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }

                val shortcut = ShortcutInfo.Builder(this, "remote_$cameraAddress")
                    .setShortLabel(getString(R.string.shortcut_remote_control_short, cameraName))
                    .setLongLabel(getString(R.string.shortcut_remote_control_long, cameraName))
                    .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(intent)
                    .build()

                shortcutManager.requestPinShortcut(shortcut, null)
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
        try {
            // Try to open the Play Store app directly
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
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
    deepLinkCameraAddress: String?,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onConnectToDevice: (FoundDevice) -> Unit,
    onTriggerShutter: (String) -> Unit,
    onForgetDevice: (String) -> Unit,
    onCameraSettings: (String, Int, Boolean, Int, Boolean, String?) -> Unit,
    onRemoteCommand: (String, RemoteControlCommand) -> Unit,
    onAddToHomeScreen: (String, String) -> Unit,
    onClearLog: () -> Unit,
    onShareLog: () -> Unit,
    onRateApp: () -> Unit,
    onDismissShutterError: () -> Unit,
    onDismissBondingError: () -> Unit,
    onDismissDeepLink: () -> Unit
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
    var isReorderMode by remember { mutableStateOf(false) }

    var uiRefreshTrigger by remember { mutableStateOf(0) }

    // Handle deep link to show remote control dialog
    LaunchedEffect(deepLinkCameraAddress) {
        if (deepLinkCameraAddress != null) {
            // Find if camera exists in saved cameras
            val hasSavedCamera = AppSettingsManager.hasSavedCamera(context, deepLinkCameraAddress)
            if (hasSavedCamera) {
                // Show remote control dialog for this camera
                // This will be handled below
            }
        }
    }

    val handleCameraSettings: (String, Int, Boolean, Int, Boolean, String?) -> Unit = { address, mode, enabled, duration, autoFocus, customName ->
        onCameraSettings(address, mode, enabled, duration, autoFocus, customName)
        uiRefreshTrigger++  // Trigger UI refresh after settings are saved
    }

    // Handle back press to close menu or exit reorder mode
    BackHandler(enabled = showMenu || isReorderMode) {
        when {
            showMenu -> showMenu = false
            isReorderMode -> isReorderMode = false
        }
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
        val connection = cameras.find { it.device.address == bondingErrorDeviceAddress }
        val cameraName = AppSettingsManager.getCameraName(context, bondingErrorDeviceAddress ?: "", connection?.device?.name)
        BondingErrorDialog(
            cameraName = cameraName,
            onDismiss = onDismissBondingError
        )
    }

    // Deep link remote control dialog
    if (deepLinkCameraAddress != null) {
        val connection = cameras.find { it.device.address == deepLinkCameraAddress }
        val cameraName = AppSettingsManager.getCameraName(context, deepLinkCameraAddress, connection?.device?.name)

        RemoteControlDialog(
            cameraAddress = deepLinkCameraAddress,
            service = service,
            isConnected = connection?.isConnected ?: false,
            onDismiss = onDismissDeepLink,
            onRemoteCommand = onRemoteCommand,
            onSaveAutoFocus = { autoFocus ->
                val currentSettings = AppSettingsManager.getCameraSettings(context, deepLinkCameraAddress)
                handleCameraSettings(
                    deepLinkCameraAddress,
                    currentSettings.connectionMode,
                    currentSettings.quickConnectEnabled,
                    currentSettings.quickConnectDurationMinutes,
                    autoFocus,
                    currentSettings.customName ?: ""
                )
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.app_name)) },
                actions = {
                    if (isReorderMode) {
                        // Show checkmark in reorder mode
                        IconButton(onClick = {
                            isReorderMode = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.CheckBox,
                                contentDescription = "Save order"
                            )
                        }
                    } else {
                        // Show normal menu
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
                            if (cameras.size > 1) {
                                DropdownMenuItem(
                                    text = { Text(context.getString(R.string.menu_rearrange_cameras)) },
                                    onClick = {
                                        isReorderMode = true
                                        showMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.DragHandle,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
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
                }
            )
        },
        floatingActionButton = {
            if (!isReorderMode) {
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
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // Main content
            if (cameras.isEmpty()) {
                // Empty state
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
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val minLogHeight = 400.dp
                        val maxCameraHeight = maxHeight - minLogHeight

                        Column(modifier = Modifier.fillMaxSize()) {
                            ReorderableCameraList(
                                connectedCameras = cameras,
                                isReorderMode = isReorderMode,
                                refreshTrigger = uiRefreshTrigger,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = maxCameraHeight)
                                    .padding(16.dp),
                                service = service,
                                onTriggerShutter = onTriggerShutter,
                                onForgetDevice = onForgetDevice,
                                onCameraSettings = handleCameraSettings,
                                onRemoteCommand = onRemoteCommand,
                                onLongPress = {
                                    if (!isReorderMode) {
                                        isReorderMode = true
                                    }
                                },
                                onAddToHomeScreen = onAddToHomeScreen
                            )

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
                    ReorderableCameraList(
                        connectedCameras = cameras,
                        isReorderMode = isReorderMode,
                        refreshTrigger = uiRefreshTrigger,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        service = service,
                        onTriggerShutter = onTriggerShutter,
                        onForgetDevice = onForgetDevice,
                        onCameraSettings = handleCameraSettings,
                        onRemoteCommand = onRemoteCommand,
                        onLongPress = {
                            if (!isReorderMode) {
                                isReorderMode = true
                            }
                        },
                        onAddToHomeScreen = onAddToHomeScreen
                    )
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