package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.CameraEntity
import com.example.engine.PtzCommandState
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceSurface
import kotlinx.coroutines.delay

/**
 * Hardware ONVIF Camera PTZ Dock.
 * Designed to sit OUTSIDE the video viewport (below on portrait, beside on landscape/TV)
 * so the user always sees 100% of the camera image while panning, tilting, and zooming.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PtzControlOverlay(
    camera: CameraEntity,
    ptzStatus: PtzCommandState,
    onStartMove: (pan: Float, tilt: Float, zoom: Float, label: String) -> Unit,
    onStopMove: () -> Unit,
    onGotoHome: () -> Unit,
    onGotoPreset: (String) -> Unit,
    onProbe: () -> Unit,
    onChangeOnvifPort: (Int) -> Unit,
    onClose: (() -> Unit)? = null,
    isVerticalDock: Boolean = false,
    modifier: Modifier = Modifier
) {
    var speed by remember { mutableFloatStateOf(0.6f) }

    val statusForCam = ptzStatus.takeIf { it.cameraId == camera.id }
    val statusLine = when {
        statusForCam?.lastError != null -> statusForCam.lastError
        statusForCam != null && statusForCam.activeAction == "PTZ Ready" -> stringResource(R.string.ptz_ready)
        statusForCam != null -> statusForCam.activeAction
        else -> stringResource(R.string.ptz_default_status, camera.onvifPort)
    }
    val statusColor = when {
        statusForCam?.lastError != null -> NeonAmber
        statusForCam?.isMoving == true -> NeonGreen
        else -> Color.White.copy(alpha = 0.78f)
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .testTag("ptz_control_panel"),
        color = SurveillanceCardBg,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row: Title + Status + Refresh + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.ptz_panel_title),
                        color = NeonCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusLine,
                        color = statusColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onProbe,
                        modifier = Modifier.size(34.dp).testTag("ptz_probe_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.ptz_reconnect), tint = NeonCyan, modifier = Modifier.size(18.dp))
                    }
                    if (onClose != null) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(34.dp).testTag("ptz_close_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ptz_close), tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (isVerticalDock) {
                // Landscape / Wide Side-Dock Layout
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PtzDPad(
                        speed = speed,
                        onStartMove = onStartMove,
                        onStopMove = onStopMove,
                        onGotoHome = onGotoHome
                    )
                    PtzZoomAndPresetsColumn(
                        camera = camera,
                        speed = speed,
                        onSpeedChange = { speed = it },
                        onStartMove = onStartMove,
                        onStopMove = onStopMove,
                        onGotoPreset = onGotoPreset,
                        onChangeOnvifPort = onChangeOnvifPort
                    )
                }
            } else {
                // Portrait Bottom-Dock Layout (compact height so video gets max room above)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PtzDPad(
                        speed = speed,
                        onStartMove = onStartMove,
                        onStopMove = onStopMove,
                        onGotoHome = onGotoHome
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PtzZoomAndPresetsColumn(
                            camera = camera,
                            speed = speed,
                            onSpeedChange = { speed = it },
                            onStartMove = onStartMove,
                            onStopMove = onStopMove,
                            onGotoPreset = onGotoPreset,
                            onChangeOnvifPort = onChangeOnvifPort
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PtzDPad(
    speed: Float,
    onStartMove: (pan: Float, tilt: Float, zoom: Float, label: String) -> Unit,
    onStopMove: () -> Unit,
    onGotoHome: () -> Unit
) {
    val movingUpLabel = stringResource(R.string.ptz_moving_up)
    val movingDownLabel = stringResource(R.string.ptz_moving_down)
    val movingLeftLabel = stringResource(R.string.ptz_moving_left)
    val movingRightLabel = stringResource(R.string.ptz_moving_right)

    Box(
        modifier = Modifier
            .size(132.dp)
            .clip(CircleShape)
            .background(SurveillanceSurface),
        contentAlignment = Alignment.Center
    ) {
        // Tilt Up
        HoldPtzButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = stringResource(R.string.ptz_tilt_up),
            tag = "ptz_up_button",
            onPressStart = { onStartMove(0f, speed, 0f, movingUpLabel) },
            onPressEnd = onStopMove,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
        )

        // Tilt Down
        HoldPtzButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.ptz_tilt_down),
            tag = "ptz_down_button",
            onPressStart = { onStartMove(0f, -speed, 0f, movingDownLabel) },
            onPressEnd = onStopMove,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
        )

        // Pan Left
        HoldPtzButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.ptz_pan_left),
            tag = "ptz_left_button",
            onPressStart = { onStartMove(-speed, 0f, 0f, movingLeftLabel) },
            onPressEnd = onStopMove,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
        )

        // Pan Right
        HoldPtzButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.ptz_pan_right),
            tag = "ptz_right_button",
            onPressStart = { onStartMove(speed, 0f, 0f, movingRightLabel) },
            onPressEnd = onStopMove,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
        )

        // Center Home Position
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(NeonCyan)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onGotoHome() })
                }
                .testTag("ptz_reset_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Home,
                contentDescription = stringResource(R.string.ptz_home),
                tint = Color.Black,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PtzZoomAndPresetsColumn(
    camera: CameraEntity,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onStartMove: (pan: Float, tilt: Float, zoom: Float, label: String) -> Unit,
    onStopMove: () -> Unit,
    onGotoPreset: (String) -> Unit,
    onChangeOnvifPort: (Int) -> Unit
) {
    val zoomingOutLabel = stringResource(R.string.ptz_zooming_out)
    val zoomingInLabel = stringResource(R.string.ptz_zooming_in)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Optical Zoom Out / In (press and hold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HoldZoomPill(
                icon = Icons.Default.Remove,
                label = stringResource(R.string.ptz_zoom_out),
                tag = "ptz_zoom_out_button",
                onPressStart = { onStartMove(0f, 0f, -speed, zoomingOutLabel) },
                onPressEnd = onStopMove,
                modifier = Modifier.weight(1f)
            )
            HoldZoomPill(
                icon = Icons.Default.Add,
                label = stringResource(R.string.ptz_zoom_in),
                tag = "ptz_zoom_in_button",
                onPressStart = { onStartMove(0f, 0f, speed, zoomingInLabel) },
                onPressEnd = onStopMove,
                modifier = Modifier.weight(1f)
            )
        }

        // Speed & Presets
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                0.3f to stringResource(R.string.ptz_speed_slow),
                0.6f to stringResource(R.string.ptz_speed_med),
                1.0f to stringResource(R.string.ptz_speed_fast)
            ).forEach { (valSpeed, label) ->
                val selected = kotlin.math.abs(speed - valSpeed) < 0.05f
                FilterChip(
                    selected = selected,
                    onClick = { onSpeedChange(valSpeed) },
                    label = { Text(label, fontSize = 10.sp, maxLines = 1, softWrap = false) }
                )
            }

            listOf("1", "2", "3").forEach { token ->
                OutlinedButton(
                    onClick = { onGotoPreset(token) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 38.dp, minHeight = 32.dp)
                ) {
                    Text("P$token", fontSize = 10.sp, color = NeonCyan, maxLines = 1, softWrap = false)
                }
            }
        }

        // ONVIF Port quick selector for real camera compatibility
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.ptz_onvif_label), color = Color.Gray, fontSize = 10.sp, maxLines = 1, softWrap = false)
            listOf(80, 8000, 8080, 2020, 8899).forEach { port ->
                val active = camera.onvifPort == port
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) NeonCyan else SurveillanceSurface)
                        .pointerInput(port) {
                            detectTapGestures(onTap = { onChangeOnvifPort(port) })
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "$port",
                        color = if (active) Color.Black else Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun HoldPtzButton(
    icon: ImageVector,
    contentDescription: String,
    tag: String,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentStart by rememberUpdatedState(onPressStart)
    val currentEnd by rememberUpdatedState(onPressEnd)

    Box(
        modifier = modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(SurveillanceCardBg)
            .pointerInput(tag) {
                detectTapGestures(
                    onPress = {
                        currentStart()
                        val released = tryAwaitRelease()
                        if (!released) {
                            currentEnd()
                        } else {
                            // Keep brief motor nudge if quick tap, then stop
                            delay(180)
                            currentEnd()
                        }
                    }
                )
            }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = NeonCyan, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun HoldZoomPill(
    icon: ImageVector,
    label: String,
    tag: String,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentStart by rememberUpdatedState(onPressStart)
    val currentEnd by rememberUpdatedState(onPressEnd)

    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SurveillanceSurface)
            .pointerInput(tag) {
                detectTapGestures(
                    onPress = {
                        currentStart()
                        val released = tryAwaitRelease()
                        if (!released) {
                            currentEnd()
                        } else {
                            delay(180)
                            currentEnd()
                        }
                    }
                )
            }
            .padding(horizontal = 10.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = NeonCyan, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}
