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
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kutner.cameragpslink.AppSettingsManager
import org.kutner.cameragpslink.CameraConnection
import org.kutner.cameragpslink.CameraSyncService
import org.kutner.cameragpslink.RemoteControlCommand
import org.kutner.cameragpslink.R
import kotlin.math.abs

@Composable
fun RemoteControlDialog(
    cameraAddress: String,
    service: CameraSyncService,
    connection: CameraConnection,
    onDismiss: () -> Unit,
    onRemoteCommand: (String, RemoteControlCommand) -> Unit,
    onSaveAutoFocus: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val cameraSettings = remember { AppSettingsManager.getCameraSettings(context, cameraAddress) }
    var halfShutterEnabled by remember { mutableStateOf(cameraSettings.enableHalfShutterPress) }

    // Observe connection state dynamically
    val connectedCameras by service.connectedCameras.collectAsState()
    val currentConnection = connectedCameras.find { it.device.address == cameraAddress }
    val isCurrentlyConnected = currentConnection?.isConnected ?: false

    // Get vibrator service
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl

    // Observe Remote Control Enabled state
    val remoteEnabledMap by service.isRemoteControlEnabled.collectAsState()
    val isRemoteControlEnabled = remoteEnabledMap[cameraAddress] ?: false

    // Observe Focus State
    val focusStates by service.isFocusAcquired.collectAsState()
    val isFocused = focusStates[cameraAddress] ?: false

    // Track previous focus state to detect changes
    var previousFocusState by remember { mutableStateOf(false) }

    // Vibrate when focus is acquired
    LaunchedEffect(isFocused) {
        if (isFocused && !previousFocusState) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        previousFocusState = isFocused
    }

    // Observe recording state
    val recordingMap by service.isRecordingVideo.collectAsState()
    val isRecording = recordingMap[cameraAddress] ?: false

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
                        text = context.getString(R.string.dialog_remote_control_title, AppSettingsManager.getCameraName(context, cameraAddress, connection.device.name)),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Conditional Content - use isCurrentlyConnected (dynamic state)
                if (!isCurrentlyConnected) {
                    // Display "Not Connected" Message
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = context.getString(R.string.dialog_remote_control_not_connected),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else if (!isRemoteControlEnabled) {
                    // Display Warning Message
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
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
                            val arrowTint = if (isButtonPressed)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
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
                                                onRemoteCommand(cameraAddress, RemoteControlCommand.HALF_SHUTTER_DOWN)
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
                                                            onRemoteCommand(cameraAddress, RemoteControlCommand.FULL_SHUTTER_DOWN)
                                                        }
                                                    }
                                                }
                                            } finally {
                                                isButtonPressed = false
                                                scope.launch { offsetX.animateTo(0f, animationSpec = tween(200)) }
                                                if (isHalfPressed) {
                                                    onRemoteCommand(cameraAddress, RemoteControlCommand.HALF_SHUTTER_UP)
                                                    isHalfPressed = false
                                                }
                                                if (isFullPressed) {
                                                    scope.launch {
                                                        delay(100)
                                                        onRemoteCommand(cameraAddress, RemoteControlCommand.FULL_SHUTTER_UP)
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
                                                    onRemoteCommand(cameraAddress, RemoteControlCommand.FULL_SHUTTER_DOWN)
                                                    tryAwaitRelease()
                                                } finally {
                                                    isButtonPressed = false
                                                    onRemoteCommand(cameraAddress, RemoteControlCommand.FULL_SHUTTER_UP)
                                                }
                                            }
                                        )
                                    }
                                },
                            shape = CircleShape,
                            color = when {
                                isFocused -> Color.Green
                                isFullPressed -> MaterialTheme.colorScheme.secondary
                                isHalfPressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
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
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.C1_DOWN)
                                    tryAwaitRelease()
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.C1_UP)
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
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.AF_ON_DOWN)
                                    tryAwaitRelease()
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.AF_ON_UP)
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
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.RECORD_DOWN)
                                    tryAwaitRelease()
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.RECORD_UP)
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
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.ZOOM_WIDE_DOWN)
                                    tryAwaitRelease()
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.ZOOM_WIDE_UP)
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
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.ZOOM_TELE_DOWN)
                                    tryAwaitRelease()
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.ZOOM_TELE_UP)
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
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.FOCUS_NEAR_DOWN)
                                    tryAwaitRelease()
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.FOCUS_NEAR_UP)
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
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.FOCUS_FAR_DOWN)
                                    tryAwaitRelease()
                                    onRemoteCommand(cameraAddress, RemoteControlCommand.FOCUS_FAR_UP)
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
                        Switch(
                            checked = halfShutterEnabled,
                            onCheckedChange = {
                                halfShutterEnabled = it
                                onSaveAutoFocus(it)
                            }
                        )
                    }
                }
            }
        }
    }
}