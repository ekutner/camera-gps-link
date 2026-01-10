package org.kutner.cameragpslink.composables

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.kutner.cameragpslink.AppSettingsManager
import org.kutner.cameragpslink.CameraConnection
import org.kutner.cameragpslink.CameraSyncService
import org.kutner.cameragpslink.R
import org.kutner.cameragpslink.RemoteControlCommand

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReorderableCameraList(
    connectedCameras: List<CameraConnection>,
    isReorderMode: Boolean,
    modifier: Modifier = Modifier,
    service: CameraSyncService,
    onTriggerShutter: (String) -> Unit,
    onForgetDevice: (String) -> Unit,
    onCameraSettings: (String, Int, Boolean, Int, Boolean) -> Unit,
    onRemoteCommand: (String, RemoteControlCommand) -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Get the saved camera order from AppSettingsManager
    val savedCameraAddresses = remember(connectedCameras) {
        AppSettingsManager.getSavedCameras(context)
    }

    // Create a map for quick lookup of connections by address
    val connectionMap = remember(connectedCameras) {
        connectedCameras.associateBy { it.device.address }
    }

    // Mutable list for reordering
    var reorderableAddresses by remember(savedCameraAddresses) {
        mutableStateOf(savedCameraAddresses)
    }

    val dragDropState = rememberDragDropState(
        lazyListState = listState,
        onMove = { fromIndex, toIndex ->
            reorderableAddresses = reorderableAddresses.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        },
        onDragEnd = {
            // Save the new order when dragging ends
            AppSettingsManager.reorderCameras(context, reorderableAddresses)
        },
        scope = scope
    )

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        itemsIndexed(
            items = reorderableAddresses,
            key = { _, address -> address }
        ) { index, address ->
            // Look up the connection for this address
            val connection = connectionMap[address]

            // Only display if we have a connection for this address
            if (connection != null) {
                val dragging = index == dragDropState.draggingItemIndex
                val itemModifier = if (dragging) {
                    Modifier
                        .zIndex(1f)
                        .graphicsLayer {
                            translationY = dragDropState.draggingItemOffset
                        }
                } else {
                    Modifier.animateItem()
                }

                // Use a side effect to keep track of current index
                val currentIndex = remember { mutableIntStateOf(index) }
                LaunchedEffect(index) {
                    currentIndex.intValue = index
                }

                Column(modifier = itemModifier.fillMaxWidth()) {
                    ConnectedCameraCard(
                        modifier = Modifier.fillMaxWidth(),
                        cameraName = connection.device.name ?: context.getString(R.string.unknown_camera_name),
                        cameraAddress = connection.device.address,
                        isBonded = connection.isBonded,
                        isConnected = connection.isConnected,
                        isConnecting = connection.isConnecting,
                        service = service,
                        isReorderMode = isReorderMode,
                        isDragging = dragging,
                        elevation = if (dragging) 8.dp else 2.dp,
                        dragModifier = if (isReorderMode) {
                            Modifier.pointerInput(address) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        dragDropState.onDragStartAtIndex(currentIndex.intValue)
                                    },
                                    onDrag = { change, offset ->
                                        change.consume()
                                        dragDropState.onDrag(offset)
                                    },
                                    onDragEnd = { dragDropState.onDragInterrupted() },
                                    onDragCancel = { dragDropState.onDragInterrupted() }
                                )
                            }
                        } else {
                            Modifier
                        },
                        onShutter = { onTriggerShutter(connection.device.address) },
                        onDisconnect = { onForgetDevice(connection.device.address) },
                        onCameraSettings = { mode, qe, dur, af ->
                            onCameraSettings(connection.device.address, mode, qe, dur, af)
                        },
                        onRemoteCommand = onRemoteCommand,
                        onLongPress = onLongPress
                    )
                }
            }
        }
    }

    LaunchedEffect(dragDropState) {
        while (true) {
            val diff = dragDropState.scrollChannel.receive()
            listState.scrollBy(diff)
        }
    }
}

@Composable
fun rememberDragDropState(
    lazyListState: LazyListState,
    onMove: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    scope: CoroutineScope
): DragDropState {
    return remember(lazyListState) {
        DragDropState(
            state = lazyListState,
            onMove = onMove,
            onDragEnd = onDragEnd,
            scope = scope
        )
    }
}

class DragDropState(
    private val state: LazyListState,
    private val onMove: (Int, Int) -> Unit,
    private val onDragEnd: () -> Unit,
    private val scope: CoroutineScope
) {
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    internal val scrollChannel = Channel<Float>()

    private var draggingItemDraggedDelta by mutableFloatStateOf(0f)
    private var draggingItemInitialOffset by mutableIntStateOf(0)

    internal val draggingItemOffset: Float
        get() = draggingItemLayoutInfo?.let { item ->
            draggingItemInitialOffset + draggingItemDraggedDelta - item.offset
        } ?: 0f

    private val draggingItemLayoutInfo: LazyListItemInfo?
        get() = state.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == draggingItemIndex }

    internal fun onDragStart(offset: Offset) {
        state.layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                offset.y.toInt() in item.offset..(item.offset + item.size)
            }
            ?.also {
                draggingItemIndex = it.index
                draggingItemInitialOffset = it.offset
            }
    }

    internal fun onDragStartAtIndex(index: Int) {
        state.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }
            ?.also {
                draggingItemIndex = it.index
                draggingItemInitialOffset = it.offset
                draggingItemDraggedDelta = 0f
            }
    }

    internal fun onDragInterrupted() {
        draggingItemDraggedDelta = 0f
        draggingItemIndex = null
        draggingItemInitialOffset = 0
        onDragEnd()
    }

    internal fun onDrag(offset: Offset) {
        draggingItemDraggedDelta += offset.y

        val draggingItem = draggingItemLayoutInfo ?: return
        val startOffset = draggingItem.offset + draggingItemOffset
        val endOffset = startOffset + draggingItem.size
        val middleOffset = startOffset + (endOffset - startOffset) / 2f

        val targetItem = state.layoutInfo.visibleItemsInfo.find { item ->
            middleOffset.toInt() in item.offset..(item.offset + item.size) &&
                    draggingItem.index != item.index
        }

        if (targetItem != null) {
            val scrollToIndex = if (targetItem.index == state.firstVisibleItemIndex) {
                draggingItem.index
            } else if (draggingItem.index == state.firstVisibleItemIndex) {
                targetItem.index
            } else {
                null
            }

            onMove.invoke(draggingItem.index, targetItem.index)
            draggingItemIndex = targetItem.index

            if (scrollToIndex != null) {
                scope.launch {
                    state.scrollToItem(scrollToIndex, state.firstVisibleItemScrollOffset)
                }
            }
        } else {
            val overscroll = when {
                draggingItemDraggedDelta > 0 ->
                    (endOffset - state.layoutInfo.viewportEndOffset).coerceAtLeast(0f)
                draggingItemDraggedDelta < 0 ->
                    (startOffset - state.layoutInfo.viewportStartOffset).coerceAtMost(0f)
                else -> 0f
            }
            if (overscroll != 0f) {
                scrollChannel.trySend(overscroll)
            }
        }
    }
}