package org.kutner.cameragpslink.composables

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType.Companion.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import org.kutner.cameragpslink.R
import org.kutner.cameragpslink.*


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectedCameraCard(
    modifier: Modifier = Modifier,
    connection: CameraConnection,
    service: CameraSyncService,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false,
    elevation: Dp = 2.dp,
    dragModifier: Modifier = Modifier,
    onLongPress: () -> Unit = {}
) {
    val context = LocalContext.current
    val address = connection.device.address

    // Dummy settings version to force recomposition when settings change
    var settingsVersion by remember { mutableIntStateOf(0) }

    // Get camera name
    val cameraName = remember(connection.device.address, settingsVersion) {
        AppSettingsManager.getCameraName(context, address, connection.device.name)
    }

    // Observe state from service
    val remoteEnabledMap by service.isRemoteControlEnabled.collectAsState()
    val isRemoteControlEnabled = remoteEnabledMap[address] ?: false

    val focusStates by service.isFocusAcquired.collectAsState()
    val isFocused = focusStates[address] ?: false

    // Local UI state
    var showMenu by remember { mutableStateOf(false) }
    var showCameraSettingsDialog by remember { mutableStateOf(false) }
    var showRemoteControlDialog by remember { mutableStateOf(false) }

    // Handle back press to close menu
    BackHandler(enabled = showMenu) {
        showMenu = false
    }

    // Automatically close RemoteControlDialog if camera disconnects
    LaunchedEffect(connection.isConnected) {
        if (!connection.isConnected) {
            showRemoteControlDialog = false
        }
    }

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .then(
                    if (isDragging) {
                        Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle - only visible in reorder mode
            if (isReorderMode) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    modifier = Modifier
                        .size(32.dp)
                        .padding(end = 8.dp)
                        .then(dragModifier),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Rest of the card content
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isReorderMode) {
                                Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = onLongPress
                                )
                            } else {
                                Modifier
                            }
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                                contentDescription = "App Icon",
                                modifier = Modifier.size(80.dp),
                                colorFilter = if (!connection.isConnected) {
                                    ColorFilter.colorMatrix(
                                        ColorMatrix().apply {
                                            setToSaturation(0f)
                                        }
                                    )
                                } else {
                                    null
                                }
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = when {
                                    !connection.isBonded -> context.getString(R.string.camera_state_pairing_not_completed)
                                    connection.isConnected -> context.getString(R.string.camera_state_connected)
                                    connection.isConnecting -> context.getString(R.string.camera_state_connecting)
                                    else -> context.getString(R.string.camera_state_disconnected)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    !connection.isBonded -> MaterialTheme.colorScheme.error
                                    connection.isConnected -> Color(0xFF4CAF50)
                                    connection.isConnecting -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                },
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = cameraName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Hide menu in reorder mode
                    if (!isReorderMode) {
                        CameraMenu(
                            showMenu = showMenu,
                            onShowMenuChange = { showMenu = it },
                            onShowSettings = { showCameraSettingsDialog = true },
                            onAddToHomeScreen = {
                                createRemoteControlShortcut(context, address, cameraName)
                            },
                            onRemoveCamera = {
                                service.forgetDevice(address)
                            }
                        )
                    }
                }

                // Show buttons only when not in reorder mode AND connected
                if (!isReorderMode && connection.isConnected) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .combinedClickable(
                                    onClick = { showRemoteControlDialog = true },
                                    onLongClick = { /* Consume long press */ }
                                )
                        ) {
                            Button(
                                onClick = { showRemoteControlDialog = true },
                                enabled = connection.isConnected,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(
                                    text = context.getString(R.string.action_remote),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .combinedClickable(
                                    onClick = { service.triggerShutter(address) },
                                    onLongClick = { /* Consume long press */ }
                                )
                        ) {
                            Button(
                                onClick = { service.triggerShutter(address) },
                                enabled = connection.isConnected && isRemoteControlEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isFocused) Color.Green else MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(
                                    text = context.getString(R.string.action_shutter),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showCameraSettingsDialog) {
        CameraSettingsDialog(
            cameraAddress = address,
            onDismiss = { showCameraSettingsDialog = false },
            onSave = { mode, enabled, duration, customName ->
                AppSettingsManager.updateCameraSettings(context, address, mode = mode, quickConnectEnabled = enabled, durationMinutes = duration, customName = customName)
                service.onSettingsChanged(address)
                settingsVersion++
                showCameraSettingsDialog = false
            }
        )
    }

    if (showRemoteControlDialog) {
        RemoteControlDialog(
            cameraAddress = address,
            service = service,
            connection = connection,
            onDismiss = { showRemoteControlDialog = false },
            onRemoteCommand = { address, cmd -> service.sendRemoteCommand(address, cmd) },
            onSaveAutoFocus = { autoFocus ->
                AppSettingsManager.updateCameraSettings(context,address, enableHalfShutterPress = autoFocus)
            }
        )
    }
}

@Composable
private fun CameraMenu(
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onShowSettings: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    onRemoveCamera: () -> Unit
) {
    val context = LocalContext.current

    Box {
        IconButton(onClick = { onShowMenuChange(true) }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Options")
        }
        DropdownMenu(
            expanded = showMenu,
            properties = PopupProperties(focusable = false),
            onDismissRequest = { onShowMenuChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text(context.getString(R.string.menu_settings)) },
                onClick = {
                    onShowSettings()
                    onShowMenuChange(false)
                }
            )
            DropdownMenuItem(
                text = { Text(context.getString(R.string.menu_add_remote_to_home)) },
                onClick = {
                    onAddToHomeScreen()
                    onShowMenuChange(false)
                }
            )
            DropdownMenuItem(
                text = { Text(context.getString(R.string.menu_remove_camera)) },
                onClick = {
                    onRemoveCamera()
                    onShowMenuChange(false)
                }
            )
        }
    }
}

private fun createRemoteControlShortcut(context: Context, cameraAddress: String, cameraName: String) {
    // Call the MainActivity method if context is MainActivity
    if (context is MainActivity) {
        context.createRemoteControlShortcut(cameraAddress, cameraName)
    }
}