package com.example.ui.screens

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CameraEntity
import com.example.ui.components.PtzControlOverlay
import com.example.ui.components.RtspVideoPlayer
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.viewmodel.CameraViewModel

@Composable
fun FullScreenPlayerScreen(
    camera: CameraEntity,
    viewModel: CameraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val activity = context as? Activity

    val player = remember(camera.id) { viewModel.streamManager.getOrCreatePlayer(camera) }
    val telemetry by viewModel.streamManager.getTelemetryFlow(camera.id).collectAsState()
    val recordingCameras by viewModel.recordingSchedulerEngine.currentlyRecordingCameras.collectAsState()
    val isRecording = recordingCameras.contains(camera.id)

    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffsetX by remember { mutableFloatStateOf(0.0f) }
    var panOffsetY by remember { mutableFloatStateOf(0.0f) }

    var isPtzVisible by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var showSensitivitySlider by remember { mutableStateOf(false) }
    var localSensitivity by remember { mutableFloatStateOf(camera.motionSensitivity.toFloat()) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("fullscreen_player_screen")
    ) {
        // Video Stream
        RtspVideoPlayer(
            camera = camera,
            player = player,
            telemetry = telemetry,
            zoomScale = zoomScale,
            panOffsetX = panOffsetX,
            panOffsetY = panOffsetY,
            showControlsOverlay = true,
            modifier = Modifier.fillMaxSize()
        )

        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 12.dp, end = 12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = onBack,
                modifier = Modifier.size(42.dp).testTag("fullscreen_back_button"),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = SurveillanceCardBg.copy(alpha = 0.85f),
                    contentColor = NeonCyan
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }

            // Quick Floating Window Popout & System PiP
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // In-App Floating Resizable Window Button
                FilledIconButton(
                    onClick = {
                        viewModel.setFloatingCamera(camera)
                        onBack()
                    },
                    modifier = Modifier.size(42.dp).testTag("pop_floating_window_btn"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SurveillanceCardBg.copy(alpha = 0.85f),
                        contentColor = NeonCyan
                    )
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = "Open Floating Resizable Window")
                }

                // System PiP
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    FilledIconButton(
                        onClick = {
                            activity?.enterPictureInPictureMode()
                        },
                        modifier = Modifier.size(42.dp).testTag("system_pip_btn"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = SurveillanceCardBg.copy(alpha = 0.85f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "System Picture-in-Picture")
                    }
                }
            }
        }

        // PTZ Overlay
        AnimatedVisibility(
            visible = isPtzVisible,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        ) {
            PtzControlOverlay(
                zoomScale = zoomScale,
                panOffsetX = panOffsetX,
                panOffsetY = panOffsetY,
                onZoomChange = { zoomScale = it },
                onPanChange = { x, y ->
                    panOffsetX = x
                    panOffsetY = y
                },
                onReset = {
                    zoomScale = 1.0f
                    panOffsetX = 0.0f
                    panOffsetY = 0.0f
                }
            )
        }

        // Sensitivity Slider Popup
        AnimatedVisibility(
            visible = showSensitivitySlider,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 84.dp, start = 24.dp, end = 24.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)),
                color = SurveillanceCardBg.copy(alpha = 0.95f),
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Motion Sensitivity", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("${localSensitivity.toInt()}%", color = NeonCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = localSensitivity,
                        onValueChange = {
                            localSensitivity = it
                            viewModel.addOrUpdateCamera(camera.copy(motionSensitivity = it.toInt()))
                        },
                        valueRange = 10f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonCyan,
                            activeTrackColor = NeonCyan
                        )
                    )
                }
            }
        }

        // Bottom Controls Bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .clip(RoundedCornerShape(20.dp))
                .testTag("fullscreen_bottom_bar"),
            color = SurveillanceCardBg.copy(alpha = 0.9f),
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Record Button
                FilledIconButton(
                    onClick = {
                        if (isRecording) {
                            viewModel.stopRecording(camera.id)
                        } else {
                            viewModel.startManualRecording(camera)
                        }
                    },
                    modifier = Modifier.size(46.dp).testTag("fullscreen_record_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isRecording) NeonRed else Color(0xFF26334D),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = "Toggle Recording",
                        tint = if (isRecording) Color.White else NeonRed
                    )
                }

                // Snapshot Button
                FilledIconButton(
                    onClick = {
                        viewModel.triggerMotionSimulation(camera)
                    },
                    modifier = Modifier.size(46.dp).testTag("fullscreen_snapshot_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF26334D),
                        contentColor = NeonCyan
                    )
                ) {
                    Icon(Icons.Default.Camera, contentDescription = "Snapshot")
                }

                // Motion Trigger Test
                FilledIconButton(
                    onClick = {
                        viewModel.triggerMotionSimulation(camera)
                    },
                    modifier = Modifier.size(46.dp).testTag("fullscreen_motion_test_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF26334D),
                        contentColor = NeonAmber
                    )
                ) {
                    Icon(Icons.Default.MotionPhotosOn, contentDescription = "Test Motion Alert")
                }

                // PTZ Drawer Toggle
                FilledIconButton(
                    onClick = { isPtzVisible = !isPtzVisible },
                    modifier = Modifier.size(46.dp).testTag("fullscreen_ptz_toggle"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isPtzVisible) NeonCyan else Color(0xFF26334D),
                        contentColor = if (isPtzVisible) Color.Black else NeonCyan
                    )
                ) {
                    Text("PTZ", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                // Sensitivity Toggle
                FilledIconButton(
                    onClick = { showSensitivitySlider = !showSensitivitySlider },
                    modifier = Modifier.size(46.dp).testTag("fullscreen_tune_toggle"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (showSensitivitySlider) NeonCyan else Color(0xFF26334D),
                        contentColor = if (showSensitivitySlider) Color.Black else Color.White
                    )
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "Sensitivity")
                }

                // Audio Mute/Unmute
                FilledIconButton(
                    onClick = {
                        isMuted = !isMuted
                        player.volume = if (isMuted) 0f else 1f
                    },
                    modifier = Modifier.size(46.dp).testTag("fullscreen_audio_toggle"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF26334D),
                        contentColor = if (isMuted) Color.Gray else NeonCyan
                    )
                ) {
                    Icon(
                        if (isMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                        contentDescription = "Audio Toggle"
                    )
                }
            }
        }
    }
}
