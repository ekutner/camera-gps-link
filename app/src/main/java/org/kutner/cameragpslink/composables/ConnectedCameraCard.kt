package org.kutner.cameragpslink.composables

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import org.kutner.cameragpslink.AppSettingsManager
import org.kutner.cameragpslink.R
import org.kutner.cameragpslink.RemoteControlCommand
import org.kutner.cameragpslink.CameraSyncService

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectedCameraCard(
    modifier: Modifier = Modifier,
    cameraName: String,
    cameraAddress: String,
    isBonded: Boolean,
    isConnected: Boolean,
    isConnecting: Boolean,
    context: Context,
    service: CameraSyncService,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false,
    elevation: Dp = 2.dp,
    dragModifier: Modifier = Modifier,
    onShutter: () -> Unit,
    onDisconnect: () -> Unit,
    onCameraSettings: (Int, Boolean, Int, Boolean, String?) -> Unit,
    onRemoteCommand: (String, RemoteControlCommand) -> Unit,
    onLongPress: () -> Unit = {},
    onAddToHomeScreen: (String, String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showCameraSettingsDialog by remember { mutableStateOf(false) }
    var showRemoteControlDialog by remember { mutableStateOf(false) }

    // Observe Remote Control Enabled state
    val remoteEnabledMap by service.isRemoteControlEnabled.collectAsState()
    val isRemoteControlEnabled = remoteEnabledMap[cameraAddress] ?: false

    // Observe Focus State
    val focusStates by service.isFocusAcquired.collectAsState()
    val isFocused = focusStates[cameraAddress] ?: false

    // Handle back press to close menu when focusable is false
    BackHandler(enabled = showMenu) {
        showMenu = false
    }

    // Automatically close RemoteControlDialog if camera disconnects
    LaunchedEffect(isConnected) {
        if (!isConnected) {
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
                            androidx.compose.foundation.Image(
                                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                                contentDescription = "App Icon",
                                modifier = Modifier.size(80.dp),
                                colorFilter = if (!isConnected) {
                                    ColorFilter.colorMatrix(
                                        androidx.compose.ui.graphics.ColorMatrix().apply {
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
                                    !isBonded -> context.getString(R.string.camera_state_pairing_not_completed)
                                    isConnected -> context.getString(R.string.camera_state_connected)
                                    isConnecting -> context.getString(R.string.camera_state_connecting)
                                    else -> context.getString(R.string.camera_state_disconnected)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    !isBonded -> MaterialTheme.colorScheme.error
                                    isConnected -> Color(0xFF4CAF50)
                                    isConnecting -> MaterialTheme.colorScheme.primary
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
                                text = cameraAddress,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Hide menu in reorder mode
                    if (!isReorderMode) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Options")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                properties = PopupProperties(focusable = false),
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(context.getString(R.string.menu_settings)) },
                                    onClick = {
                                        showCameraSettingsDialog = true
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(context.getString(R.string.menu_add_remote_to_home)) },
                                    onClick = {
                                        onAddToHomeScreen(cameraAddress, cameraName)
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(context.getString(R.string.menu_remove_camera)) },
                                    onClick = {
                                        onDisconnect()
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Show buttons only when not in reorder mode AND connected
                if (!isReorderMode && isConnected) {
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
                                    onLongClick = { /* Consume long press - do nothing */ }
                                )
                        ) {
                            Button(
                                onClick = { showRemoteControlDialog = true },
                                enabled = isConnected,
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
                                    onClick = onShutter,
                                    onLongClick = { /* Consume long press - do nothing */ }
                                )
                        ) {
                            Button(
                                onClick = onShutter,
                                enabled = isConnected && isRemoteControlEnabled,
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

    if (showCameraSettingsDialog) {
        CameraSettingsDialog(
            cameraAddress = cameraAddress,
            onDismiss = { showCameraSettingsDialog = false },
            onSave = { mode, enabled, duration, autoFocus, customName ->
                onCameraSettings(mode, enabled, duration, autoFocus, customName)
                showCameraSettingsDialog = false
            }
        )
    }

    if (showRemoteControlDialog) {
        RemoteControlDialog(
            cameraAddress = cameraAddress,
            service = service,
            onDismiss = { showRemoteControlDialog = false },
            onRemoteCommand = onRemoteCommand,
            onSaveAutoFocus = { autoFocus ->
                val currentSettings = AppSettingsManager.getCameraSettings(context, cameraAddress)
                onCameraSettings(
                    currentSettings.connectionMode,
                    currentSettings.quickConnectEnabled,
                    currentSettings.quickConnectDurationMinutes,
                    autoFocus,
                    currentSettings.customName
                )
            }
        )
    }
}