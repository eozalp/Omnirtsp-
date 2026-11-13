package com.example.ui.screens

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.CameraEntity
import com.example.ui.components.PtzControlOverlay
import com.example.ui.components.RtspVideoPlayer
import com.example.ui.components.VideoRotationPanel
import com.example.ui.components.normalizeRotationDegrees
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
    initialPtzOpen: Boolean = false,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val activity = context as? Activity

    val cameras by viewModel.cameras.collectAsState()
    val liveCamera = cameras.find { it.id == camera.id } ?: camera

    val player = remember(camera.id) { viewModel.streamManager.getOrCreatePlayer(camera) }
    val telemetry by viewModel.streamManager.getTelemetryFlow(camera.id).collectAsState()
    val recordingCameras by viewModel.recordingSchedulerEngine.currentlyRecordingCameras.collectAsState()
    val showTechnicalDetails by viewModel.showTechnicalDetails.collectAsState()
    val ptzStatus by viewModel.ptzStatus.collectAsState()
    val isRecording = recordingCameras.contains(camera.id)

    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffsetX by remember { mutableFloatStateOf(0.0f) }
    var panOffsetY by remember { mutableFloatStateOf(0.0f) }

    var isPtzVisible by remember(camera.id, initialPtzOpen) { mutableStateOf(initialPtzOpen) }
    var isRotationVisible by remember { mutableStateOf(false) }
    val isMuted by viewModel.isGlobalMuted.collectAsState()
    var showSensitivitySlider by remember { mutableStateOf(false) }
    var localSensitivity by remember { mutableFloatStateOf(camera.motionSensitivity.toFloat()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val latestRotation by rememberUpdatedState(liveCamera.rotationDegrees)

    LaunchedEffect(isPtzVisible, liveCamera.id, liveCamera.onvifPort) {
        if (isPtzVisible) {
            viewModel.probeCameraPtz(liveCamera)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("fullscreen_player_screen")
    ) {
        // Dedicated Top Bar (never covers video feed)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                FilledIconButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp).testTag("fullscreen_back_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SurveillanceCardBg,
                        contentColor = NeonCyan
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.fullscreen_back))
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = liveCamera.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Technical Details, Float Window, System PiP & Delete Camera
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledIconButton(
                    onClick = { viewModel.toggleTechnicalDetails() },
                    modifier = Modifier.size(40.dp).testTag("fullscreen_details_toggle"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (showTechnicalDetails) NeonCyan else SurveillanceCardBg,
                        contentColor = if (showTechnicalDetails) Color.Black else Color.White
                    )
                ) {
                    Icon(Icons.Default.Info, contentDescription = stringResource(R.string.fullscreen_toggle_details), modifier = Modifier.size(19.dp))
                }

                FilledIconButton(
                    onClick = {
                        viewModel.openFloatingCamera(liveCamera)
                        onBack()
                    },
                    modifier = Modifier.size(40.dp).testTag("pop_floating_window_btn"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SurveillanceCardBg,
                        contentColor = NeonCyan
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.fullscreen_float_window), modifier = Modifier.size(19.dp))
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    FilledIconButton(
                        onClick = {
                            val pipAspect = com.example.ui.components.computeEffectiveContainerAspectRatio(
                                sourceAspect = com.example.ui.components.parseResolutionAspectRatio(telemetry.resolution),
                                rotationDegrees = liveCamera.rotationDegrees
                            )
                            val rational = Rational((pipAspect * 100f).toInt().coerceIn(45, 239), 100)
                            val params = PictureInPictureParams.Builder()
                                .setAspectRatio(rational)
                                .build()
                            activity?.enterPictureInPictureMode(params)
                        },
                        modifier = Modifier.size(40.dp).testTag("system_pip_btn"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = SurveillanceCardBg,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = stringResource(R.string.fullscreen_pip), modifier = Modifier.size(19.dp))
                    }
                }

                FilledIconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(40.dp).testTag("fullscreen_delete_camera_btn"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SurveillanceCardBg,
                        contentColor = NeonRed
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.live_menu_remove_camera), modifier = Modifier.size(19.dp))
                }
            }
        }

        // Unobstructed Video Viewport + Non-Overlaying Control Dock
        val hasActiveDock = isPtzVisible || isRotationVisible || showSensitivitySlider
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            val isWide = maxWidth >= 600.dp

            val videoViewport: @Composable (Modifier) -> Unit = { videoMod ->
                RtspVideoPlayer(
                    camera = liveCamera,
                    player = player,
                    telemetry = telemetry,
                    zoomScale = zoomScale,
                    panOffsetX = panOffsetX,
                    panOffsetY = panOffsetY,
                    rotationDegrees = liveCamera.rotationDegrees,
                    showControlsOverlay = !isPtzVisible,
                    showTechnicalDetails = showTechnicalDetails,
                    onRetry = { viewModel.streamManager.retryPlayer(liveCamera) },
                    modifier = videoMod
                        .pointerInput(liveCamera.id) {
                            detectTransformGestures { _, pan, zoom, rotationChange ->
                                if (rotationChange != 0f) {
                                    viewModel.setCameraRotation(liveCamera.id, latestRotation + rotationChange)
                                }
                                if (zoom != 1f) {
                                    zoomScale = (zoomScale * zoom).coerceIn(1.0f, 5.0f)
                                }
                                if (zoomScale > 1.01f) {
                                    panOffsetX += pan.x
                                    panOffsetY += pan.y
                                }
                            }
                        }
                )
            }

            val activeDockContent: @Composable (Boolean, Modifier) -> Unit = { verticalDock, dockMod ->
                when {
                    isPtzVisible -> {
                        PtzControlOverlay(
                            camera = liveCamera,
                            ptzStatus = ptzStatus,
                            onStartMove = { pan, tilt, zoom, label ->
                                viewModel.startCameraPtzMove(liveCamera, pan, tilt, zoom, label)
                            },
                            onStopMove = { viewModel.stopCameraPtz(liveCamera) },
                            onGotoHome = { viewModel.gotoCameraPtzHome(liveCamera) },
                            onGotoPreset = { token -> viewModel.gotoCameraPtzPreset(liveCamera, token) },
                            onProbe = { viewModel.probeCameraPtz(liveCamera) },
                            onChangeOnvifPort = { port -> viewModel.updateCameraOnvifPort(liveCamera, port) },
                            onClose = { isPtzVisible = false },
                            isVerticalDock = verticalDock,
                            modifier = dockMod
                        )
                    }
                    isRotationVisible -> {
                        VideoRotationPanel(
                            cameraName = liveCamera.name,
                            rotationDegrees = liveCamera.rotationDegrees,
                            onRotationChange = { deg -> viewModel.setCameraRotation(liveCamera.id, deg) },
                            onClose = { isRotationVisible = false },
                            modifier = dockMod
                        )
                    }
                    showSensitivitySlider -> {
                        Surface(
                            modifier = dockMod.clip(RoundedCornerShape(14.dp)),
                            color = SurveillanceCardBg,
                            tonalElevation = 6.dp
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.fullscreen_motion_sensitivity), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("${localSensitivity.toInt()}%", color = NeonCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = localSensitivity,
                                    onValueChange = {
                                        localSensitivity = it
                                        viewModel.addOrUpdateCamera(liveCamera.copy(motionSensitivity = it.toInt()))
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
                }
            }

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    videoViewport(Modifier.weight(1f).fillMaxHeight())
                    if (hasActiveDock) {
                        Box(
                            modifier = Modifier
                                .width(310.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center
                        ) {
                            activeDockContent(true, Modifier.fillMaxWidth())
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    videoViewport(Modifier.weight(1f).fillMaxWidth())
                    if (hasActiveDock) {
                        activeDockContent(false, Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // Dedicated Bottom Controls Bar (never covers video)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .testTag("fullscreen_bottom_bar"),
            color = SurveillanceCardBg,
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Record Button
                FilledIconButton(
                    onClick = {
                        if (isRecording) {
                            viewModel.stopRecording(liveCamera.id)
                        } else {
                            viewModel.startManualRecording(liveCamera)
                        }
                    },
                    modifier = Modifier.size(44.dp).testTag("fullscreen_record_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isRecording) NeonRed else Color(0xFF26334D),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = stringResource(R.string.fullscreen_toggle_recording),
                        tint = if (isRecording) Color.White else NeonRed
                    )
                }

                // Snapshot Button
                FilledIconButton(
                    onClick = { viewModel.triggerMotionAlertManually(liveCamera, level = 85) },
                    modifier = Modifier.size(44.dp).testTag("fullscreen_snapshot_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF26334D),
                        contentColor = NeonCyan
                    )
                ) {
                    Icon(Icons.Default.Camera, contentDescription = stringResource(R.string.fullscreen_snapshot))
                }

                // Motion Trigger Test
                FilledIconButton(
                    onClick = { viewModel.triggerMotionAlertManually(liveCamera, level = 90) },
                    modifier = Modifier.size(44.dp).testTag("fullscreen_motion_test_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF26334D),
                        contentColor = NeonAmber
                    )
                ) {
                    Icon(Icons.Default.MotionPhotosOn, contentDescription = stringResource(R.string.live_menu_test_motion))
                }

                // Hardware Camera PTZ Dock Toggle
                FilledIconButton(
                    onClick = {
                        isPtzVisible = !isPtzVisible
                        if (isPtzVisible) {
                            isRotationVisible = false
                            showSensitivitySlider = false
                        }
                    },
                    modifier = Modifier.size(44.dp).testTag("fullscreen_ptz_toggle"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isPtzVisible) NeonCyan else Color(0xFF26334D),
                        contentColor = if (isPtzVisible) Color.Black else NeonCyan
                    )
                ) {
                    Text(stringResource(R.string.ptz_short), fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, softWrap = false)
                }

                // Rotate Video Toggle
                val isRotated = normalizeRotationDegrees(liveCamera.rotationDegrees) != 0f
                FilledIconButton(
                    onClick = {
                        isRotationVisible = !isRotationVisible
                        if (isRotationVisible) {
                            isPtzVisible = false
                            showSensitivitySlider = false
                        }
                    },
                    modifier = Modifier.size(44.dp).testTag("fullscreen_rotate_toggle"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isRotationVisible || isRotated) NeonCyan else Color(0xFF26334D),
                        contentColor = if (isRotationVisible || isRotated) Color.Black else Color.White
                    )
                ) {
                    Icon(Icons.Default.RotateRight, contentDescription = stringResource(R.string.live_rotate_video))
                }

                // Sensitivity Toggle
                FilledIconButton(
                    onClick = {
                        showSensitivitySlider = !showSensitivitySlider
                        if (showSensitivitySlider) {
                            isPtzVisible = false
                            isRotationVisible = false
                        }
                    },
                    modifier = Modifier.size(44.dp).testTag("fullscreen_tune_toggle"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (showSensitivitySlider) NeonCyan else Color(0xFF26334D),
                        contentColor = if (showSensitivitySlider) Color.Black else Color.White
                    )
                ) {
                    Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.fullscreen_motion_sensitivity))
                }

                // Global Audio Mute/Unmute (synced across live view & floating windows)
                FilledIconButton(
                    onClick = { viewModel.toggleGlobalMute() },
                    modifier = Modifier.size(44.dp).testTag("fullscreen_audio_toggle"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isMuted) Color(0xFF442222) else Color(0xFF26334D),
                        contentColor = if (isMuted) Color.LightGray else NeonCyan
                    )
                ) {
                    Icon(
                        if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.fullscreen_audio_toggle)
                    )
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = NeonRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.live_remove_dialog_title), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = stringResource(R.string.live_remove_dialog_body, liveCamera.name),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteCamera(liveCamera)
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonRed, contentColor = Color.White),
                    modifier = Modifier.testTag("confirm_delete_fullscreen_btn")
                ) {
                    Text(stringResource(R.string.common_remove), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel), color = Color.Gray, maxLines = 1, softWrap = false)
                }
            },
            containerColor = SurveillanceCardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
