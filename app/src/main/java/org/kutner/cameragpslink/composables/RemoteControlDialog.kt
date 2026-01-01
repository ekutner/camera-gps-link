package org.kutner.cameragpslink.composables

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Square
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kutner.cameragpslink.AppSettingsManager
import org.kutner.cameragpslink.CameraSyncService
import org.kutner.cameragpslink.R
import org.kutner.cameragpslink.RemoteCommand
import kotlin.math.abs

@Composable
fun RemoteControlDialog(
    cameraAddress: String,
    service: CameraSyncService, // Pass service to access state
    onDismiss: () -> Unit,
    onRemoteCommand: (String, RemoteCommand) -> Unit,
    onReleaseCommand: (String, RemoteCommand) -> Unit,
    onSaveAutoFocus: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val cameraSettings = remember { AppSettingsManager.getCameraSettings(context, cameraAddress) }
    var halfShutterEnabled by remember { mutableStateOf(cameraSettings.enableHalfShutterPress) }

    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl

    // Observe Remote Control Enabled state
    val remoteEnabledMap by service.isRemoteControlEnabled.collectAsState()
    val isRemoteControlEnabled = remoteEnabledMap[cameraAddress] ?: false

    // Observe Focus State
    val focusStates by service.isFocusAcquired.collectAsState()
    val isFocused = focusStates[cameraAddress] ?: false

    // Observe recording state
    val recordingMap by service.isRecordingVideo.collectAsState()
    val isRecording = recordingMap[cameraAddress] ?: false

    LaunchedEffect(halfShutterEnabled) {
        onSaveAutoFocus(halfShutterEnabled)
    }

    // Shutter states
    var isButtonPressed by remember { mutableStateOf(false) }
    var isHalfPressed by remember { mutableStateOf(false) }
    var isFullPressed by remember { mutableStateOf(false) }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Press indication states for other buttons
    var isC1Pressed by remember { mutableStateOf(false) }
    var isAFPressed by remember { mutableStateOf(false) }
    var isRecordPressed by remember { mutableStateOf(false) }
    var isWidePressed by remember { mutableStateOf(false) }
    var isTelePressed by remember { mutableStateOf(false) }
    var isNearPressed by remember { mutableStateOf(false) }
    var isFarPressed by remember { mutableStateOf(false) }

    @Composable
    fun getButtonColor(isPressed: Boolean, baseColor: Color = MaterialTheme.colorScheme.primary): Color {
        return if (isPressed) baseColor.copy(alpha = 0.7f) else baseColor
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(R.string.dialog_remote_control_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Conditional Content
                if (!isRemoteControlEnabled) {
                    // Display Warning Message
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp), // Match approximate height of controls
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = context.getString(R.string.dialog_remote_control_warning),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(16.dp)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Refresh link using Text with LinkAnnotation
                            val annotatedString = buildAnnotatedString {
                                append(context.getString(R.string.dialog_remote_control_refresh))
                                addStyle(
                                    style = SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline
                                    ),
                                    start = 0,
                                    end = length
                                )
                                addLink(
                                    clickable = androidx.compose.ui.text.LinkAnnotation.Clickable(
                                        tag = "REFRESH",
                                        linkInteractionListener = {
                                            service.probeRemoteControl(cameraAddress)
                                        }
                                    ),
                                    start = 0,
                                    end = length
                                )
                            }

                            Text(
                                text = annotatedString,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                } else {
                    // Shutter button
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        if (halfShutterEnabled) {
                            val arrowTint = if (isButtonPressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(32.dp), tint = arrowTint)
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(32.dp), tint = arrowTint)
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .size(120.dp)
                                .offset(x = offsetX.value.dp)
                                .pointerInput(halfShutterEnabled) {
                                    if (halfShutterEnabled) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            isButtonPressed = true
                                            if (!isHalfPressed) {
                                                isHalfPressed = true
                                                onRemoteCommand(cameraAddress, RemoteCommand.HALF_SHUTTER_BUTTON)
                                            }
                                            var currentDragOffset = 0f
                                            try {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val change = event.changes.firstOrNull { it.id == down.id }
                                                    if (change == null || !change.pressed) break
                                                    val dragAmount = change.positionChange().x
                                                    if (dragAmount != 0f) {
                                                        change.consume()
                                                        val adjustedDrag = if (isRtl) -dragAmount else dragAmount
                                                        currentDragOffset = (currentDragOffset + adjustedDrag).coerceIn(-50f, 50f)
                                                        scope.launch { offsetX.snapTo(currentDragOffset) }
                                                        if (abs(currentDragOffset) > 30f && !isFullPressed) {
                                                            isFullPressed = true
                                                            onRemoteCommand(cameraAddress, RemoteCommand.FULL_SHUTTER_BUTTON)
                                                        }
                                                    }
                                                }
                                            } finally {
                                                isButtonPressed = false
                                                scope.launch { offsetX.animateTo(0f, animationSpec = tween(200)) }
                                                if (isHalfPressed) {
                                                    onReleaseCommand(cameraAddress, RemoteCommand.HALF_SHUTTER_BUTTON)
                                                    isHalfPressed = false
                                                }
                                                if (isFullPressed) {
                                                    scope.launch {
                                                        delay(100)
                                                        onReleaseCommand(cameraAddress, RemoteCommand.FULL_SHUTTER_BUTTON)
                                                        isFullPressed = false
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        detectTapGestures(
                                            onPress = {
                                                isButtonPressed = true
                                                try {
                                                    onRemoteCommand(cameraAddress, RemoteCommand.FULL_SHUTTER_BUTTON)
                                                    tryAwaitRelease()
                                                } finally {
                                                    isButtonPressed = false
                                                    onReleaseCommand(cameraAddress, RemoteCommand.FULL_SHUTTER_BUTTON)
                                                }
                                            }
                                        )
                                    }
                                },
                            shape = CircleShape,
                            color = when {
                                isFocused -> Color.Green // TURN GREEN WHEN FOCUSED
                                isFullPressed -> MaterialTheme.colorScheme.secondary
                                isHalfPressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
//                            isButtonPressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                else -> MaterialTheme.colorScheme.primary
                            }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = "⚪", fontSize = 48.sp, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }


                    Spacer(modifier = Modifier.height(24.dp))

                    // C1, AF-ON, Record Row
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        // C1
                        Surface(
                            modifier = Modifier.size(72.dp).pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    isC1Pressed = true
                                    onRemoteCommand(cameraAddress, RemoteCommand.C1_BUTTON)
                                    tryAwaitRelease()
                                    onReleaseCommand(cameraAddress, RemoteCommand.C1_BUTTON)
                                    isC1Pressed = false
                                })
                            },
                            shape = CircleShape, color = getButtonColor(isC1Pressed)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    context.getString(R.string.dialog_remote_control_c1),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        // AF-ON
                        Surface(
                            modifier = Modifier.size(72.dp).pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    isAFPressed = true
                                    onRemoteCommand(cameraAddress, RemoteCommand.AF_ON_BUTTON)
                                    tryAwaitRelease()
                                    onReleaseCommand(cameraAddress, RemoteCommand.AF_ON_BUTTON)
                                    isAFPressed = false
                                })
                            },
                            shape = CircleShape, color = getButtonColor(isAFPressed)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    context.getString(R.string.dialog_remote_control_af_on),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }

                        // Record Button
                        Surface(
                            modifier = Modifier.size(72.dp).pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    isRecordPressed = true
                                    onRemoteCommand(cameraAddress, RemoteCommand.RECORD_BUTTON)
                                    tryAwaitRelease()
                                    onReleaseCommand(cameraAddress, RemoteCommand.RECORD_BUTTON)
                                    isRecordPressed = false
                                })
                            },
                            shape = CircleShape,
                            color = getButtonColor(isRecordPressed, Color(0xFFE53935))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isRecording) Icons.Default.Square else Icons.Default.Circle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Zoom Row
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.weight(1f).height(48.dp).pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    isWidePressed = true
                                    onRemoteCommand(cameraAddress, RemoteCommand.ZOOM_WIDE_BUTTON)
                                    tryAwaitRelease()
                                    onReleaseCommand(cameraAddress, RemoteCommand.ZOOM_WIDE_BUTTON)
                                    isWidePressed = false
                                })
                            },
                            shape = RoundedCornerShape(8.dp), color = getButtonColor(isWidePressed)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    context.getString(R.string.dialog_remote_control_wide),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        Text(
                            context.getString(R.string.dialog_remote_control_zoom),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Surface(
                            modifier = Modifier.weight(1f).height(48.dp).pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    isTelePressed = true
                                    onRemoteCommand(cameraAddress, RemoteCommand.ZOOM_TELE_BUTTON)
                                    tryAwaitRelease()
                                    onReleaseCommand(cameraAddress, RemoteCommand.ZOOM_TELE_BUTTON)
                                    isTelePressed = false
                                })
                            },
                            shape = RoundedCornerShape(8.dp), color = getButtonColor(isTelePressed)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    context.getString(R.string.dialog_remote_control_tele),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Focus Row
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.weight(1f).height(48.dp).pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    isNearPressed = true
                                    onRemoteCommand(cameraAddress, RemoteCommand.FOCUS_NEAR_BUTTON)
                                    tryAwaitRelease()
                                    onReleaseCommand(cameraAddress, RemoteCommand.FOCUS_NEAR_BUTTON)
                                    isNearPressed = false
                                })
                            },
                            shape = RoundedCornerShape(8.dp), color = getButtonColor(isNearPressed)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    context.getString(R.string.dialog_remote_control_near),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        Text(
                            context.getString(R.string.dialog_remote_control_focus),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Surface(
                            modifier = Modifier.weight(1f).height(48.dp).pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    isFarPressed = true
                                    onRemoteCommand(cameraAddress, RemoteCommand.FOCUS_FAR_BUTTON)
                                    tryAwaitRelease()
                                    onReleaseCommand(cameraAddress, RemoteCommand.FOCUS_FAR_BUTTON)
                                    isFarPressed = false
                                })
                            },
                            shape = RoundedCornerShape(8.dp), color = getButtonColor(isFarPressed)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    context.getString(R.string.dialog_remote_control_far),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            context.getString(R.string.dialog_remote_control_enable_half_shutter),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = halfShutterEnabled, onCheckedChange = { halfShutterEnabled = it })
                    }
                }
            }
        }
    }
}