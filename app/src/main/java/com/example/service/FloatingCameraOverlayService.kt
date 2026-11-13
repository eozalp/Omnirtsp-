package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.R
import com.example.data.model.CameraEntity
import com.example.engine.RtspStreamManager
import com.example.i18n.AppLocaleManager
import com.example.ui.components.CompactFloatingRotationBar
import com.example.ui.components.CornerResizeGripsOverlay
import com.example.ui.components.ResizeCorner
import com.example.ui.components.RtspVideoPlayer
import com.example.ui.components.baseWidthFromActualWidthDp
import com.example.ui.components.computeEffectiveContainerAspectRatio
import com.example.ui.components.normalizeRotationDegrees
import com.example.ui.components.resizableCameraWindow
import com.example.ui.components.resolveFloatingWindowSizeDp
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.viewmodel.CornerPosition
import com.example.ui.viewmodel.FloatingWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground Service hosting resizable, draggable system overlay windows (TYPE_APPLICATION_OVERLAY)
 * so multiple RTSP camera streams stay visible on top of other apps such as YouTube or TV streams.
 */
class FloatingCameraOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var streamManager: RtspStreamManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    private data class AttachedOverlay(
        val view: ComposeView,
        val params: WindowManager.LayoutParams,
        val lifecycleOwner: OverlayLifecycleOwner
    )

    private val attachedOverlays = mutableMapOf<String, AttachedOverlay>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        FloatingOverlayStateHolder.initAudioSettings(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        streamManager = RtspStreamManager(applicationContext)
        FloatingOverlayStateHolder.setSystemOverlayActive(true)
        acquireWakeLock()
        startForegroundNotification()
        observeFloatingWindows()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniView::OverlayWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            FloatingOverlayStateHolder.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                AppLocaleManager.getString(this, R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = AppLocaleManager.getString(this@FloatingCameraOverlayService, R.string.overlay_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val closeAllIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingCameraOverlayService::class.java).apply {
                action = ACTION_STOP_ALL
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(AppLocaleManager.getString(this, R.string.overlay_notif_title))
            .setContentText(AppLocaleManager.getString(this, R.string.overlay_notif_text))
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, AppLocaleManager.getString(this, R.string.overlay_notif_close_all), closeAllIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeFloatingWindows() {
        serviceScope.launch {
            combine(
                FloatingOverlayStateHolder.floatingWindows,
                FloatingOverlayStateHolder.cameras
            ) { windows, cameras ->
                windows to cameras
            }.collect { (windows, cameras) ->
                if (!Settings.canDrawOverlays(this@FloatingCameraOverlayService) || windows.isEmpty()) {
                    removeAllOverlays()
                    stopSelf()
                    return@collect
                }

                val desiredIds = windows.map { it.cameraId }.toSet()
                val toRemove = attachedOverlays.keys.filter { it !in desiredIds }
                toRemove.forEach { removeOverlay(it) }

                windows.sortedBy { it.zIndex }.forEach { winState ->
                    val cam = cameras.find { it.id == winState.cameraId } ?: return@forEach
                    val existing = attachedOverlays[cam.id]
                    if (existing == null) {
                        addOverlayWindow(cam, winState)
                    } else {
                        syncOverlayParamsFromState(existing, winState)
                    }
                }
            }
        }
    }

    private fun getScreenBoundsPx(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun dpToPx(dp: Float): Int =
        (dp * resources.displayMetrics.density).roundToInt()

    private fun calculateWindowGeometry(winState: FloatingWindowState): Triple<Int, Int, Pair<Int, Int>> {
        val (screenW, screenH) = getScreenBoundsPx()
        val effAspect = computeEffectiveContainerAspectRatio(16f / 9f, winState.rotationDegrees)
        val maxWidthDp = (screenW / resources.displayMetrics.density * 0.85f).coerceAtLeast(140f)
        val maxHeightDp = (screenH / resources.displayMetrics.density * 0.80f).coerceAtLeast(140f)
        val (windowWidthDp, windowHeightDp) = resolveFloatingWindowSizeDp(
            baseWidthDp = winState.widthDp,
            effectiveAspect = effAspect,
            maxWidthDp = maxWidthDp.coerceAtMost(540f),
            maxHeightDp = maxHeightDp.coerceAtMost(540f)
        )
        val widthPx = dpToPx(windowWidthDp)
        val heightPx = dpToPx(windowHeightDp)
        val marginPx = dpToPx(12f)
        val topSafePx = dpToPx(36f)
        val bottomSafePx = dpToPx(24f)

        val minX = marginPx
        val maxX = (screenW - widthPx - marginPx).coerceAtLeast(minX)
        val minY = topSafePx
        val maxY = (screenH - heightPx - bottomSafePx).coerceAtLeast(minY)

        val x: Int
        val y: Int
        when (winState.cornerSnap) {
            CornerPosition.TOP_LEFT -> {
                x = minX; y = minY
            }
            CornerPosition.TOP_RIGHT -> {
                x = maxX; y = minY
            }
            CornerPosition.BOTTOM_LEFT -> {
                x = minX; y = maxY
            }
            CornerPosition.BOTTOM_RIGHT -> {
                x = maxX; y = maxY
            }
            null -> {
                x = (winState.offsetX?.roundToInt() ?: minX).coerceIn(minX, maxX)
                y = (winState.offsetY?.roundToInt() ?: minY).coerceIn(minY, maxY)
            }
        }
        return Triple(widthPx, heightPx, x to y)
    }

    private fun addOverlayWindow(camera: CameraEntity, winState: FloatingWindowState) {
        val (widthPx, heightPx, pos) = calculateWindowGeometry(winState)
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            pos.first,
            pos.second,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val lifecycleOwner = OverlayLifecycleOwner().apply { onCreate() }
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val selectedLanguageTag by AppLocaleManager.selectedLanguageTag.collectAsState()
                val baseConfig = LocalConfiguration.current
                val localizedConfig = remember(selectedLanguageTag, baseConfig) {
                    AppLocaleManager.applyLocaleToResources(this@FloatingCameraOverlayService, selectedLanguageTag)
                }

                CompositionLocalProvider(
                    LocalConfiguration provides localizedConfig
                ) {
                    MyApplicationTheme {
                        val windows by FloatingOverlayStateHolder.floatingWindows.collectAsState()
                        val showTech by FloatingOverlayStateHolder.showTechnicalDetails.collectAsState()
                        val currentState = windows.find { it.cameraId == camera.id } ?: winState

                        SystemOverlayCameraCard(
                        camera = camera,
                        windowState = currentState,
                        streamManager = streamManager,
                        showTechnicalDetails = showTech,
                        onDragMove = { dx, dy ->
                            val (screenW, screenH) = getScreenBoundsPx()
                            val margin = dpToPx(8f)
                            val newX = (params.x + dx.roundToInt()).coerceIn(margin, (screenW - params.width - margin).coerceAtLeast(margin))
                            val newY = (params.y + dy.roundToInt()).coerceIn(margin, (screenH - params.height - margin).coerceAtLeast(margin))
                            params.x = newX
                            params.y = newY
                            safeUpdateViewLayout(this, params)
                            FloatingOverlayStateHolder.updatePosition(camera.id, newX.toFloat(), newY.toFloat())
                        },
                        onCornerResize = { _: ResizeCorner, widthDeltaDp, shiftX, shiftY ->
                            val (screenW, screenH) = getScreenBoundsPx()
                            val latestState = FloatingOverlayStateHolder.floatingWindows.value
                                .find { it.cameraId == camera.id } ?: currentState
                            val effAspect = computeEffectiveContainerAspectRatio(16f / 9f, latestState.rotationDegrees)
                            val maxWidthDp = (screenW / resources.displayMetrics.density * 0.85f).coerceAtLeast(140f)
                            val maxHeightDp = (screenH / resources.displayMetrics.density * 0.80f).coerceAtLeast(140f)
                            val currentWidthDp = params.width / resources.displayMetrics.density
                            val targetActualW = (currentWidthDp + widthDeltaDp).coerceAtLeast(96f)
                            val newBaseWidthDp = baseWidthFromActualWidthDp(targetActualW, effAspect)
                            val (resolvedWidthDp, resolvedHeightDp) = resolveFloatingWindowSizeDp(
                                baseWidthDp = newBaseWidthDp,
                                effectiveAspect = effAspect,
                                maxWidthDp = maxWidthDp.coerceAtMost(540f),
                                maxHeightDp = maxHeightDp.coerceAtMost(540f)
                            )
                            val newWidthPx = dpToPx(resolvedWidthDp)
                            val newHeightPx = dpToPx(resolvedHeightDp)

                            val margin = dpToPx(8f)
                            val newX = (params.x + shiftX.roundToInt()).coerceIn(margin, (screenW - newWidthPx - margin).coerceAtLeast(margin))
                            val newY = (params.y + shiftY.roundToInt()).coerceIn(margin, (screenH - newHeightPx - margin).coerceAtLeast(margin))

                            params.width = newWidthPx
                            params.height = newHeightPx
                            params.x = newX
                            params.y = newY
                            safeUpdateViewLayout(this, params)
                            FloatingOverlayStateHolder.resizeWindow(camera.id, newBaseWidthDp, newX.toFloat(), newY.toFloat())
                        },
                        onSnapCorner = { corner ->
                            FloatingOverlayStateHolder.snapToCorner(camera.id, corner)
                        },
                        onStepResize = { newWidthDp ->
                            FloatingOverlayStateHolder.resizeWindow(camera.id, newWidthDp)
                        },
                        onRotate = { newDegrees ->
                            FloatingOverlayStateHolder.setRotation(camera.id, newDegrees)
                        },
                        onOpenApp = {
                            startActivity(
                                Intent(this@FloatingCameraOverlayService, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                }
                            )
                        },
                        onClose = {
                            FloatingOverlayStateHolder.closeFloatingCamera(camera.id)
                        }
                    )
                    }
                }
            }
        }

        try {
            windowManager.addView(composeView, params)
            attachedOverlays[camera.id] = AttachedOverlay(composeView, params, lifecycleOwner)
        } catch (_: Exception) {
            lifecycleOwner.onDestroy()
        }
    }

    private fun syncOverlayParamsFromState(overlay: AttachedOverlay, winState: FloatingWindowState) {
        val (widthPx, heightPx, pos) = calculateWindowGeometry(winState)
        if (overlay.params.width != widthPx ||
            overlay.params.height != heightPx ||
            overlay.params.x != pos.first ||
            overlay.params.y != pos.second
        ) {
            overlay.params.width = widthPx
            overlay.params.height = heightPx
            overlay.params.x = pos.first
            overlay.params.y = pos.second
            safeUpdateViewLayout(overlay.view, overlay.params)
        }
    }

    private fun safeUpdateViewLayout(view: ComposeView, params: WindowManager.LayoutParams) {
        if (view.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }
    }

    private fun removeOverlay(cameraId: String) {
        attachedOverlays.remove(cameraId)?.let { entry ->
            try {
                if (entry.view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(entry.view)
                }
            } catch (_: Exception) {}
            entry.lifecycleOwner.onDestroy()
        }
        streamManager.releasePlayer(cameraId)
    }

    private fun removeAllOverlays() {
        val keys = attachedOverlays.keys.toList()
        keys.forEach { removeOverlay(it) }
    }

    override fun onDestroy() {
        releaseWakeLock()
        removeAllOverlays()
        FloatingOverlayStateHolder.setSystemOverlayActive(false)
        streamManager.releaseAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "omniview_overlay_channel"
        private const val NOTIFICATION_ID = 4401
        const val ACTION_SYNC = "com.example.action.SYNC_OVERLAY"
        const val ACTION_STOP_ALL = "com.example.action.STOP_ALL_OVERLAYS"

        fun startOrSync(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingCameraOverlayService::class.java).apply {
                action = ACTION_SYNC
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingCameraOverlayService::class.java))
        }
    }
}

