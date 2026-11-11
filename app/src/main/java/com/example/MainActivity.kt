package com.example

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CameraEntity
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
import com.example.ui.theme.SurveillanceSurface
import com.example.ui.viewmodel.CameraViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()
    private var isSystemPiP by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                if (isSystemPiP) {
                    // System PiP minimal viewer
                    val cameras by viewModel.cameras.collectAsState()
                    val targetCamera = viewModel.selectedFullscreenCamera.collectAsState().value
                        ?: viewModel.floatingCamera.collectAsState().value
                        ?: cameras.firstOrNull()

                    if (targetCamera != null) {
                        val player = viewModel.streamManager.getOrCreatePlayer(targetCamera)
                        val telemetry by viewModel.streamManager.getTelemetryFlow(targetCamera.id).collectAsState()
                        RtspVideoPlayer(
                            camera = targetCamera,
                            player = player,
                            telemetry = telemetry,
                            showControlsOverlay = false,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    OmniViewApp(viewModel = viewModel)
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
    val floatingCamera by viewModel.floatingCamera.collectAsState()
    val unreadAlertCount by viewModel.unreadAlertCount.collectAsState()
    val isTvMode by viewModel.isTvMode.collectAsState()

    // Full screen camera view takes priority
    if (selectedFullscreenCamera != null) {
        FullScreenPlayerScreen(
            camera = selectedFullscreenCamera!!,
            viewModel = viewModel,
            onBack = { viewModel.selectFullscreenCamera(null) }
        )
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(SurveillanceDarkBg)
    ) {
        val isWideScreen = maxWidth >= 600.dp || isTvMode

        if (isWideScreen) {
            // Adaptive Two-Pane / Navigation Rail for Tablet, Desktop and Android TV
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = SurveillanceCardBg,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("surveillance_nav_rail")
                ) {
                    NavigationRailItem(
                        selected = activeScreen == "live",
                        onClick = { viewModel.navigateTo("live") },
                        icon = { Icon(Icons.Default.Videocam, contentDescription = "Live Cameras") },
                        label = { Text("Live", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
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
                                Icon(Icons.Default.MotionPhotosOn, contentDescription = "Alerts")
                            }
                        },
                        label = { Text("Alerts", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
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
                        icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "Schedules") },
                        label = { Text("Schedules", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
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
                        icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "Recordings") },
                        label = { Text("Clips", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
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
                        icon = { Icon(Icons.Default.Cloud, contentDescription = "Cloud Storage") },
                        label = { Text("Cloud", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
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

                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
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
                            icon = { Icon(Icons.Default.Videocam, contentDescription = "Live Cameras") },
                            label = { Text("Live", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
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
                                    Icon(Icons.Default.MotionPhotosOn, contentDescription = "Alerts")
                                }
                            },
                            label = { Text("Alerts", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
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
                            icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "Schedules") },
                            label = { Text("Plans", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
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
                            icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "Recordings") },
                            label = { Text("Clips", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
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
                            icon = { Icon(Icons.Default.Cloud, contentDescription = "Cloud Storage") },
                            label = { Text("Cloud", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
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

        // Overlay: Interactive In-App Movable and Resizable Floating Camera Window!
        floatingCamera?.let { cam ->
            val player = viewModel.streamManager.getOrCreatePlayer(cam)
            val telemetry by viewModel.streamManager.getTelemetryFlow(cam.id).collectAsState()

            FloatingCameraWindow(
                camera = cam,
                player = player,
                telemetry = telemetry,
                onClose = { viewModel.setFloatingCamera(null) },
                onExpandToFullscreen = {
                    viewModel.selectFullscreenCamera(cam)
                    viewModel.setFloatingCamera(null)
                },
                onSwitchCamera = { viewModel.cycleFloatingCamera() },
                modifier = Modifier
            )
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
