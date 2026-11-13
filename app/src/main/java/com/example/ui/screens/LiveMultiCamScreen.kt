package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.model.CameraEntity
import com.example.engine.DiscoveredCamera
import com.example.i18n.AppLocaleManager
import com.example.ui.components.RtspVideoPlayer
import com.example.ui.components.VideoRotationDialog
import com.example.ui.components.computeEffectiveContainerAspectRatio
import com.example.ui.components.normalizeRotationDegrees
import com.example.ui.components.parseResolutionAspectRatio
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceDarkBg
import com.example.ui.theme.SurveillanceSurface
import com.example.ui.viewmodel.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LiveMultiCamScreen(
    viewModel: CameraViewModel,
    onSelectCamera: (CameraEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val cameras by viewModel.cameras.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val feedSizeScales by viewModel.feedSizeScales.collectAsState()
    val isTvMode by viewModel.isTvMode.collectAsState()
    val showTechnicalDetails by viewModel.showTechnicalDetails.collectAsState()
    val floatingWindows by viewModel.floatingWindows.collectAsState()
    val isSystemOverlayActive by viewModel.isSystemOverlayActive.collectAsState()
    val alerts by viewModel.alerts.collectAsState()
    val recordingCameras by viewModel.recordingSchedulerEngine.currentlyRecordingCameras.collectAsState()
    val discoveryState by viewModel.discoveryState.collectAsState()
    val selectedLanguageTag by viewModel.selectedLanguageTag.collectAsState()
    val isGlobalMuted by viewModel.isGlobalMuted.collectAsState()

    var showCameraDialog by remember { mutableStateOf(false) }
    var cameraToEdit by remember { mutableStateOf<CameraEntity?>(null) }
    var suggestedCameraPrefill by remember { mutableStateOf<DiscoveredCamera?>(null) }
    var showDiscoveryDialog by remember { mutableStateOf(false) }
    var cameraToDelete by remember { mutableStateOf<CameraEntity?>(null) }
    var cameraToRotateId by remember { mutableStateOf<String?>(null) }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = Settings.canDrawOverlays(context)
        canDrawOverlays = granted
        if (granted) {
            viewModel.startSystemOverlayOverApps()
        }
    }

    val launchOverlaySettings: () -> Unit = {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            overlayPermissionLauncher.launch(intent)
        } catch (_: Exception) {
            try {
                overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {}
        }
    }

    val recentAlert = alerts.firstOrNull { !it.isRead }
    val floatingIds = remember(floatingWindows) { floatingWindows.map { it.cameraId }.toSet() }
    val isTurkishActive = remember(selectedLanguageTag) { AppLocaleManager.isTurkish(context) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurveillanceDarkBg)
            .testTag("live_multicam_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Clean Responsive Two-Row Header Bar (prevents crowding on compact phones)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.live_title),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = NeonCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val recText = if (recordingCameras.isNotEmpty()) {
                            stringResource(R.string.live_rec_suffix, recordingCameras.size)
                        } else {
                            ""
                        }
                        val floatText = if (floatingWindows.isNotEmpty()) {
                            stringResource(R.string.live_float_suffix, floatingWindows.size)
                        } else {
                            ""
                        }
                        Text(
                            text = "${stringResource(R.string.live_cams_count, cameras.size)}$recText$floatText",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Quick Language & Global Audio Mute Toggles
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SurveillanceSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.setAppLanguage(
                                        if (isTurkishActive) AppLocaleManager.LANG_ENGLISH else AppLocaleManager.LANG_TURKISH
                                    )
                                }
                                .testTag("quick_language_toggle_btn")
                        ) {
                            Text(
                                text = if (isTurkishActive) {
                                    stringResource(R.string.language_quick_badge_tr)
                                } else {
                                    stringResource(R.string.language_quick_badge_en)
                                },
                                color = NeonCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isGlobalMuted) Color(0xFF442222) else SurveillanceSurface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isGlobalMuted) NeonRed.copy(alpha = 0.7f) else NeonCyan.copy(alpha = 0.55f)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.toggleGlobalMute() }
                                .testTag("global_mute_toggle_btn")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = if (isGlobalMuted) {
                                        Icons.AutoMirrored.Filled.VolumeOff
                                    } else {
                                        Icons.AutoMirrored.Filled.VolumeUp
                                    },
                                    contentDescription = stringResource(R.string.fullscreen_audio_toggle),
                                    tint = if (isGlobalMuted) NeonRed else NeonCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isGlobalMuted) {
                                        stringResource(R.string.sound_muted_short)
                                    } else {
                                        stringResource(R.string.sound_on_short)
                                    },
                                    color = if (isGlobalMuted) Color.White else NeonCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                // Action Controls Toolbar
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Auto-Discover Network Cameras
                    val unaddedFoundCount = discoveryState.results.count { !it.alreadyAdded }
                    FilledIconButton(
                        onClick = {
                            showDiscoveryDialog = true
                            if (!discoveryState.isScanning) {
                                viewModel.scanForNetworkCameras()
                            }
                        },
                        modifier = Modifier.size(40.dp).testTag("discover_cameras_btn"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (unaddedFoundCount > 0 || discoveryState.isScanning) NeonCyan else SurveillanceSurface,
                            contentColor = if (unaddedFoundCount > 0 || discoveryState.isScanning) Color.Black else NeonCyan
                        )
                    ) {
                        if (discoveryState.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.Black
                            )
                        } else {
                            Icon(
                                Icons.Default.Router,
                                contentDescription = stringResource(R.string.live_find_network_cameras),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    // Show Over Other Apps (YouTube / System Overlay)
                    FilledIconButton(
                        onClick = {
                            canDrawOverlays = Settings.canDrawOverlays(context)
                            if (isSystemOverlayActive) {
                                viewModel.stopSystemOverlayOverApps()
                            } else if (canDrawOverlays) {
                                viewModel.startSystemOverlayOverApps()
                            } else {
                                showOverlayPermissionDialog = true
                            }
                        },
                        modifier = Modifier.size(40.dp).testTag("over_apps_overlay_btn"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isSystemOverlayActive) NeonGreen else SurveillanceSurface,
                            contentColor = if (isSystemOverlayActive) Color.Black else NeonCyan
                        )
                    ) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = stringResource(R.string.live_show_over_other_apps),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Float All / Clear Floating
                    FilledIconButton(
                        onClick = {
                            canDrawOverlays = Settings.canDrawOverlays(context)
                            if (floatingWindows.size >= cameras.size && cameras.isNotEmpty()) {
                                viewModel.closeAllFloatingWindows()
                            } else {
                                viewModel.floatAllCameras()
                                if (!canDrawOverlays) {
                                    showOverlayPermissionDialog = true
                                }
                            }
                        },
                        modifier = Modifier.size(40.dp).testTag("float_all_btn"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (floatingWindows.isNotEmpty()) NeonCyan else SurveillanceSurface,
                            contentColor = if (floatingWindows.isNotEmpty()) Color.Black else Color.White
                        )
                    ) {
                        Icon(
                            if (floatingWindows.size >= cameras.size && cameras.isNotEmpty()) Icons.Default.Close else Icons.Default.PictureInPictureAlt,
                            contentDescription = if (floatingWindows.isNotEmpty()) {
                                stringResource(R.string.live_close_floating_windows)
                            } else {
                                stringResource(R.string.live_float_all_cameras)
                            },
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Toggle Technical Details (hidden by default)
                    FilledIconButton(
                        onClick = { viewModel.toggleTechnicalDetails() },
                        modifier = Modifier.size(40.dp).testTag("toggle_details_btn"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (showTechnicalDetails) NeonCyan else SurveillanceSurface,
                            contentColor = if (showTechnicalDetails) Color.Black else Color.White
                        )
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(R.string.live_show_technical_details),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Toggle Grid 1x1 / 2x2
                    FilledIconButton(
                        onClick = { viewModel.setGridColumns(if (gridColumns == 1) 2 else 1) },
                        modifier = Modifier.size(40.dp).testTag("grid_toggle_btn"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = SurveillanceSurface,
                            contentColor = NeonCyan
                        )
                    ) {
                        Icon(
                            if (gridColumns == 1) Icons.Default.GridView else Icons.Default.ViewAgenda,
                            contentDescription = if (gridColumns == 1) {
                                stringResource(R.string.live_grid_2x2)
                            } else {
                                stringResource(R.string.live_grid_single)
                            },
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // TV Mode Toggle
                    FilledIconButton(
                        onClick = { viewModel.toggleTvMode() },
                        modifier = Modifier.size(40.dp).testTag("tv_mode_toggle"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isTvMode) NeonAmber else SurveillanceSurface,
                            contentColor = if (isTvMode) Color.Black else Color.White
                        )
                    ) {
                        Icon(
                            Icons.Default.Tv,
                            contentDescription = stringResource(R.string.live_tv_mode),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            // Helpful Prompt Banner when floating windows are open without system-wide overlay permission
            AnimatedVisibility(visible = floatingWindows.isNotEmpty() && !canDrawOverlays) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showOverlayPermissionDialog = true }
                        .testTag("enable_over_apps_banner"),
                    color = NeonCyan.copy(alpha = 0.14f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.Layers, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.live_watch_over_apps_banner),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = stringResource(R.string.live_enable_action),
                            color = NeonCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Automatic Network Camera Suggestion Banner
            val suggestedCameras = remember(discoveryState.results) {
                discoveryState.results.filter { !it.alreadyAdded }
            }
            AnimatedVisibility(visible = suggestedCameras.isNotEmpty()) {
                val topCam = suggestedCameras.first()
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showDiscoveryDialog = true }
                        .testTag("network_discovery_banner"),
                    color = NeonGreen.copy(alpha = 0.14f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen.copy(alpha = 0.65f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.Router, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (suggestedCameras.size == 1) {
                                    stringResource(R.string.live_found_one_camera, topCam.name, topCam.ipAddress)
                                } else {
                                    stringResource(R.string.live_found_many_cameras, suggestedCameras.size)
                                },
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = stringResource(R.string.live_connect_action),
                            color = NeonGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Motion Alert Notification Banner
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
                                contentDescription = stringResource(R.string.nav_alerts_desc),
                                tint = NeonAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.live_motion_banner, alert.cameraName),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = stringResource(R.string.live_view_action),
                                color = NeonAmber,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // Empty State or Camera Grid
            if (cameras.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.live_empty_title),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.live_empty_subtitle),
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                val effectiveCols = if (isTvMode) 2 else gridColumns
                LazyVerticalGrid(
                    columns = GridCells.Fixed(effectiveCols),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("camera_matrix_grid")
                ) {
                    items(
                        items = cameras,
                        key = { it.id },
                        span = { cam ->
                            val scale = feedSizeScales[cam.id] ?: 1f
                            if (effectiveCols > 1 && scale >= 1.35f) {
                                GridItemSpan(maxLineSpan)
                            } else {
                                GridItemSpan(1)
                            }
                        }
                    ) { camera ->
                        val initialPlayer = remember(camera.id) { viewModel.streamManager.getOrCreatePlayer(camera) }
                        val reactivePlayer by viewModel.streamManager.getPlayerFlow(camera.id).collectAsState()
                        val player = reactivePlayer ?: initialPlayer
                        val telemetry by viewModel.streamManager.getTelemetryFlow(camera.id).collectAsState()
                        val isRecording = recordingCameras.contains(camera.id)
                        val isFloating = floatingIds.contains(camera.id)
                        val sizeScale = feedSizeScales[camera.id] ?: 1.0f
                        var menuExpanded by remember { mutableStateOf(false) }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("camera_card_${camera.id}"),
                            colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                            shape = RoundedCornerShape(14.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column {
                                // Resizable Video Preview (Adapts 16:9 <-> 9:16 with rotation, zero black sides)
                                val sourceAspect = parseResolutionAspectRatio(telemetry.resolution)
                                val rotatedAspect = computeEffectiveContainerAspectRatio(sourceAspect, camera.rotationDegrees)
                                val dynamicAspectRatio = (rotatedAspect / sizeScale).coerceIn(0.45f, 2.4f)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(dynamicAspectRatio)
                                ) {
                                    if (isFloating) {
                                        // Yield the single ExoPlayer video surface to the active floating window (in-app or system overlay)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(SurveillanceDarkBg)
                                                .clickable { viewModel.bringFloatingToFront(camera.id) }
                                                .padding(12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.PictureInPictureAlt,
                                                    contentDescription = null,
                                                    tint = NeonCyan,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                                Text(
                                                    text = stringResource(R.string.live_floating_active_hint),
                                                    color = Color.White.copy(alpha = 0.8f),
                                                    fontSize = 11.sp,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    } else {
                                        RtspVideoPlayer(
                                            camera = camera,
                                            player = player,
                                            telemetry = telemetry,
                                            showTechnicalDetails = showTechnicalDetails,
                                            isTvFocused = isTvMode,
                                            onRetry = { viewModel.streamManager.retryPlayer(camera) },
                                            onClick = { onSelectCamera(camera) },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    // Corner drag-to-resize grip for individual feed cards
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.55f))
                                            .pointerInput(camera.id, sizeScale) {
                                                var currentScale = sizeScale
                                                detectDragGestures(
                                                    onDragStart = { currentScale = sizeScale },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        val deltaDp = with(density) { (dragAmount.x + dragAmount.y).toDp().value }
                                                        currentScale = (currentScale + deltaDp * 0.0045f).coerceIn(0.7f, 1.8f)
                                                        viewModel.updateFeedSizeScale(camera.id, currentScale)
                                                    }
                                                )
                                            }
                                            .testTag("card_resize_corner_${camera.id}"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "⤡",
                                            color = if (sizeScale != 1f) NeonCyan else Color.White.copy(alpha = 0.85f),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Black,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }

                                // Simple, Clean Card Toolbar
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(SurveillanceSurface)
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = camera.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Rotate Video Quick Button (shown directly in 1-col/TV; also always in More menu)
                                        val isRotated = normalizeRotationDegrees(camera.rotationDegrees) != 0f
                                        if (effectiveCols == 1 || isTvMode || isRotated) {
                                            IconButton(
                                                onClick = { cameraToRotateId = camera.id },
                                                modifier = Modifier.size(36.dp).testTag("rotate_cam_${camera.id}")
                                            ) {
                                                Icon(
                                                    Icons.Default.RotateRight,
                                                    contentDescription = stringResource(R.string.live_rotate_video),
                                                    tint = if (isRotated) NeonCyan else Color.White.copy(alpha = 0.8f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        // Float / Pop Out Button (also floats over YouTube if overlay permission granted)
                                        IconButton(
                                            onClick = {
                                                canDrawOverlays = Settings.canDrawOverlays(context)
                                                viewModel.toggleFloatingCamera(camera)
                                                if (!isFloating && !canDrawOverlays) {
                                                    showOverlayPermissionDialog = true
                                                }
                                            },
                                            modifier = Modifier.size(36.dp).testTag("floating_cam_${camera.id}")
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = if (isFloating) {
                                                    stringResource(R.string.live_close_floating)
                                                } else {
                                                    stringResource(R.string.live_open_floating)
                                                },
                                                tint = if (isFloating) NeonCyan else Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        // Record Button
                                        IconButton(
                                            onClick = {
                                                if (isRecording) {
                                                    viewModel.stopRecording(camera.id)
                                                } else {
                                                    viewModel.startManualRecording(camera)
                                                }
                                            },
                                            modifier = Modifier.size(36.dp).testTag("record_cam_${camera.id}")
                                        ) {
                                            Icon(
                                                if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                                contentDescription = if (isRecording) {
                                                    stringResource(R.string.live_stop_recording)
                                                } else {
                                                    stringResource(R.string.live_record)
                                                },
                                                tint = if (isRecording) NeonRed else Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        // Overflow Menu (Settings, Reset Size, Fullscreen, Motion Test, Remove)
                                        Box {
                                            IconButton(
                                                onClick = { menuExpanded = true },
                                                modifier = Modifier.size(40.dp).testTag("more_cam_${camera.id}")
                                            ) {
                                                Icon(
                                                    Icons.Default.MoreVert,
                                                    contentDescription = stringResource(R.string.live_more_options),
                                                    tint = Color.White.copy(alpha = 0.8f),
                                                    modifier = Modifier.size(19.dp)
                                                )
                                            }

                                            DropdownMenu(
                                                expanded = menuExpanded,
                                                onDismissRequest = { menuExpanded = false },
                                                containerColor = SurveillanceCardBg
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.live_menu_fullscreen), color = Color.White) },
                                                    leadingIcon = { Icon(Icons.Default.Fullscreen, contentDescription = null, tint = NeonCyan) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        onSelectCamera(camera)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.live_menu_ptz), color = Color.White) },
                                                    leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null, tint = NeonCyan) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        viewModel.selectFullscreenCamera(camera, openPtz = true)
                                                    }
                                                )
                                                if (sizeScale != 1.0f) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.live_menu_reset_size), color = Color.White) },
                                                        leadingIcon = { Icon(Icons.Default.GridView, contentDescription = null, tint = NeonCyan) },
                                                        onClick = {
                                                            menuExpanded = false
                                                            viewModel.resetFeedSizeScale(camera.id)
                                                        }
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.live_rotate_video), color = Color.White) },
                                                    leadingIcon = { Icon(Icons.Default.RotateRight, contentDescription = null, tint = NeonCyan) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        cameraToRotateId = camera.id
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.live_menu_camera_settings), color = Color.White) },
                                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        cameraToEdit = camera
                                                        showCameraDialog = true
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.live_menu_test_motion), color = Color.White) },
                                                    leadingIcon = { Icon(Icons.Default.MotionPhotosOn, contentDescription = null, tint = NeonAmber) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        viewModel.triggerMotionAlertManually(camera, level = 80)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.live_menu_remove_camera), color = NeonRed) },
                                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = NeonRed) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        cameraToDelete = camera
                                                    }
                                                )
                                            }
                                        }
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
                suggestedCameraPrefill = null
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
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.live_add_camera))
        }

        // Overlay ("Show Over Other Apps") Permission Dialog
        if (showOverlayPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showOverlayPermissionDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Layers, contentDescription = null, tint = NeonCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.live_overlay_dialog_title), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Text(
                        text = stringResource(R.string.live_overlay_dialog_body),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showOverlayPermissionDialog = false
                            launchOverlaySettings()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                        modifier = Modifier.testTag("grant_overlay_permission_btn")
                    ) {
                        Text(stringResource(R.string.common_allow), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showOverlayPermissionDialog = false }) {
                        Text(stringResource(R.string.live_overlay_in_app_only), color = Color.Gray, maxLines = 1, softWrap = false)
                    }
                },
                containerColor = SurveillanceCardBg,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Network Camera Discovery Dialog
        if (showDiscoveryDialog) {
            NetworkCameraDiscoveryDialog(
                discoveryState = discoveryState,
                onRescan = { viewModel.scanForNetworkCameras() },
                onQuickConnect = { found ->
                    viewModel.connectDiscoveredCamera(found)
                    showDiscoveryDialog = false
                },
                onConfigure = { found ->
                    showDiscoveryDialog = false
                    cameraToEdit = null
                    suggestedCameraPrefill = found
                    showCameraDialog = true
                },
                onDismiss = { showDiscoveryDialog = false }
            )
        }

        // Camera Settings Dialog
        if (showCameraDialog) {
            CameraSettingsDialog(
                camera = cameraToEdit,
                discoveryState = discoveryState,
                onTriggerDiscoveryScan = { viewModel.scanForNetworkCameras() },
                onFetchOnvifRtspUrl = { rtspOrIp, port, user, pass ->
                    viewModel.fetchOnvifRtspUrl(rtspOrIp, port, user, pass)
                },
                initialSuggestedCamera = suggestedCameraPrefill,
                onDismiss = {
                    showCameraDialog = false
                    suggestedCameraPrefill = null
                },
                onSave = { camera ->
                    viewModel.addOrUpdateCamera(camera)
                    showCameraDialog = false
                    suggestedCameraPrefill = null
                },
                onDelete = { camera ->
                    viewModel.deleteCamera(camera)
                    showCameraDialog = false
                    suggestedCameraPrefill = null
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
                        Text(stringResource(R.string.live_remove_dialog_title), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Text(
                        text = stringResource(R.string.live_remove_dialog_body, cam.name),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp
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
                        Text(stringResource(R.string.common_remove), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { cameraToDelete = null }) {
                        Text(stringResource(R.string.common_cancel), color = Color.Gray, maxLines = 1, softWrap = false)
                    }
                },
                containerColor = SurveillanceCardBg,
                shape = RoundedCornerShape(16.dp)
            )
        }
        // Video Rotation Dialog (Free & By-Degree)
        cameraToRotateId?.let { targetId ->
            val targetCam = cameras.find { it.id == targetId }
            if (targetCam != null) {
                VideoRotationDialog(
                    cameraName = targetCam.name,
                    rotationDegrees = targetCam.rotationDegrees,
                    onRotationChange = { deg -> viewModel.setCameraRotation(targetCam.id, deg) },
                    onDismiss = { cameraToRotateId = null }
                )
            }
        }
    }
}

