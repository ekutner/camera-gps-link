package org.kutner.cameragpslink.composables

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.kutner.cameragpslink.CameraSettings
import org.kutner.cameragpslink.AppSettingsManager
import org.kutner.cameragpslink.R

@SuppressLint("MissingPermission")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraSettingsDialog(
    cameraAddress: String,
    onDismiss: () -> Unit,
    // Callback now includes Mode, Enabled, Duration, AutoFocus, CustomName
    onSave: (Int, Boolean, Int, Boolean, String) -> Unit
) {
    val context = LocalContext.current
    val currentSettings: CameraSettings = remember {
        AppSettingsManager.getCameraSettings(context, cameraAddress)
    }

    // Get the device name from Bluetooth
    val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager
    val deviceName = try {
        bluetoothManager.adapter.getRemoteDevice(cameraAddress)?.name ?: context.getString(R.string.unknown_camera_name)
    } catch (e: Exception) {
        context.getString(R.string.unknown_camera_name)
    }

    var customName by remember { mutableStateOf(currentSettings.customName ?: "") }
    var connectionMode by remember { mutableStateOf(currentSettings.connectionMode) }
    var quickConnectEnabled by remember { mutableStateOf(currentSettings.quickConnectEnabled) }
    var durationMinutes by remember { mutableStateOf(currentSettings.quickConnectDurationMinutes) }
    var enableHalfShutterPress by remember { mutableStateOf(currentSettings.enableHalfShutterPress) }

    val durationOptions = listOf(0, 1, 5, 10, 30, 60, 120, 180, 240, 360, 720)
    val scrollState = rememberScrollState()

    // Logic: Quick connect is only editable if Mode 1 is selected
    val isQuickConnectEditable = (connectionMode == 1)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = context.getString(R.string.dialog_camera_settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    // --- SECTION 0: Camera Name ---
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = context.getString(R.string.dialog_camera_settings_camera_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = customName,
                            onValueChange = { customName = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(deviceName) },
                            singleLine = true
                        )
                    }

                    androidx.compose.material3.HorizontalDivider()

                    // --- SECTION 1: Connection Mode ---
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = context.getString(R.string.dialog_camera_settings_connection_mode),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (connectionMode == 1),
                                onClick = { connectionMode = 1 }
                            )
                            Text(
                                text = context.getString(R.string.dialog_camera_settings_mode1),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (connectionMode == 2),
                                onClick = { connectionMode = 2 }
                            )
                            Text(
                                text = context.getString(R.string.dialog_camera_settings_mode2),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    androidx.compose.material3.HorizontalDivider()

                    // --- SECTION 2: Quick Connect Settings ---
                    // This entire section is visually disabled if Mode != 1
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = context.getString(R.string.dialog_camera_settings_enable_quick_connect),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isQuickConnectEditable) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                            Switch(
                                checked = quickConnectEnabled,
                                onCheckedChange = { if (isQuickConnectEditable) quickConnectEnabled = it },
                                enabled = isQuickConnectEditable
                            )
                        }

                        Text(
                            text = context.getString(R.string.dialog_camera_settings_quick_connect_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isQuickConnectEditable) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )

                        Text(
                            text = context.getString(R.string.dialog_camera_settings_quick_connect_period),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isQuickConnectEditable && quickConnectEnabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            durationOptions.forEach { minutes ->
                                val isSelected = durationMinutes == minutes
                                // Determine button colors based on enabled state
                                val containerColor = when {
                                    !isQuickConnectEditable || !quickConnectEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                                val contentColor = when {
                                    !isQuickConnectEditable || !quickConnectEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }

                                Button(
                                    onClick = { if (isQuickConnectEditable && quickConnectEnabled) durationMinutes = minutes },
                                    enabled = isQuickConnectEditable && quickConnectEnabled,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = containerColor,
                                        contentColor = contentColor,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    ),
                                    modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                                ) {
                                    Text(
                                        text = formatDurationMinutes(minutes),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        Text(
                            text = when (durationMinutes) {
                                0 -> context.getString(R.string.dialog_camera_settings_quick_connect_period_always)
                                else -> context.getString(R.string.dialog_camera_settings_quick_connect_period_other, formatDurationMinutes(durationMinutes))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isQuickConnectEditable && quickConnectEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }

                    // OK Button
                    Button(
                        onClick = {
                            onSave(connectionMode, quickConnectEnabled, durationMinutes, enableHalfShutterPress, customName)
                        },
                        modifier = Modifier
                            .align(Alignment.End)
                            .height(48.dp)
                    ) {
                        Text(context.getString(R.string.button_ok))
                    }
                }

                VerticalScrollbar(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                    scrollState = scrollState
                )
            }
        }
    }
}

@Composable
fun VerticalScrollbar(
    modifier: Modifier = Modifier,
    scrollState: ScrollState,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    width: androidx.compose.ui.unit.Dp = 4.dp
) {
    val targetAlpha = if (scrollState.maxValue > 0) 1f else 0f
    if (scrollState.maxValue == 0) return

    Canvas(modifier = modifier.width(width).padding(end = 2.dp, top = 24.dp, bottom = 24.dp)) {
        val elementHeight = this.size.height
        val scrollValue = scrollState.value
        val maxValue = scrollState.maxValue
        val totalHeight = elementHeight + maxValue
        val scrollbarHeight = elementHeight * (elementHeight / totalHeight)
        val scrollbarOffsetY = scrollValue * (elementHeight / totalHeight)

        drawRoundRect(
            color = color,
            topLeft = Offset(0f, scrollbarOffsetY),
            size = Size(width.toPx(), scrollbarHeight),
            alpha = targetAlpha,
            cornerRadius = CornerRadius(4.dp.toPx())
        )
    }
}

private fun formatDurationMinutes(minutes: Int): String {
    return when {
        minutes == 0 -> "Always"
        minutes < 60 -> "$minutes min"
        minutes == 60 -> "1 hour"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> {
            val hours = minutes / 60
            val remainingMinutes = minutes % 60
            "${hours}h ${remainingMinutes}m"
        }
    }
}