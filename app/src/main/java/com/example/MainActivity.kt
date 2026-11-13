package com.example

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.i18n.AppLocaleManager
import com.example.ui.components.FloatingCameraWindow
import com.example.ui.components.RtspVideoPlayer
import com.example.ui.screens.CloudStorageScreen
import com.example.ui.screens.FullScreenPlayerScreen
import com.example.ui.screens.LiveMultiCamScreen
import com.example.ui.screens.MotionAlertsScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.screens.ScheduleScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceDarkBg
import com.example.ui.viewmodel.CameraViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()
    private var isSystemPiP by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val selectedLanguageTag by viewModel.selectedLanguageTag.collectAsState()
            val baseConfig = LocalConfiguration.current
            val localizedConfig = remember(selectedLanguageTag, baseConfig) {
                AppLocaleManager.applyLocaleToResources(this@MainActivity, selectedLanguageTag)
            }

            CompositionLocalProvider(
                LocalConfiguration provides localizedConfig,
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalOnBackPressedDispatcherOwner provides this@MainActivity
            ) {
                MyApplicationTheme {
                    if (isSystemPiP) {
                    // System PiP minimal viewer: shows active floating cameras (or fullscreen/first camera)
                    val cameras by viewModel.cameras.collectAsState()
                    val floatingWindows by viewModel.floatingWindows.collectAsState()
                    val selectedFullscreen by viewModel.selectedFullscreenCamera.collectAsState()

                    val pipCameras = remember(cameras, floatingWindows, selectedFullscreen) {
                        if (floatingWindows.isNotEmpty()) {
                            floatingWindows
                                .sortedBy { it.zIndex }
                                .mapNotNull { win -> cameras.find { it.id == win.cameraId } }
                        } else {
                            listOfNotNull(selectedFullscreen ?: cameras.firstOrNull())
                        }
                    }

                    if (pipCameras.size == 1) {
                        val targetCamera = pipCameras.first()
                        val player = remember(targetCamera.id) {
                            viewModel.streamManager.getOrCreatePlayer(targetCamera)
                        }
                        val telemetry by viewModel.streamManager.getTelemetryFlow(targetCamera.id).collectAsState()
                        RtspVideoPlayer(
                            camera = targetCamera,
                            player = player,
                            telemetry = telemetry,
                            rotationDegrees = targetCamera.rotationDegrees,
                            showControlsOverlay = false,
                            showTechnicalDetails = false,
                            compactMode = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (pipCameras.size > 1) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        ) {
                            pipCameras.chunked(2).forEach { rowCams ->
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    rowCams.forEach { cam ->
                                        key(cam.id) {
                                            val player = remember(cam.id) {
                                                viewModel.streamManager.getOrCreatePlayer(cam)
                                            }
                                            val telemetry by viewModel.streamManager.getTelemetryFlow(cam.id).collectAsState()
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                            ) {
                                                RtspVideoPlayer(
                                                    camera = cam,
                                                    player = player,
                                                    telemetry = telemetry,
                                                    rotationDegrees = cam.rotationDegrees,
                                                    showControlsOverlay = false,
                                                    showTechnicalDetails = false,
                                                    compactMode = true,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    OmniViewApp(viewModel = viewModel)
                }
            }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isSystemPiP = isInPictureInPictureMode
    }
}

@Composable
fun OmniViewApp(viewModel: CameraViewModel) {
    val activeScreen by viewModel.activeScreen.collectAsState()
    val selectedFullscreenCamera by viewModel.selectedFullscreenCamera.collectAsState()
    val openFullscreenWithPtz by viewModel.openFullscreenWithPtz.collectAsState()
    val floatingWindows by viewModel.floatingWindows.collectAsState()
    val isSystemOverlayActive by viewModel.isSystemOverlayActive.collectAsState()
    val cameras by viewModel.cameras.collectAsState()
    val showTechnicalDetails by viewModel.showTechnicalDetails.collectAsState()
    val unreadAlertCount by viewModel.unreadAlertCount.collectAsState()
    val isTvMode by viewModel.isTvMode.collectAsState()
    val isGlobalMuted by viewModel.isGlobalMuted.collectAsState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(SurveillanceDarkBg)
    ) {
        val density = LocalDensity.current
        val safeTopPx = WindowInsets.safeDrawing.getTop(density).toFloat()
        val safeBottomPx = WindowInsets.safeDrawing.getBottom(density).toFloat()
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val isWideScreen = maxWidth >= 600.dp || isTvMode

        if (selectedFullscreenCamera != null) {
            FullScreenPlayerScreen(
                camera = selectedFullscreenCamera!!,
                viewModel = viewModel,
                initialPtzOpen = openFullscreenWithPtz,
                onBack = { viewModel.selectFullscreenCamera(null) }
            )
        } else if (isWideScreen) {
            // Adaptive Two-Pane / Navigation Rail for Tablet, Desktop and Android TV
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = SurveillanceCardBg,
                    contentColor = Color.White,
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                    modifier = Modifier.testTag("surveillance_nav_rail")
                ) {
                    NavigationRailItem(
                        selected = activeScreen == "live",
                        onClick = { viewModel.navigateTo("live") },
                        icon = { Icon(Icons.Default.Videocam, contentDescription = stringResource(R.string.nav_live_desc)) },
                        label = { Text(stringResource(R.string.nav_live), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        ),
                        modifier = Modifier.testTag("nav_rail_live")
                    )

                    NavigationRailItem(
                        selected = activeScreen == "alerts",
                        onClick = { viewModel.navigateTo("alerts") },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (unreadAlertCount > 0) {
                                        Badge(containerColor = NeonAmber) { Text("$unreadAlertCount") }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.MotionPhotosOn, contentDescription = stringResource(R.string.nav_alerts_desc))
                            }
                        },
                        label = { Text(stringResource(R.string.nav_alerts), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        ),
                        modifier = Modifier.testTag("nav_rail_alerts")
                    )

                    NavigationRailItem(
                        selected = activeScreen == "schedules",
                        onClick = { viewModel.navigateTo("schedules") },
                        icon = { Icon(Icons.Default.CalendarMonth, contentDescription = stringResource(R.string.nav_schedules_desc)) },
                        label = { Text(stringResource(R.string.nav_plans), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        ),
                        modifier = Modifier.testTag("nav_rail_schedules")
                    )

                    NavigationRailItem(
                        selected = activeScreen == "recordings",
                        onClick = { viewModel.navigateTo("recordings") },
                        icon = { Icon(Icons.Default.VideoLibrary, contentDescription = stringResource(R.string.nav_recordings_desc)) },
                        label = { Text(stringResource(R.string.nav_clips), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        ),
                        modifier = Modifier.testTag("nav_rail_recordings")
                    )

                    NavigationRailItem(
                        selected = activeScreen == "cloud",
                        onClick = { viewModel.navigateTo("cloud") },
                        icon = { Icon(Icons.Default.Cloud, contentDescription = stringResource(R.string.nav_storage_desc)) },
                        label = { Text(stringResource(R.string.nav_storage), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        ),
                        modifier = Modifier.testTag("nav_rail_cloud")
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                ) {
                    ScreenContent(activeScreen = activeScreen, viewModel = viewModel)
                }
            }
        } else {
            // Mobile Scaffold with Bottom Navigation Bar
            Scaffold(
                containerColor = SurveillanceDarkBg,
                bottomBar = {
                    NavigationBar(
                        containerColor = SurveillanceCardBg,
                        tonalElevation = 8.dp,
                        modifier = Modifier.testTag("surveillance_bottom_nav")
                    ) {
                        NavigationBarItem(
                            selected = activeScreen == "live",
                            onClick = { viewModel.navigateTo("live") },
                            icon = { Icon(Icons.Default.Videocam, contentDescription = stringResource(R.string.nav_live_desc)) },
                            label = { Text(stringResource(R.string.nav_live), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                selectedTextColor = NeonCyan,
                                indicatorColor = NeonCyan,
                                unselectedIconColor = Color.Gray,
                                unselectedTextColor = Color.Gray
                            ),
                            modifier = Modifier.testTag("bottom_nav_live")
                        )

                        NavigationBarItem(
                            selected = activeScreen == "alerts",
                            onClick = { viewModel.navigateTo("alerts") },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (unreadAlertCount > 0) {
                                            Badge(containerColor = NeonAmber) { Text("$unreadAlertCount") }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.MotionPhotosOn, contentDescription = stringResource(R.string.nav_alerts_desc))
                                }
                            },
                            label = { Text(stringResource(R.string.nav_alerts), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                selectedTextColor = NeonCyan,
                                indicatorColor = NeonCyan,
                                unselectedIconColor = Color.Gray,
                                unselectedTextColor = Color.Gray
                            ),
                            modifier = Modifier.testTag("bottom_nav_alerts")
                        )

                        NavigationBarItem(
                            selected = activeScreen == "schedules",
                            onClick = { viewModel.navigateTo("schedules") },
                            icon = { Icon(Icons.Default.CalendarMonth, contentDescription = stringResource(R.string.nav_schedules_desc)) },
                            label = { Text(stringResource(R.string.nav_plans), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                selectedTextColor = NeonCyan,
                                indicatorColor = NeonCyan,
                                unselectedIconColor = Color.Gray,
                                unselectedTextColor = Color.Gray
                            ),
                            modifier = Modifier.testTag("bottom_nav_schedules")
                        )

                        NavigationBarItem(
                            selected = activeScreen == "recordings",
                            onClick = { viewModel.navigateTo("recordings") },
                            icon = { Icon(Icons.Default.VideoLibrary, contentDescription = stringResource(R.string.nav_recordings_desc)) },
                            label = { Text(stringResource(R.string.nav_clips), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                selectedTextColor = NeonCyan,
                                indicatorColor = NeonCyan,
                                unselectedIconColor = Color.Gray,
                                unselectedTextColor = Color.Gray
                            ),
                            modifier = Modifier.testTag("bottom_nav_recordings")
                        )

                        NavigationBarItem(
                            selected = activeScreen == "cloud",
                            onClick = { viewModel.navigateTo("cloud") },
                            icon = { Icon(Icons.Default.Cloud, contentDescription = stringResource(R.string.nav_storage_desc)) },
                            label = { Text(stringResource(R.string.nav_storage), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                selectedTextColor = NeonCyan,
                                indicatorColor = NeonCyan,
                                unselectedIconColor = Color.Gray,
                                unselectedTextColor = Color.Gray
                            ),
                            modifier = Modifier.testTag("bottom_nav_cloud")
                        )
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    ScreenContent(activeScreen = activeScreen, viewModel = viewModel)
                }
            }
        }

        // In-App Floating Windows Fallback (rendered when system-wide overlay service isn't active)
        if (!isSystemOverlayActive) {
            floatingWindows.sortedBy { it.zIndex }.forEach { winState ->
                val cam = cameras.find { it.id == winState.cameraId }
                if (cam != null) {
                    key(cam.id) {
                        val player = remember(cam.id) { viewModel.streamManager.getOrCreatePlayer(cam) }
                        val telemetry by viewModel.streamManager.getTelemetryFlow(cam.id).collectAsState()

                        FloatingCameraWindow(
                            camera = cam,
                            windowState = winState,
                            player = player,
                            telemetry = telemetry,
                            showTechnicalDetails = showTechnicalDetails,
                            containerWidthPx = containerWidthPx,
                            containerHeightPx = containerHeightPx,
                            safeTopPx = safeTopPx,
                            safeBottomPx = safeBottomPx,
                            onUpdatePosition = { x, y -> viewModel.updateFloatingPosition(cam.id, x, y) },
                            onSnapCorner = { corner -> viewModel.snapFloatingToCorner(cam.id, corner) },
                            onResize = { widthDp, ox, oy -> viewModel.resizeFloatingWindow(cam.id, widthDp, ox, oy) },
                            onRotate = { deg -> viewModel.setCameraRotation(cam.id, deg) },
                            onBringToFront = { viewModel.bringFloatingToFront(cam.id) },
                            onExpandToFullscreen = {
                                viewModel.selectFullscreenCamera(cam)
                                viewModel.closeFloatingCamera(cam.id)
                            },
                            onRetry = { viewModel.streamManager.retryPlayer(cam) },
                            onClose = { viewModel.closeFloatingCamera(cam.id) },
                            isMuted = isGlobalMuted,
                            onToggleMute = { viewModel.toggleGlobalMute() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ScreenContent(activeScreen: String, viewModel: CameraViewModel) {
    if (activeScreen != "live") {
        BackHandler { viewModel.navigateTo("live") }
    }

    when (activeScreen) {
        "live" -> LiveMultiCamScreen(
            viewModel = viewModel,
            onSelectCamera = { cam -> viewModel.selectFullscreenCamera(cam) }
        )
        "alerts" -> MotionAlertsScreen(
            viewModel = viewModel,
            onViewCamera = { camId ->
                val cam = viewModel.cameras.value.find { it.id == camId }
                if (cam != null) {
                    viewModel.selectFullscreenCamera(cam)
                } else {
                    viewModel.navigateTo("live")
                }
            }
        )
        "schedules" -> ScheduleScreen(viewModel = viewModel)
        "recordings" -> RecordingsScreen(viewModel = viewModel)
        "cloud" -> CloudStorageScreen(viewModel = viewModel)
    }
}