@Composable
private fun SystemOverlayCameraCard(
    camera: CameraEntity,
    windowState: FloatingWindowState,
    streamManager: RtspStreamManager,
    showTechnicalDetails: Boolean,
    onDragMove: (Float, Float) -> Unit,
    onCornerResize: (ResizeCorner, Float, Float, Float) -> Unit,
    onSnapCorner: (CornerPosition) -> Unit,
    onStepResize: (Float) -> Unit,
    onRotate: (Float) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit
) {
    val player = remember(camera.id) { streamManager.getOrCreatePlayer(camera) }
    val telemetry by streamManager.getTelemetryFlow(camera.id).collectAsState()
    val isGlobalMuted by FloatingOverlayStateHolder.isGlobalMuted.collectAsState()
    val overlayContext = LocalContext.current
    var controlsVisible by remember { mutableStateOf(true) }
    var isRotateMode by remember { mutableStateOf(false) }

    LaunchedEffect(controlsVisible, isRotateMode, windowState.widthDp, windowState.cornerSnap, windowState.rotationDegrees) {
        if (controlsVisible && !isRotateMode) {
            delay(4000)
            controlsVisible = false
        }
    }

    val effAspect = computeEffectiveContainerAspectRatio(16f / 9f, windowState.rotationDegrees)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, if (controlsVisible) NeonCyan else Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .resizableCameraWindow(
                key = camera.id,
                aspectRatio = effAspect,
                onInteractionStart = { controlsVisible = true },
                onDragMove = onDragMove,
                onCornerResize = onCornerResize
            ),
        color = SurveillanceCardBg
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            RtspVideoPlayer(
                camera = camera,
                player = player,
                telemetry = telemetry,
                rotationDegrees = windowState.rotationDegrees,
                showControlsOverlay = true,
                showTechnicalDetails = showTechnicalDetails,
                compactMode = true,
                onRetry = { streamManager.retryPlayer(camera) },
                onClick = { controlsVisible = !controlsVisible },
                modifier = Modifier.fillMaxSize()
            )

            CornerResizeGripsOverlay(isHighlighted = controlsVisible)

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                ) {
                    // Top-Right Actions
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onOpenApp,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.OpenInFull,
                                contentDescription = stringResource(R.string.floating_open_app),
                                tint = NeonCyan,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.common_close),
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    // Bottom Corner-Snap, Step-Resize & Rotation Pill
                    if (isRotateMode) {
                        CompactFloatingRotationBar(
                            rotationDegrees = windowState.rotationDegrees,
                            onRotationChange = onRotate,
                            onDone = { isRotateMode = false },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 6.dp, bottom = 6.dp, end = 20.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.72f))
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OverlayChip("↖", windowState.cornerSnap == CornerPosition.TOP_LEFT) {
                                onSnapCorner(CornerPosition.TOP_LEFT)
                            }
                            OverlayChip("↗", windowState.cornerSnap == CornerPosition.TOP_RIGHT) {
                                onSnapCorner(CornerPosition.TOP_RIGHT)
                            }
                            OverlayChip("↙", windowState.cornerSnap == CornerPosition.BOTTOM_LEFT) {
                                onSnapCorner(CornerPosition.BOTTOM_LEFT)
                            }
                            OverlayChip("↘", windowState.cornerSnap == CornerPosition.BOTTOM_RIGHT) {
                                onSnapCorner(CornerPosition.BOTTOM_RIGHT)
                            }
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onStepResize(windowState.widthDp - 40f) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.floating_smaller), tint = Color.White, modifier = Modifier.size(13.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onStepResize(windowState.widthDp + 40f) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.floating_larger), tint = NeonCyan, modifier = Modifier.size(13.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (normalizeRotationDegrees(windowState.rotationDegrees) != 0f) {
                                            NeonCyan.copy(alpha = 0.28f)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .clickable { isRotateMode = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.RotateRight, contentDescription = stringResource(R.string.live_rotate_video), tint = NeonCyan, modifier = Modifier.size(13.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isGlobalMuted) Color(0xFF442222) else Color.Transparent)
                                    .clickable { FloatingOverlayStateHolder.toggleGlobalMuted(overlayContext) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isGlobalMuted) {
                                        Icons.AutoMirrored.Filled.VolumeOff
                                    } else {
                                        Icons.AutoMirrored.Filled.VolumeUp
                                    },
                                    contentDescription = stringResource(R.string.fullscreen_audio_toggle),
                                    tint = if (isGlobalMuted) Color.LightGray else NeonCyan,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayChip(symbol: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) NeonCyan else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = if (selected) Color.Black else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}
