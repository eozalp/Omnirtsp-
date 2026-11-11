package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CameraEntity
import com.example.ui.components.RtspVideoPlayer
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceDarkBg
import com.example.ui.theme.SurveillanceSurface
import com.example.ui.viewmodel.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveMultiCamScreen(
    viewModel: CameraViewModel,
    onSelectCamera: (CameraEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val cameras by viewModel.cameras.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val isTvMode by viewModel.isTvMode.collectAsState()
    val alerts by viewModel.alerts.collectAsState()
    val recordingCameras by viewModel.recordingSchedulerEngine.currentlyRecordingCameras.collectAsState()
    val activeSchedules by viewModel.recordingSchedulerEngine.activeSchedulesState.collectAsState()

    var showCameraDialog by remember { mutableStateOf(false) }
    var cameraToEdit by remember { mutableStateOf<CameraEntity?>(null) }
    var cameraToDelete by remember { mutableStateOf<CameraEntity?>(null) }

    val recentAlert = alerts.firstOrNull { !it.isRead }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurveillanceDarkBg)
            .testTag("live_multicam_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "OmniView RTSP",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = NeonCyan
                        )
                        if (activeSchedules.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF1E3A2B)
                            ) {
                                Text(
                                    text = "SCHEDULE ACTIVE",
                                    color = Color(0xFF34D399),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "${cameras.size} Live Feeds • ${recordingCameras.size} Recording",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                // Grid Layout Controls & TV Mode Toggle
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Grid 1x1
                    FilledIconButton(
                        onClick = { viewModel.setGridColumns(1) },
                        modifier = Modifier.size(36.dp).testTag("grid_1x1_btn"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (gridColumns == 1) NeonCyan else SurveillanceSurface,
                            contentColor = if (gridColumns == 1) Color.Black else Color.White
                        )
                    ) {
                        Icon(Icons.Default.ViewAgenda, contentDescription = "Single View", modifier = Modifier.size(18.dp))
                    }

                    // Grid 2x2
                    FilledIconButton(
                        onClick = { viewModel.setGridColumns(2) },
                        modifier = Modifier.size(36.dp).testTag("grid_2x2_btn"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (gridColumns == 2) NeonCyan else SurveillanceSurface,
                            contentColor = if (gridColumns == 2) Color.Black else Color.White
                        )
                    ) {
                        Icon(Icons.Default.GridView, contentDescription = "2x2 Grid", modifier = Modifier.size(18.dp))
                    }

                    // TV Mode Button
                    FilledIconButton(
                        onClick = { viewModel.toggleTvMode() },
                        modifier = Modifier.size(36.dp).testTag("tv_mode_toggle"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isTvMode) NeonAmber else SurveillanceSurface,
                            contentColor = if (isTvMode) Color.Black else Color.White
                        )
                    ) {
                        Icon(Icons.Default.Tv, contentDescription = "Android TV Navigation Mode", modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Top Motion Alert Notification Banner
            AnimatedVisibility(visible = recentAlert != null) {
                recentAlert?.let { alert ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                viewModel.markAlertRead(alert.id)
                                cameras.find { it.id == alert.cameraId }?.let { onSelectCamera(it) }
                            }
                            .testTag("motion_alert_banner"),
                        color = NeonAmber.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonAmber)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Motion Alert",
                                tint = NeonAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ALERT: Motion on ${alert.cameraName}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = NeonAmber
                                )
                                Text(
                                    text = alert.description,
                                    fontSize = 11.sp,
                                    color = Color.White
                                )
                            }
                            Text(
                                text = "VIEW",
                                color = NeonAmber,
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // Multi-cam Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (isTvMode) 2 else gridColumns),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("camera_matrix_grid")
            ) {
                items(cameras, key = { it.id }) { camera ->
                    val player = remember(camera.id) { viewModel.streamManager.getOrCreatePlayer(camera) }
                    val telemetry by viewModel.streamManager.getTelemetryFlow(camera.id).collectAsState()
                    val isRecording = recordingCameras.contains(camera.id)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("camera_card_${camera.id}"),
                        colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Column {
                            // Video Stream
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                            ) {
                                RtspVideoPlayer(
                                    camera = camera,
                                    player = player,
                                    telemetry = telemetry,
                                    isTvFocused = isTvMode,
                                    onRetry = { viewModel.streamManager.retryPlayer(camera) },
                                    onClick = { onSelectCamera(camera) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Card Action Toolbar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SurveillanceSurface)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Camera title
                                Text(
                                    text = camera.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )

                                // Action Buttons
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // Record Button
                                    IconButton(
                                        onClick = {
                                            if (isRecording) {
                                                viewModel.stopRecording(camera.id)
                                            } else {
                                                viewModel.startManualRecording(camera)
                                            }
                                        },
                                        modifier = Modifier.size(32.dp).testTag("record_cam_${camera.id}")
                                    ) {
                                        Icon(
                                            if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                            contentDescription = "Record",
                                            tint = if (isRecording) NeonRed else Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    // Motion Test Trigger
                                    IconButton(
                                        onClick = { viewModel.triggerMotionAlertManually(camera, level = 80) },
                                        modifier = Modifier.size(32.dp).testTag("test_motion_cam_${camera.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.MotionPhotosOn,
                                            contentDescription = "Test Motion Alert",
                                            tint = NeonAmber,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    // Pop Floating Window with Resize
                                    IconButton(
                                        onClick = { viewModel.setFloatingCamera(camera) },
                                        modifier = Modifier.size(32.dp).testTag("floating_cam_${camera.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.OpenInNew,
                                            contentDescription = "Float Window",
                                            tint = NeonCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    // Fullscreen
                                    IconButton(
                                        onClick = { onSelectCamera(camera) },
                                        modifier = Modifier.size(32.dp).testTag("fullscreen_cam_${camera.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.Fullscreen,
                                            contentDescription = "Fullscreen",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    // Edit Settings
                                    IconButton(
                                        onClick = {
                                            cameraToEdit = camera
                                            showCameraDialog = true
                                        },
                                        modifier = Modifier.size(32.dp).testTag("edit_cam_${camera.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.Settings,
                                            contentDescription = "Edit Settings",
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Remove / Delete Camera
                                    IconButton(
                                        onClick = { cameraToDelete = camera },
                                        modifier = Modifier.size(32.dp).testTag("delete_cam_${camera.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remove Camera",
                                            tint = NeonRed.copy(alpha = 0.85f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Add Camera FAB
        FloatingActionButton(
            onClick = {
                cameraToEdit = null
                showCameraDialog = true
            },
            containerColor = NeonCyan,
            contentColor = Color.Black,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("add_camera_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Camera")
        }

        // Camera Settings Dialog with onDelete support
        if (showCameraDialog) {
            CameraSettingsDialog(
                camera = cameraToEdit,
                onDismiss = { showCameraDialog = false },
                onSave = { camera ->
                    viewModel.addOrUpdateCamera(camera)
                    showCameraDialog = false
                },
                onDelete = { camera ->
                    viewModel.deleteCamera(camera)
                    showCameraDialog = false
                }
            )
        }

        // Card Delete Confirmation Dialog
        cameraToDelete?.let { cam ->
            AlertDialog(
                onDismissRequest = { cameraToDelete = null },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = NeonRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Remove Camera?", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Text(
                        text = "Are you sure you want to remove '${cam.name}' (${cam.location})? This will disconnect the live stream and delete the camera from your surveillance matrix.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteCamera(cam)
                            cameraToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonRed, contentColor = Color.White),
                        modifier = Modifier.testTag("confirm_delete_card_btn")
                    ) {
                        Text("Remove Camera", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { cameraToDelete = null }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = SurveillanceCardBg,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}
