package com.example.ui.components

import androidx.annotation.OptIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.data.model.CameraEntity
import com.example.data.model.StreamTelemetry
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceDarkBg
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun RtspVideoPlayer(
    camera: CameraEntity,
    player: ExoPlayer?,
    telemetry: StreamTelemetry,
    modifier: Modifier = Modifier,
    zoomScale: Float = 1.0f,
    panOffsetX: Float = 0.0f,
    panOffsetY: Float = 0.0f,
    rotationDegrees: Float = camera.rotationDegrees,
    showControlsOverlay: Boolean = true,
    showTechnicalDetails: Boolean = false,
    compactMode: Boolean = false,
    isTvFocused: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    var currentTimeString by remember { mutableStateOf("") }
    var hasFocus by remember { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Live clock ticker only runs when technical details are visible
    LaunchedEffect(showTechnicalDetails) {
        if (showTechnicalDetails) {
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
            while (true) {
                currentTimeString = formatter.format(Date())
                delay(1000)
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val recordingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_alpha"
    )

    val motionBoxScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "motion_box_pulse"
    )

    val focusedOrTv = hasFocus || isTvFocused

    val density = LocalDensity.current
    val normalizedRotation = normalizeRotationDegrees(rotationDegrees)
    val sourceAspect = remember(telemetry.resolution, player?.videoSize) {
        val vs = player?.videoSize
        if (vs != null && vs.width > 0 && vs.height > 0) {
            (vs.width.toFloat() / vs.height.toFloat()).coerceIn(0.4f, 3.0f)
        } else {
            parseResolutionAspectRatio(telemetry.resolution)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(if (compactMode) 10.dp else 12.dp))
            .background(SurveillanceDarkBg)
            .border(
                width = if (hasFocus) 3.dp else if (telemetry.isMotionDetected) 2.dp else 1.dp,
                color = if (hasFocus) NeonCyan else if (telemetry.isMotionDetected) NeonAmber else if (focusedOrTv) Color(0xFF3A527A) else Color(0xFF222E45),
                shape = RoundedCornerShape(if (compactMode) 10.dp else 12.dp)
            )
            .onFocusChanged { hasFocus = it.isFocused }
            .then(
                if (onClick != null) {
                    Modifier
                        .focusable()
                        .clickable { onClick() }
                } else Modifier
            )
            .onSizeChanged { viewportSize = it }
            .testTag("rtsp_player_${camera.id}")
    ) {
        val (coverWPx, coverHPx) = remember(normalizedRotation, viewportSize, sourceAspect) {
            computeCoverFrameSizeForRotation(
                viewportW = viewportSize.width.toFloat(),
                viewportH = viewportSize.height.toFloat(),
                sourceAspect = sourceAspect,
                rotationDegrees = normalizedRotation
            )
        }

        val frameSizeModifier = if (coverWPx > 0f && coverHPx > 0f) {
            with(density) {
                Modifier.requiredSize(width = coverWPx.toDp(), height = coverHPx.toDp())
            }
        } else {
            Modifier.fillMaxSize()
        }

        // Video layer sized & rotated so 16:9 -> 9:16 fills 100% edge-to-edge with zero black sides
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .then(frameSizeModifier)
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    rotationZ = normalizedRotation
                    translationX = panOffsetX
                    translationY = panOffsetY
                }
        ) {
            // Clean dark backdrop (shows subtle surveillance grid only when technical details or motion active)
            SurveillanceCanvas(
                isMotionDetected = telemetry.isMotionDetected,
                showGrid = showTechnicalDetails,
                motionBoxScale = motionBoxScale
            )

            // Video Stream View - Kept continuously mounted when player is present
            if (player != null) {
                AndroidView(
                    factory = { ctx ->
                        (android.view.LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
                            this.player = player
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            useController = false
                        }
                    },
                    update = { playerView ->
                        if (playerView.player != player) {
                            playerView.player = player
                        }
                        if (playerView.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        }
                    },
                    onRelease = { playerView ->
                        try {
                            playerView.player = null
                        } catch (ignored: Exception) {}
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = if (telemetry.hasError || !telemetry.isLive) 0f else 1f
                        }
                )
            }
        }

        // Stream Offline Overlay (simple user-friendly message; tech error only when showTechnicalDetails is true)
        if (telemetry.hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = stringResource(R.string.player_camera_offline),
                        tint = NeonAmber,
                        modifier = Modifier.size(if (compactMode) 22.dp else 30.dp)
                    )
                    Text(
                        text = stringResource(R.string.player_camera_offline),
                        color = NeonAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (compactMode) 11.sp else 13.sp
                    )
                    if (showTechnicalDetails && telemetry.errorMessage != null) {
                        Text(
                            text = telemetry.errorMessage,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (onRetry != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = NeonCyan,
                            modifier = Modifier
                                .clickable { onRetry() }
                                .padding(top = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.common_retry),
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Minimal / Clean Overlay HUD
        if (showControlsOverlay) {
            if (compactMode && !showTechnicalDetails) {
                // Ultra-clean floating corner badge: just camera name + active REC/Motion dot
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (telemetry.isRecording) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(NeonRed.copy(alpha = recordingAlpha))
                        )
                    }
                    Text(
                        text = camera.name,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (normalizedRotation != 0f) {
                        Text(
                            text = formatRotationLabel(normalizedRotation),
                            color = NeonCyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            } else {
                // Top Bar (Clean by default; adds timecode/location/LIVE only if technical details requested)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.72f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (showTechnicalDetails) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Column {
                            Text(
                                text = camera.name,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (showTechnicalDetails) {
                                Text(
                                    text = camera.location,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (telemetry.isRecording) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NeonRed.copy(alpha = 0.25f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(NeonRed.copy(alpha = recordingAlpha))
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.player_rec_badge),
                                    color = NeonRed,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }

                        if (telemetry.isMotionDetected) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NeonAmber.copy(alpha = 0.25f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    Icons.Default.MotionPhotosOn,
                                    contentDescription = stringResource(R.string.player_motion_detected_desc),
                                    tint = NeonAmber,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.player_motion_badge),
                                    color = NeonAmber,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (normalizedRotation != 0f) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NeonCyan.copy(alpha = 0.22f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = formatRotationLabel(normalizedRotation),
                                    color = NeonCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        if (showTechnicalDetails) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NeonGreen.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(NeonGreen)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.player_live_badge),
                                    color = NeonGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Text(
                                text = currentTimeString,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Bottom Telemetry Bar — ONLY shown when user explicitly enables technical details
                if (showTechnicalDetails) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.85f)
                                    )
                                )
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${telemetry.fps} FPS",
                                color = NeonCyan,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "${telemetry.bitrateKbps} kbps",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = telemetry.resolution,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "${telemetry.latencyMs}ms",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Text(
                            text = camera.transport.name,
                            color = NeonCyan.copy(alpha = 0.7f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SurveillanceCanvas(
    isMotionDetected: Boolean,
    showGrid: Boolean,
    motionBoxScale: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF121B2E), Color(0xFF090E1A)),
                center = Offset(w / 2f, h / 2f),
                radius = w.coerceAtLeast(h) / 1.4f
            )
        )

        if (showGrid) {
            val gridStep = 40.dp.toPx()
            var x = gridStep
            while (x < w) {
                drawLine(
                    color = Color(0x1500E5FF),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f
                )
                x += gridStep
            }

            var y = gridStep
            while (y < h) {
                drawLine(
                    color = Color(0x1500E5FF),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
                y += gridStep
            }
        }

        if (isMotionDetected) {
            val boxW = 110.dp.toPx() * motionBoxScale
            val boxH = 80.dp.toPx() * motionBoxScale
            val boxLeft = (w / 2f - boxW / 2f) + (w * 0.1f)
            val boxTop = (h / 2f - boxH / 2f) - (h * 0.05f)

            drawRoundRect(
                color = Color(0xFFFF9500),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 2.5f)
            )
            drawRect(
                color = Color(0x20FF9500),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxW, boxH)
            )
        }
    }
}
