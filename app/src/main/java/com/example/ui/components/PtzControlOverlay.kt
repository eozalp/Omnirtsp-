package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
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
import com.example.engine.OnvifPreset
import com.example.engine.PtzCommandState
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceSurface
import kotlinx.coroutines.delay

/**
 * Hardware ONVIF Camera PTZ Dock.
 * Displays pre-action ONVIF Query status, discovered endpoint/profile/presets/capabilities,
 * and sends Pan/Tilt/Zoom or RelativeMove commands only against verified ONVIF services.
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
    onSelectProfile: (String) -> Unit = {},
    onClose: (() -> Unit)? = null,
    isVerticalDock: Boolean = false,
    modifier: Modifier = Modifier
) {
    var speed by remember { mutableFloatStateOf(0.6f) }

    val statusForCam = ptzStatus.takeIf { it.cameraId == camera.id }
    val isQuerying = statusForCam?.isQuerying == true
    val isQueried = statusForCam?.isQueried == true
    val supportsZoom = statusForCam?.supportsZoom != false

    val statusLine = when {
        statusForCam?.isQuerying == true -> statusForCam.activeAction
        statusForCam?.lastError != null -> statusForCam.lastError
        statusForCam != null && statusForCam.activeAction == "PTZ Ready" -> stringResource(R.string.ptz_ready)
        statusForCam != null -> statusForCam.activeAction
        else -> stringResource(R.string.ptz_default_status, camera.onvifPort)
    }
    val statusColor = when {
        statusForCam?.isQuerying == true -> NeonCyan
        statusForCam?.lastError != null -> NeonAmber
        statusForCam?.isMoving == true -> NeonGreen
        isQueried -> NeonGreen
        else -> Color.White.copy(alpha = 0.78f)
    }

    val presetsToShow = remember(statusForCam?.presets) {
        val discovered = statusForCam?.presets.orEmpty()
        if (discovered.isNotEmpty()) {
            discovered.take(5)
        } else {
            listOf(
                OnvifPreset("1", "P1"),
                OnvifPreset("2", "P2"),
                OnvifPreset("3", "P3")
            )
        }
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header row: Title + Query ONVIF Action Chip + Close
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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (statusForCam?.diagnosticSummary?.isNotBlank() == true && statusForCam.lastError == null) {
                        Text(
                            text = statusForCam.diagnosticSummary,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isQueried) SurveillanceSurface else NeonCyan.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isQuerying) { onProbe() }
                            .testTag("ptz_probe_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isQuerying) {
                                CircularProgressIndicator(
                                    color = NeonCyan,
                                    strokeWidth = 1.8.dp,
                                    modifier = Modifier.size(13.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.ptz_query_button),
                                    tint = NeonCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.ptz_query_button),
                                color = NeonCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
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

            if (!isQueried && !isQuerying && statusForCam?.lastError != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = NeonAmber.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onProbe() }
                ) {
                    Text(
                        text = stringResource(R.string.ptz_query_required_hint),
                        color = NeonAmber,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
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
                        enabled = !isQuerying,
                        onStartMove = onStartMove,
                        onStopMove = onStopMove,
                        onGotoHome = onGotoHome
                    )
                    PtzZoomAndPresetsColumn(
                        camera = camera,
                        status = statusForCam,
                        presets = presetsToShow,
                        supportsZoom = supportsZoom,
                        speed = speed,
                        onSpeedChange = { speed = it },
                        onStartMove = onStartMove,
                        onStopMove = onStopMove,
                        onGotoPreset = onGotoPreset,
                        onChangeOnvifPort = onChangeOnvifPort,
                        onSelectProfile = onSelectProfile
                    )
                }
            } else {
                // Portrait Bottom-Dock Layout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PtzDPad(
                        speed = speed,
                        enabled = !isQuerying,
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
                            status = statusForCam,
                            presets = presetsToShow,
                            supportsZoom = supportsZoom,
                            speed = speed,
                            onSpeedChange = { speed = it },
                            onStartMove = onStartMove,
                            onStopMove = onStopMove,
                            onGotoPreset = onGotoPreset,
                            onChangeOnvifPort = onChangeOnvifPort,
                            onSelectProfile = onSelectProfile
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
    enabled: Boolean,
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
            .background(SurveillanceSurface)
            .alpha(if (enabled) 1f else 0.55f),
        contentAlignment = Alignment.Center
    ) {
        // Tilt Up
        HoldPtzButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = stringResource(R.string.ptz_tilt_up),
            tag = "ptz_up_button",
            enabled = enabled,
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
            enabled = enabled,
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
            enabled = enabled,
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
            enabled = enabled,
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
                .pointerInput(enabled) {
                    detectTapGestures(onTap = { if (enabled) onGotoHome() })
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

@Composable
private fun PtzZoomAndPresetsColumn(
    camera: CameraEntity,
    status: PtzCommandState?,
    presets: List<OnvifPreset>,
    supportsZoom: Boolean,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onStartMove: (pan: Float, tilt: Float, zoom: Float, label: String) -> Unit,
    onStopMove: () -> Unit,
    onGotoPreset: (String) -> Unit,
    onChangeOnvifPort: (Int) -> Unit,
    onSelectProfile: (String) -> Unit
) {
    val zoomingOutLabel = stringResource(R.string.ptz_zooming_out)
    val zoomingInLabel = stringResource(R.string.ptz_zooming_in)
    val isQuerying = status?.isQuerying == true

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (supportsZoom) {
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
                    enabled = !isQuerying,
                    onPressStart = { onStartMove(0f, 0f, -speed, zoomingOutLabel) },
                    onPressEnd = onStopMove,
                    modifier = Modifier.weight(1f)
                )
                HoldZoomPill(
                    icon = Icons.Default.Add,
                    label = stringResource(R.string.ptz_zoom_in),
                    tag = "ptz_zoom_in_button",
                    enabled = !isQuerying,
                    onPressStart = { onStartMove(0f, 0f, speed, zoomingInLabel) },
                    onPressEnd = onStopMove,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Text(
                text = stringResource(R.string.ptz_pan_tilt_only),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp
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

            presets.forEach { preset ->
                OutlinedButton(
                    onClick = { onGotoPreset(preset.token) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 38.dp, minHeight = 32.dp)
                ) {
                    Text(
                        preset.name.take(8),
                        fontSize = 10.sp,
                        color = NeonCyan,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Discovered ONVIF Profiles selector (shown when camera exposes multiple profiles)
        val profiles = status?.availableProfiles.orEmpty()
        if (profiles.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.ptz_profile_label),
                    color = Color.Gray,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false
                )
                val activeToken = status?.profileToken?.ifBlank { camera.ptzProfileToken } ?: camera.ptzProfileToken
                profiles.forEach { token ->
                    val selected = token == activeToken
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (selected) NeonCyan else SurveillanceSurface)
                            .clickable { onSelectProfile(token) }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = token,
                            color = if (selected) Color.Black else Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
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
            val displayPort = status?.resolvedOnvifPort ?: camera.onvifPort
            listOf(80, 2020, 8000, 8080, 8899).forEach { port ->
                val active = displayPort == port
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
    enabled: Boolean,
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
            .pointerInput(tag, enabled) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
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
    enabled: Boolean,
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
            .alpha(if (enabled) 1f else 0.5f)
            .pointerInput(tag, enabled) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
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
