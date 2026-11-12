package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.exoplayer.ExoPlayer
import com.example.R
import com.example.data.model.CameraEntity
import com.example.data.model.StreamTelemetry
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.viewmodel.CornerPosition
import com.example.ui.viewmodel.FloatingWindowState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun FloatingCameraWindow(
    camera: CameraEntity,
    windowState: FloatingWindowState,
    player: ExoPlayer?,
    telemetry: StreamTelemetry,
    showTechnicalDetails: Boolean,
    containerWidthPx: Float,
    containerHeightPx: Float,
    safeTopPx: Float,
    safeBottomPx: Float,
    onUpdatePosition: (Float, Float) -> Unit,
    onSnapCorner: (CornerPosition) -> Unit,
    onResize: (widthDp: Float, offsetX: Float?, offsetY: Float?) -> Unit,
    onRotate: (Float) -> Unit,
    onBringToFront: () -> Unit,
    onExpandToFullscreen: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var controlsVisible by remember { mutableStateOf(true) }
    var isFocused by remember { mutableStateOf(false) }
    var isRotateMode by remember { mutableStateOf(false) }

    LaunchedEffect(controlsVisible, isFocused, isRotateMode, windowState.widthDp, windowState.cornerSnap, windowState.rotationDegrees) {
        if (controlsVisible && !isFocused && !isRotateMode) {
            delay(4000)
            controlsVisible = false
        }
    }

    val sourceAspect = parseResolutionAspectRatio(telemetry.resolution)
    val effAspect = computeEffectiveContainerAspectRatio(sourceAspect, windowState.rotationDegrees)
    val maxAllowedWidthDp = with(density) { (containerWidthPx * 0.85f).toDp().value }.coerceAtLeast(140f)
    val maxAllowedHeightDp = with(density) { ((containerHeightPx - safeTopPx - safeBottomPx) * 0.85f).toDp().value }.coerceAtLeast(140f)
    val (windowWidthDp, windowHeightDp) = resolveFloatingWindowSizeDp(
        baseWidthDp = windowState.widthDp,
        effectiveAspect = effAspect,
        maxWidthDp = maxAllowedWidthDp.coerceAtMost(540f),
        maxHeightDp = maxAllowedHeightDp.coerceAtMost(540f)
    )

    val windowWidthPx = with(density) { windowWidthDp.dp.toPx() }
    val windowHeightPx = with(density) { windowHeightDp.dp.toPx() }
    val marginPx = with(density) { 12.dp.toPx() }

    val minX = marginPx
    val maxX = (containerWidthPx - windowWidthPx - marginPx).coerceAtLeast(minX)
    val minY = safeTopPx + marginPx
    val maxY = (containerHeightPx - safeBottomPx - windowHeightPx - marginPx).coerceAtLeast(minY)

    val resolvedX: Float
    val resolvedY: Float
    when (windowState.cornerSnap) {
        CornerPosition.TOP_LEFT -> {
            resolvedX = minX
            resolvedY = minY
        }
        CornerPosition.TOP_RIGHT -> {
            resolvedX = maxX
            resolvedY = minY
        }
        CornerPosition.BOTTOM_LEFT -> {
            resolvedX = minX
            resolvedY = maxY
        }
        CornerPosition.BOTTOM_RIGHT -> {
            resolvedX = maxX
            resolvedY = maxY
        }
        null -> {
            resolvedX = (windowState.offsetX ?: minX).coerceIn(minX, maxX)
            resolvedY = (windowState.offsetY ?: minY).coerceIn(minY, maxY)
        }
    }

    Surface(
        modifier = modifier
            .zIndex(windowState.zIndex)
            .offset { IntOffset(resolvedX.roundToInt(), resolvedY.roundToInt()) }
            .width(windowWidthDp.dp)
            .height(windowHeightDp.dp)
            .shadow(14.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isFocused || controlsVisible) 2.dp else 1.5.dp,
                color = if (isFocused || controlsVisible) NeonCyan else Color.White.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged {
                isFocused = it.hasFocus
                if (it.hasFocus) {
                    controlsVisible = true
                    onBringToFront()
                }
            }
            .resizableCameraWindow(
                key = camera.id,
                aspectRatio = effAspect,
                onInteractionStart = {
                    onBringToFront()
                    controlsVisible = true
                },
                onDragMove = { dx, dy ->
                    val newX = (resolvedX + dx).coerceIn(minX, maxX)
                    val newY = (resolvedY + dy).coerceIn(minY, maxY)
                    onUpdatePosition(newX, newY)
                },
                onCornerResize = { _, widthDeltaDp, shiftX, shiftY ->
                    val targetActualW = (windowWidthDp + widthDeltaDp).coerceAtLeast(96f)
                    val newBaseWidthDp = baseWidthFromActualWidthDp(targetActualW, effAspect)
                    val (newWindowWidthDp, newWindowHeightDp) = resolveFloatingWindowSizeDp(
                        baseWidthDp = newBaseWidthDp,
                        effectiveAspect = effAspect,
                        maxWidthDp = maxAllowedWidthDp.coerceAtMost(540f),
                        maxHeightDp = maxAllowedHeightDp.coerceAtMost(540f)
                    )
                    val newWindowWidthPx = with(density) { newWindowWidthDp.dp.toPx() }
                    val newWindowHeightPx = with(density) { newWindowHeightDp.dp.toPx() }
                    val newMaxX = (containerWidthPx - newWindowWidthPx - marginPx).coerceAtLeast(minX)
                    val newMaxY = (containerHeightPx - safeBottomPx - newWindowHeightPx - marginPx).coerceAtLeast(minY)
                    val newX = (resolvedX + shiftX).coerceIn(minX, newMaxX)
                    val newY = (resolvedY + shiftY).coerceIn(minY, newMaxY)
                    onResize(newBaseWidthDp, newX, newY)
                }
            )
            .testTag("floating_camera_window_${camera.id}"),
        color = SurveillanceCardBg,
        tonalElevation = 10.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Live Video Feed (compact, distraction-free)
            RtspVideoPlayer(
                camera = camera,
                player = player,
                telemetry = telemetry,
                rotationDegrees = windowState.rotationDegrees,
                showControlsOverlay = true,
                showTechnicalDetails = showTechnicalDetails,
                compactMode = true,
                onRetry = onRetry,
                onClick = {
                    onBringToFront()
                    controlsVisible = !controlsVisible
                },
                modifier = Modifier.fillMaxSize()
            )

            // Visible 4-corner resize brackets
            CornerResizeGripsOverlay(isHighlighted = controlsVisible || isFocused)

            // Auto-hiding Controls Overlay
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                ) {
                    // Top-Right Action Buttons: Expand & Close
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onExpandToFullscreen,
                            modifier = Modifier
                                .size(28.dp)
                                .focusable()
                                .testTag("floating_fullscreen_btn_${camera.id}")
                        ) {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = stringResource(R.string.floating_fullscreen),
                                tint = NeonCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(28.dp)
                                .focusable()
                                .testTag("floating_close_btn_${camera.id}")
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.floating_close_window),
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    // Bottom Quick Corner Snap, Size & Rotation Controls
                    if (isRotateMode) {
                        CompactFloatingRotationBar(
                            rotationDegrees = windowState.rotationDegrees,
                            onRotationChange = onRotate,
                            onDone = { isRotateMode = false },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                                .testTag("floating_rotation_bar_${camera.id}")
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 6.dp, bottom = 6.dp, end = 20.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.75f))
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CornerSnapChip("↖", windowState.cornerSnap == CornerPosition.TOP_LEFT) {
                                onSnapCorner(CornerPosition.TOP_LEFT)
                            }
                            CornerSnapChip("↗", windowState.cornerSnap == CornerPosition.TOP_RIGHT) {
                                onSnapCorner(CornerPosition.TOP_RIGHT)
                            }
                            CornerSnapChip("↙", windowState.cornerSnap == CornerPosition.BOTTOM_LEFT) {
                                onSnapCorner(CornerPosition.BOTTOM_LEFT)
                            }
                            CornerSnapChip("↘", windowState.cornerSnap == CornerPosition.BOTTOM_RIGHT) {
                                onSnapCorner(CornerPosition.BOTTOM_RIGHT)
                            }

                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .focusable()
                                    .clickable { onResize(windowState.widthDp - 40f, null, null) }
                                    .testTag("floating_shrink_btn_${camera.id}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Remove,
                                    contentDescription = stringResource(R.string.floating_smaller),
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .focusable()
                                    .clickable { onResize(windowState.widthDp + 40f, null, null) }
                                    .testTag("floating_grow_btn_${camera.id}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.floating_larger),
                                    tint = NeonCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (normalizeRotationDegrees(windowState.rotationDegrees) != 0f) {
                                            NeonCyan.copy(alpha = 0.28f)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .focusable()
                                    .clickable { isRotateMode = true }
                                    .testTag("floating_rotate_btn_${camera.id}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.RotateRight,
                                    contentDescription = stringResource(R.string.live_rotate_video),
                                    tint = NeonCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CornerSnapChip(
    symbol: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) NeonCyan else Color.Transparent)
            .focusable()
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = if (isSelected) Color.Black else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}