@Composable
private fun NetworkCameraDiscoveryDialog(
    discoveryState: com.example.engine.DiscoveryScanState,
    onRescan: () -> Unit,
    onQuickConnect: (DiscoveredCamera) -> Unit,
    onConfigure: (DiscoveredCamera) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .testTag("network_discovery_dialog"),
            color = SurveillanceCardBg,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Router, contentDescription = null, tint = NeonCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.discovery_title),
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = discoveryState.progressText.ifBlank { stringResource(R.string.discovery_default_subtitle) },
                                color = if (discoveryState.errorMessage != null) NeonAmber else Color.Gray,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = Color.White)
                    }
                }

                discoveryState.errorMessage?.let { err ->
                    Text(
                        text = err,
                        color = NeonAmber,
                        fontSize = 12.sp
                    )
                }

                if (discoveryState.isScanning) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurveillanceSurface)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = NeonCyan)
                        Text(
                            text = stringResource(
                                R.string.discovery_scanning_subnet,
                                discoveryState.subnetLabel.ifBlank { stringResource(R.string.discovery_wifi_lan) }
                            ),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }

                if (discoveryState.results.isEmpty() && !discoveryState.isScanning) {
                    Surface(
                        color = SurveillanceSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.discovery_none_title),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.discovery_none_body),
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else if (discoveryState.results.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(discoveryState.results, key = { it.id }) { cam ->
                            Surface(
                                color = SurveillanceSurface,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text(
                                                text = cam.name,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${cam.ipAddress} • ${cam.discoveryMethod}",
                                                color = NeonCyan,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (cam.alreadyAdded) {
                                            Text(
                                                text = stringResource(R.string.discovery_added_badge),
                                                color = NeonGreen,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Black,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    Text(
                                        text = cam.suggestedRtspUrl,
                                        color = Color.White.copy(alpha = 0.72f),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                                    ) {
                                        OutlinedButton(
                                            onClick = { onConfigure(cam) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.discovery_edit_credentials),
                                                fontSize = 11.sp,
                                                color = Color.White,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        Button(
                                            onClick = { onQuickConnect(cam) },
                                            enabled = !cam.alreadyAdded,
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = if (cam.alreadyAdded) {
                                                    stringResource(R.string.discovery_connected)
                                                } else {
                                                    stringResource(R.string.discovery_connect)
                                                },
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onRescan,
                        enabled = !discoveryState.isScanning,
                        modifier = Modifier.testTag("dialog_rescan_btn")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (discoveryState.isScanning) {
                                stringResource(R.string.discovery_scanning_btn)
                            } else {
                                stringResource(R.string.discovery_rescan_btn)
                            },
                            color = NeonCyan,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_close), color = Color.Gray, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
