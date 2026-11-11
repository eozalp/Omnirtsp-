package com.example.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
    showControlsOverlay: Boolean = true,
    isTvFocused: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    var currentTimeString by remember { mutableStateOf("") }

    // Live clock ticker
    LaunchedEffect(Unit) {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        while (true) {
            currentTimeString = formatter.format(Date())
            delay(1000)
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

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurveillanceDarkBg)
            .border(
                width = if (isTvFocused) 3.dp else if (telemetry.isMotionDetected) 2.dp else 1.dp,
                color = if (isTvFocused) NeonCyan else if (telemetry.isMotionDetected) NeonAmber else Color(0xFF26334D),
                shape = RoundedCornerShape(12.dp)
            )
            .focusable()
            .testTag("rtsp_player_${camera.id}")
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        // Video layer with PTZ transform
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffsetX
                    translationY = panOffsetY
                }
        ) {
            // Background Surveillance Canvas (always mounted for smooth transitions and offline status)
            SurveillanceCanvas(
                camera = camera,
                isMotionDetected = telemetry.isMotionDetected,
                motionBoxScale = motionBoxScale
            )

            // Video Stream View - Kept continuously mounted when player is present
            if (player != null) {
                AndroidView(
                    factory = { ctx ->
                        (android.view.LayoutInflater.from(ctx).inflate(com.example.R.layout.player_view_texture, null) as PlayerView).apply {
                            this.player = player
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            useController = false
                        }
                    },
                    update = { playerView ->
                        if (playerView.player != player) {
                            playerView.player = player
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

        // Stream Error / Offline Overlay
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
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Stream Offline",
                        tint = NeonAmber,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "FEED OFFLINE",
                        color = NeonAmber,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp
                    )
                    Text(
                        text = telemetry.errorMessage ?: "Stream Unreachable",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (onRetry != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = NeonCyan,
                            modifier = Modifier
                                .clickable { onRetry() }
                                .padding(top = 4.dp)
                        ) {
                            Text(
                                text = "RECONNECT",
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Camera Info HUD Overlay
        if (showControlsOverlay) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.85f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Camera Name and Location
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = "Camera",
                        tint = NeonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = camera.name,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = camera.location,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp
                        )
                    }
                }

                // Live Clock & Status Badges
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Recording Badge
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
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(NeonRed.copy(alpha = recordingAlpha))
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "REC",
                                color = NeonRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    // Motion Badge
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
                                contentDescription = "Motion Alert",
                                tint = NeonAmber,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "MOTION",
                                color = NeonAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Live Badge
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
                            text = "LIVE",
                            color = NeonGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Live Timecode
                    Text(
                        text = currentTimeString,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Bottom Telemetry Bar
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
                // Telemetry Stats
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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

@Composable
fun SurveillanceCanvas(
    camera: CameraEntity,
    isMotionDetected: Boolean,
    motionBoxScale: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Dark CCTV backdrop with subtle gradient
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF131D31), Color(0xFF090E1A)),
                center = Offset(w / 2f, h / 2f),
                radius = w.coerceAtLeast(h) / 1.4f
            )
        )

        // Surveillance Grid & Crosshairs
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

        // Center optical target ring
        drawCircle(
            color = Color(0x3000E5FF),
            radius = 32.dp.toPx(),
            center = Offset(w / 2f, h / 2f),
            style = Stroke(width = 1.5f)
        )
        drawLine(
            color = Color(0x4000E5FF),
            start = Offset(w / 2f - 40f, h / 2f),
            end = Offset(w / 2f + 40f, h / 2f),
            strokeWidth = 1.5f
        )
        drawLine(
            color = Color(0x4000E5FF),
            start = Offset(w / 2f, h / 2f - 40f),
            end = Offset(w / 2f, h / 2f + 40f),
            strokeWidth = 1.5f
        )

        // 4 Corner brackets (Surveillance Frame)
        val bracketLen = 24.dp.toPx()
        val m = 16.dp.toPx()
        val bracketColor = Color(0x8000E5FF)
        val stroke = 2f

        // Top-Left
        drawLine(bracketColor, Offset(m, m), Offset(m + bracketLen, m), stroke)
        drawLine(bracketColor, Offset(m, m), Offset(m, m + bracketLen), stroke)

        // Top-Right
        drawLine(bracketColor, Offset(w - m, m), Offset(w - m - bracketLen, m), stroke)
        drawLine(bracketColor, Offset(w - m, m), Offset(w - m, m + bracketLen), stroke)

        // Bottom-Left
        drawLine(bracketColor, Offset(m, h - m), Offset(m + bracketLen, h - m), stroke)
        drawLine(bracketColor, Offset(m, h - m), Offset(m, h - m - bracketLen), stroke)

        // Bottom-Right
        drawLine(bracketColor, Offset(w - m, h - m), Offset(w - m - bracketLen, h - m), stroke)
        drawLine(bracketColor, Offset(w - m, h - m), Offset(w - m, h - m - bracketLen), stroke)

        // If motion detected, draw target tracking bounding box
        if (isMotionDetected) {
            val boxW = 120.dp.toPx() * motionBoxScale
            val boxH = 90.dp.toPx() * motionBoxScale
            val boxLeft = (w / 2f - boxW / 2f) + (w * 0.12f)
            val boxTop = (h / 2f - boxH / 2f) - (h * 0.08f)

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
