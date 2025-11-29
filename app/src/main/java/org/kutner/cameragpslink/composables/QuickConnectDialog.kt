package org.kutner.cameragpslink.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.kutner.cameragpslink.CameraSettingsManager

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickConnectDialog(
    cameraAddress: String,
    onDismiss: () -> Unit,
    onSave: (Boolean, Int) -> Unit
) {
    val context = LocalContext.current
    val currentSettings = remember {
        CameraSettingsManager.getSettings(context, cameraAddress)
    }

    var enabled by remember { mutableStateOf(currentSettings.quickConnectEnabled) }
    var durationMinutes by remember { mutableStateOf(currentSettings.quickConnectDurationMinutes) }
    val durationOptions = listOf(0, 1, 5, 10, 30, 60, 180, 360, 720)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Quick Connect Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // Enable Quick Connect Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enable Quick Connect",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                }

                Text(
                    text = "The app will connect faster to the camera but will also consumes more battery power",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Duration Selection
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Auto Quick Connect Period",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        durationOptions.forEach { minutes ->
                            val isSelected = durationMinutes == minutes
                            Button(
                                onClick = { if (enabled) durationMinutes = minutes },
                                enabled = enabled,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (isSelected)
                                        MaterialTheme.colorScheme.onPrimary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
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
                            0 -> "Always use fast scanning"
                            else -> "Use fast scanning for ${formatDurationMinutes(durationMinutes)} after the camera disconnects, then switch back to regular connect mode to save battery"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                // OK Button (No changes here)
                Button(
                    onClick = {
                        onSave(enabled, durationMinutes)
                    },
                    modifier = Modifier
                        .align(Alignment.End)
                        .height(48.dp)
                ) {
                    Text("OK")
                }
            }
        }
    }
}

private fun formatDurationMinutes(minutes: Int): String {
    return when {
        minutes == 0 -> "Always"
        minutes < 60 -> "$minutes min"
        minutes == 60 -> "1 hour"
        // This handles clean hours like 120 -> "2 hours"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        // This is a fallback for odd values like 90 -> "1h 30m"
        else -> {
            val hours = minutes / 60
            val remainingMinutes = minutes % 60
            "${hours}h ${remainingMinutes}m"
        }
    }
}