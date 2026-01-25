package org.kutner.cameragpslink

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
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
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import com.google.android.play.core.review.ReviewManagerFactory
import org.kutner.cameragpslink.composables.*
import org.kutner.cameragpslink.ui.theme.CameraGpsLinkTheme

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
            // After standard permissions, check battery optimization
            if (!isBatteryOptimizationDisabled(this)) {
                showBatteryOptimizationInstructionDialog()
            } else {
                proceedWithServiceSetup()
            }
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // After returning from battery settings, proceed with setup
        proceedWithServiceSetup()
    }

    private fun proceedWithServiceSetup() {
        bindToService()
        // Only start service if we have saved cameras
        val savedCameras = AppSettingsManager.getSavedCameras(this)
        if (savedCameras.isNotEmpty()) {
            startCameraService()
        }
    }

    override fun onStart() {
        super.onStart()
        NotificationHelper(context = this).clearBootNotification()

        if (hasRequiredPermissions()) {
            bindToService()
            // Only start service if we have saved cameras
            val savedCameras = AppSettingsManager.getSavedCameras(this)
            if (savedCameras.isNotEmpty()) {
                startCameraService()
            }
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
                        service = service,
                        deepLinkCameraAddress = deepLinkAddress,
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

    override fun onResume() {
        super.onResume()
        showInAppReview()
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

    fun createRemoteControlShortcut(cameraAddress: String, cameraName: String) {
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

        val hasStandardPermissions = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        return hasStandardPermissions && isBatteryOptimizationDisabled(this)
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

    private fun showBatteryOptimizationInstructionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.battery_optimization_instruction_title)
            .setMessage(R.string.battery_optimization_instruction_message)
            .setPositiveButton(R.string.button_continue) { dialog, _ ->
                dialog.dismiss()
                requestBatteryOptimizationExemption()
            }
            .setNegativeButton(R.string.button_skip) { dialog, _ ->
                dialog.dismiss()
                proceedWithServiceSetup()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            batteryOptimizationLauncher.launch(intent)
            cameraSyncService?.log("Requesting battery optimization exemption")
        } catch (e: Exception) {
            cameraSyncService?.log("Direct battery exemption not available: ${e.message}")
            // If direct intent fails, open general settings
            openGeneralBatterySettings()
        }
    }

    private fun openGeneralBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            batteryOptimizationLauncher.launch(intent)
            cameraSyncService?.log("Opened general battery settings")
        } catch (e: Exception) {
            cameraSyncService?.log("Failed to open battery settings: ${e.message}")
            proceedWithServiceSetup()
        }
    }

    private fun bindToService() {
        Intent(this, CameraSyncService::class.java).also { intent ->
            bindService(intent, connection, BIND_AUTO_CREATE)
        }
    }

    private fun showInAppReview() {
        val installTime = AppSettingsManager.getInstallTime(this)
        val lastPromptTime = AppSettingsManager.getRatingPromptTime(this)
        val currentTime = System.currentTimeMillis()
        val millisPerDay = 1000 * 3600 * 24
        val daysSinceInstall = (currentTime - installTime) / millisPerDay
        val daysSinceLastPrompt = (currentTime - lastPromptTime) / millisPerDay

        if (lastPromptTime < 0 || daysSinceLastPrompt < 30 || daysSinceInstall < 30) {
            return
        }

        // Show the in-app review
        val reviewManager = ReviewManagerFactory.create(this)
        val request = reviewManager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val reviewInfo = task.result
                val flow = reviewManager.launchReviewFlow(this, reviewInfo)

                flow.addOnCompleteListener {
                    // Record that we showed the prompt
                    AppSettingsManager.setRatingPromptTime(this, currentTime)
                }
            } else {
                // Request failed, so we set a fictitious time to avoid showing the prompt again for 1 day
                AppSettingsManager.setRatingPromptTime(this, currentTime - 29 * millisPerDay)
            }
        }
    }

    fun launchReviewFlow() {
        // This is for the manual "Rate App" menu item
        try {
            // Try to open the Play Store app directly
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            // Disable future automatic prompts since user is actively choosing to rate
            AppSettingsManager.setRatingPromptTime(this,-1)
        } catch (e: ActivityNotFoundException) {
            // Fallback to the browser if the Play Store app is not installed
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
}

// Helper function to check if battery optimization is ignored
fun isBatteryOptimizationDisabled(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

// MainScreen composable and helper functions
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun MainScreen(
    service: CameraSyncService,
    deepLinkCameraAddress: String?,
    onDismissDeepLink: () -> Unit
) {
    val context = LocalContext.current

    // Observe state from service
    val logMessages by service.log.collectAsState()
    val cameras by service.connectedCameras.collectAsState()
    val scanning by service.isManualScanning.collectAsState()
    val devices by service.foundDevices.collectAsState()
    val errorMessage by service.shutterErrorMessage.collectAsState()
    val bondingErrorDeviceAddress by service.showBondingErrorDialog.collectAsState()

    // Local UI state
    var showLog by remember { mutableStateOf(AppSettingsManager.isShowLogEnabled(context)) }
    var showMenu by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var isReorderMode by remember { mutableStateOf(false) }

    // Handle deep link to show remote control dialog
    LaunchedEffect(deepLinkCameraAddress) {
        if (deepLinkCameraAddress != null) {
            val hasSavedCamera = AppSettingsManager.hasSavedCamera(context, deepLinkCameraAddress)
            if (!hasSavedCamera) {
                onDismissDeepLink()
            }
        }
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
            onDismissRequest = { service.clearShutterError() },
            title = { Text(context.getString(R.string.error_shutter_title)) },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { service.clearShutterError() }) {
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
            onDismiss = { service.dismissBondingErrorDialog() }
        )
    }

    // Deep link remote control dialog
    if (deepLinkCameraAddress != null) {
        val connection = cameras.find { it.device.address == deepLinkCameraAddress }
        if (connection == null) {
            onDismissDeepLink()
            return
        }

        val cameraName = AppSettingsManager.getCameraName(context, deepLinkCameraAddress, connection?.device?.name)

        RemoteControlDialog(
            cameraAddress = deepLinkCameraAddress,
            service = service,
            connection = connection,
            onDismiss = onDismissDeepLink,
            onRemoteCommand = { address, cmd -> service.sendRemoteCommand(deepLinkCameraAddress, cmd) },
            onSaveAutoFocus = { autoFocus ->
                val currentSettings = AppSettingsManager.getCameraSettings(context, deepLinkCameraAddress)
                AppSettingsManager.updateCameraSettings(context,deepLinkCameraAddress, enableHalfShutterPress = autoFocus)
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
                        TopBarMenu(
                            showMenu = showMenu,
                            onShowMenuChange = { showMenu = it },
                            showLog = showLog,
                            canRearrange = cameras.size > 1,
                            onToggleLog = {
                                showLog = !showLog
                                AppSettingsManager.setShowLogEnabled(context, showLog)
                            },
                            onRearrange = { isReorderMode = true },
                            onShowLanguage = { showLanguageDialog = true },
                            onShowHelp = {
                                val helpIntent = Intent(Intent.ACTION_VIEW, context.getString(R.string.help_page_url).toUri())
                                context.startActivity(helpIntent)
                            },
                            onRateApp = {
                                if (context is MainActivity) {
                                    context.launchReviewFlow()
                                }
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (!isReorderMode) {
                FloatingActionButton(
                    onClick = {
                        showSearchDialog = true
                        service.startManualScan()
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
                EmptyState(showLog = showLog, logMessages = logMessages, service = service)
            } else {
                CameraListContent(
                    cameras = cameras,
                    showLog = showLog,
                    isReorderMode = isReorderMode,
                    logMessages = logMessages,
                    service = service,
                    onLongPress = {
                        if (!isReorderMode) {
                            isReorderMode = true
                        }
                    }
                )
            }

            // Search dialog - handles its own events
            if (showSearchDialog) {
                SearchDialog(
                    isScanning = scanning,
                    foundDevices = devices,
                    onDismiss = {
                        service.stopManualScan()
                        showSearchDialog = false
                    },
                    onConnect = { device ->
                        service.connectToDevice(device.device, device)
                        showSearchDialog = false
                    },
                    onCancelScan = { service.stopManualScan() },
                    onRefresh = { service.startManualScan() }
                )
            }

            // Language selection dialog - handles its own events
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

@Composable
private fun TopBarMenu(
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    showLog: Boolean,
    canRearrange: Boolean,
    onToggleLog: () -> Unit,
    onRearrange: () -> Unit,
    onShowLanguage: () -> Unit,
    onShowHelp: () -> Unit,
    onRateApp: () -> Unit
) {
    val context = LocalContext.current

    IconButton(onClick = { onShowMenuChange(true) }) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = "Menu"
        )
    }
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { onShowMenuChange(false) },
        properties = PopupProperties(focusable = false)
    ) {
        if (canRearrange) {
            DropdownMenuItem(
                text = { Text(context.getString(R.string.menu_rearrange_cameras)) },
                onClick = {
                    onRearrange()
                    onShowMenuChange(false)
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
                onToggleLog()
                onShowMenuChange(false)
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
                onShowLanguage()
                onShowMenuChange(false)
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
                onShowHelp()
                onShowMenuChange(false)
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
                onShowMenuChange(false)
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

@Composable
private fun EmptyState(
    showLog: Boolean,
    logMessages: List<String>,
    service: CameraSyncService
) {
    val context = LocalContext.current

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
                onClearLog = { service.clearLog() },
                onShareLog = { shareLog(context, service.getLogAsString()) }
            )
        }
    }
}

@Composable
private fun CameraListContent(
    cameras: List<CameraConnection>,
    showLog: Boolean,
    isReorderMode: Boolean,
    logMessages: List<String>,
    service: CameraSyncService,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current

    if (showLog) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val minLogHeight = 400.dp
            val maxCameraHeight = maxHeight - minLogHeight

            Column(modifier = Modifier.fillMaxSize()) {
                ReorderableCameraList(
                    connectedCameras = cameras,
                    isReorderMode = isReorderMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxCameraHeight)
                        .padding(16.dp),
                    service = service,
                    onLongPress = onLongPress
                )

                LogCard(
                    logMessages = logMessages,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    onClearLog = { service.clearLog() },
                    onShareLog = { shareLog(context, service.getLogAsString()) }
                )
            }
        }
    } else {
        ReorderableCameraList(
            connectedCameras = cameras,
            isReorderMode = isReorderMode,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            service = service,
            onLongPress = onLongPress
        )
    }
}

// Extension function for sharing log
fun shareLog(context: android.content.Context, logText: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Camera GPS Link Log")
        putExtra(Intent.EXTRA_TEXT, logText)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Log"))
}