package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.SwitchVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.model.CameraEntity
import com.example.data.model.StreamTelemetry
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceSurface
import kotlin.math.roundToInt

@Composable
fun FloatingCameraWindow(
    camera: CameraEntity,
    player: ExoPlayer?,
    telemetry: StreamTelemetry,
    onClose: () -> Unit,
    onExpandToFullscreen: () -> Unit,
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Current width in dp (resizable between 180.dp and 380.dp)
    var windowWidthDp by remember { mutableFloatStateOf(240f) }
    // Offset position for dragging
    var offsetX by remember { mutableFloatStateOf(16f) }
    var offsetY by remember { mutableFloatStateOf(80f) }

    val density = LocalDensity.current

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(windowWidthDp.dp)
            .shadow(16.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(SurveillanceCardBg)
            .border(2.dp, NeonCyan, RoundedCornerShape(14.dp))
            .testTag("floating_camera_window"),
        color = SurveillanceCardBg,
        tonalElevation = 12.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Drag Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurveillanceSurface)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount.x).coerceAtLeast(0f)
                            offsetY = (offsetY + dragAmount.y).coerceAtLeast(0f)
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Drag handle icon and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Drag Window",
                        tint = NeonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = camera.name,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // Header Action Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Switch camera
                    IconButton(
                        onClick = onSwitchCamera,
                        modifier = Modifier.size(26.dp).testTag("floating_switch_camera_btn")
                    ) {
                        Icon(
                            Icons.Default.SwitchVideo,
                            contentDescription = "Switch Camera",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // Expand to full screen
                    IconButton(
                        onClick = onExpandToFullscreen,
                        modifier = Modifier.size(26.dp).testTag("floating_fullscreen_btn")
                    ) {
                        Icon(
                            Icons.Default.Fullscreen,
                            contentDescription = "Expand Fullscreen",
                            tint = NeonCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Close
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(26.dp).testTag("floating_close_btn")
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close Floating Window",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Video Stream Content with Aspect Ratio
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                RtspVideoPlayer(
                    camera = camera,
                    player = player,
                    telemetry = telemetry,
                    showControlsOverlay = true,
                    onClick = onExpandToFullscreen,
                    modifier = Modifier.fillMaxSize()
                )

                // Resizable Corner Handle at bottom-right
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(NeonCyan.copy(alpha = 0.85f))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                with(density) {
                                    val deltaDp = dragAmount.x.toDp().value
                                    windowWidthDp = (windowWidthDp + deltaDp).coerceIn(160f, 400f)
                                }
                            }
                        }
                        .testTag("floating_resize_handle"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⤡",
                        color = Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}
