package org.kutner.cameragpslink.composables

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import org.kutner.cameragpslink.Constants

@Composable
fun ConnectedCameraCard(
    cameraName: String,
    cameraAddress: String,
    isConnected: Boolean,
    isConnecting: Boolean,
    onShutter: () -> Unit,
    onDisconnect: () -> Unit,
    onCameraSettings: (Int, Boolean, Int) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showCameraSettingsDialog by remember { mutableStateOf(false) }

    // Handle back press to close menu when focusable is false
    BackHandler(enabled = showMenu) {
        showMenu = false
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "📷",
                            fontSize = 24.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = when {
                                isConnected -> "CONNECTED"
                                isConnecting -> "CONNECTING"
                                else -> "DISCONNECTED"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
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
                            text = { Text("Settings") },
                            onClick = {
                                showCameraSettingsDialog = true
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Remove Camera") },
                            onClick = {
                                onDisconnect()
                                showMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onShutter,
                enabled = isConnected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = Constants.ACTION_SHUTTER,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    if (showCameraSettingsDialog) {
        CameraSettingsDialog(
            cameraAddress = cameraAddress,
            onDismiss = { showCameraSettingsDialog = false },
            onSave = { mode, enabled, duration ->
                onCameraSettings(mode, enabled, duration)
                showCameraSettingsDialog = false
            }
        )
    }
}