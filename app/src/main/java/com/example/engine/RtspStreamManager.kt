package com.example.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.data.model.CameraEntity
import com.example.data.model.RtspTransport
import com.example.data.model.StreamTelemetry
import com.example.service.FloatingOverlayStateHolder
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
class RtspStreamManager(private val context: Context) {

    data class StreamHealthEvaluation(
        val healthyProgress: Boolean,
        val stallIncrement: Int,
        val isLive: Boolean,
        val isReconnecting: Boolean
    )

    data class CapturedVideoFrame(
        val bitmap: Bitmap?,
        val renderedFrameToken: Int = -1,
        val unchanged: Boolean = false,
        val isFromHeadless: Boolean = false
    )

    private val playerMap = ConcurrentHashMap<String, ExoPlayer>()
    private val playerFlowMap = ConcurrentHashMap<String, MutableStateFlow<ExoPlayer?>>()
    private val telemetryMap = ConcurrentHashMap<String, MutableStateFlow<StreamTelemetry>>()
    private val activeUrlMap = ConcurrentHashMap<String, String>()
    private val activeCameraMap = ConcurrentHashMap<String, CameraEntity>()
    private val hasRenderedFrameMap = ConcurrentHashMap<String, Boolean>()
    private val preparedAtMsMap = ConcurrentHashMap<String, Long>()
    private val attachedSurfaceCountMap = ConcurrentHashMap<String, Int>()
    private val surfaceGraceUntilMsMap = ConcurrentHashMap<String, Long>()
    private val stalledSecondsMap = ConcurrentHashMap<String, Int>()
    private val lastRetryAtMap = ConcurrentHashMap<String, Long>()
    private val lastRenderedFramesMap = ConcurrentHashMap<String, Int>()
    private val lastPlaybackPosMap = ConcurrentHashMap<String, Long>()
    private val recoveryAttemptsMap = ConcurrentHashMap<String, Int>()
    private val healthySecondsMap = ConcurrentHashMap<String, Int>()
    private val lastVideoSizeMap = ConcurrentHashMap<String, Pair<Int, Int>>()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var telemetryJob: Job? = null

    init {
        FloatingOverlayStateHolder.initAudioSettings(context)
        observeGlobalMuteState()
        startTelemetryLoop()
    }

    private fun observeGlobalMuteState() {
        scope.launch {
            FloatingOverlayStateHolder.isGlobalMuted.collect { isMuted ->
                val targetVolume = if (isMuted) 0f else 1f
                for ((_, player) in playerMap) {
                    try {
                        player.volume = targetVolume
                    } catch (_: Exception) {}
                }
            }
        }
    }

    @Volatile
    var isAppInForeground: Boolean = true
        private set

    private val headlessGrabbers = ConcurrentHashMap<String, HeadlessGlFrameGrabber>()
    private val grabberBoundPlayerMap = ConcurrentHashMap<String, WeakReference<ExoPlayer>>()
    private val recordingCameraIds = ConcurrentHashMap<String, Boolean>()
    private val uiCaptureInFlight = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()
    private val lastGrabberFrameCounts = ConcurrentHashMap<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun warmUpStreams(cameras: List<CameraEntity>) {
        if (cameras.isEmpty()) return
        kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            cameras.forEach { cam ->
                runCatching {
                    val player = getOrCreatePlayer(cam)
                    if (!isForegroundCaptureActive(cam.id) && recordingCameraIds[cam.id] == true) {
                        val (vw, vh) = lastVideoSizeMap[cam.id] ?: (640 to 368)
                        ensureHeadlessGrabberLocked(cam.id, player, vw, vh)
                    }
                }
            }
        }
    }

    fun setAppInForeground(inForeground: Boolean) {
        if (isAppInForeground == inForeground) return
        isAppInForeground = inForeground
        if (inForeground) {
            for ((cameraId, tvRef) in activeTextureViews) {
                val tv = tvRef.get()
                if (tv != null && tv.isAvailable && (attachedSurfaceCountMap[cameraId] ?: 0) > 0) {
                    releaseHeadlessGrabber(cameraId)
                    try {
                        playerMap[cameraId]?.setVideoTextureView(tv)
                    } catch (_: Exception) {}
                    surfaceGraceUntilMsMap[cameraId] = System.currentTimeMillis() + SURFACE_SWITCH_GRACE_MS
                }
            }
        } else {
            for ((cameraId, player) in playerMap) {
                if (recordingCameraIds[cameraId] == true || headlessGrabbers.containsKey(cameraId)) {
                    val vw = player.videoSize.width.takeIf { it > 0 } ?: 640
                    val vh = player.videoSize.height.takeIf { it > 0 } ?: 368
                    ensureHeadlessGrabberLocked(cameraId, player, vw, vh)
                    surfaceGraceUntilMsMap[cameraId] = System.currentTimeMillis() + SURFACE_SWITCH_GRACE_MS
                }
            }
        }
    }

    fun onSurfaceAttached(cameraId: String) {
        attachedSurfaceCountMap[cameraId] = (attachedSurfaceCountMap[cameraId] ?: 0) + 1
        surfaceGraceUntilMsMap[cameraId] = System.currentTimeMillis() + SURFACE_SWITCH_GRACE_MS
        stalledSecondsMap[cameraId] = 0
        releaseHeadlessGrabber(cameraId)
    }

    fun onSurfaceDetached(cameraId: String) {
        val next = (attachedSurfaceCountMap[cameraId] ?: 0) - 1
        if (next <= 0) {
            attachedSurfaceCountMap.remove(cameraId)
        } else {
            attachedSurfaceCountMap[cameraId] = next
        }
    }

    private fun releaseHeadlessGrabber(cameraId: String) {
        grabberBoundPlayerMap.remove(cameraId)
        headlessGrabbers.remove(cameraId)?.let { grabber ->
            val surface = grabber.surface
            if (surface != null) {
                try {
                    playerMap[cameraId]?.clearVideoSurface(surface)
                } catch (_: Exception) {}
            }
            grabber.release()
        }
    }

    fun reattachTextureView(cameraId: String, textureView: TextureView) {
        activeTextureViews[cameraId] = WeakReference(textureView)
        releaseHeadlessGrabber(cameraId)
        surfaceGraceUntilMsMap[cameraId] = System.currentTimeMillis() + SURFACE_SWITCH_GRACE_MS
        if (textureView.isAvailable) {
            try {
                playerMap[cameraId]?.setVideoTextureView(textureView)
            } catch (_: Exception) {}
        }
    }

    fun onPlayerViewReleased(cameraId: String, releasedTextureView: TextureView?, releasedFromPlayer: ExoPlayer?) {
        if (releasedTextureView != null) {
            unregisterTextureView(cameraId, releasedTextureView)
        }
        val activePlayer = playerMap[cameraId] ?: return
        val remainingTv = activeTextureViews[cameraId]?.get()
        if (isAppInForeground &&
            (attachedSurfaceCountMap[cameraId] ?: 0) > 0 &&
            remainingTv != null &&
            remainingTv !== releasedTextureView &&
            remainingTv.isAvailable
        ) {
            runCatching { activePlayer.setVideoTextureView(remainingTv) }
            surfaceGraceUntilMsMap[cameraId] = System.currentTimeMillis() + SURFACE_SWITCH_GRACE_MS
            return
        }
        val grabber = headlessGrabbers[cameraId]
        if (grabber != null && grabber.isReady) {
            val surface = grabber.surface
            if (surface != null) {
                runCatching { activePlayer.setVideoSurface(surface) }
                grabberBoundPlayerMap[cameraId] = WeakReference(activePlayer)
                surfaceGraceUntilMsMap[cameraId] = System.currentTimeMillis() + SURFACE_SWITCH_GRACE_MS
            }
        } else if (recordingCameraIds[cameraId] == true) {
            val vw = activePlayer.videoSize.width.takeIf { it > 0 } ?: 640
            val vh = activePlayer.videoSize.height.takeIf { it > 0 } ?: 368
            ensureHeadlessGrabberLocked(cameraId, activePlayer, vw, vh)
            surfaceGraceUntilMsMap[cameraId] = System.currentTimeMillis() + SURFACE_SWITCH_GRACE_MS
        }
    }

    fun getPlayerFlow(cameraId: String): StateFlow<ExoPlayer?> {
        return playerFlowMap.getOrPut(cameraId) {
            MutableStateFlow(playerMap[cameraId])
        }.asStateFlow()
    }

    fun getOrCreatePlayer(camera: CameraEntity): ExoPlayer {
        activeCameraMap[camera.id] = camera
        val existing = playerMap[camera.id]
        val currentSignature = computeStreamConfigSignature(camera)
        val targetVolume = if (FloatingOverlayStateHolder.isGlobalMuted.value) 0f else 1f

        if (existing != null) {
            if (existing.volume != targetVolume) {
                existing.volume = targetVolume
            }
            val previousSignature = activeUrlMap[camera.id]
            if (previousSignature != null && previousSignature != currentSignature) {
                activeUrlMap[camera.id] = currentSignature
                hasRenderedFrameMap[camera.id] = false
                preparedAtMsMap[camera.id] = System.currentTimeMillis()
                stalledSecondsMap[camera.id] = 0
                lastRenderedFramesMap.remove(camera.id)
                lastPlaybackPosMap.remove(camera.id)
                recoveryAttemptsMap[camera.id] = 0
                try {
                    existing.stop()
                    existing.clearMediaItems()
                    val mediaSource = createMediaSource(camera, attempt = 0)
                    existing.setMediaSource(mediaSource)
                    existing.repeatMode = if (isLiveRtspCamera(camera)) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
                    existing.prepare()
                    existing.playWhenReady = true
                } catch (t: Throwable) {
                    telemetryMap[camera.id]?.let { flow ->
                        flow.value = flow.value.copy(
                            isLive = false,
                            isReconnecting = false,
                            hasError = true,
                            errorMessage = t.message ?: "Failed to open stream"
                        )
                    }
                }
            }
            playerFlowMap.getOrPut(camera.id) { MutableStateFlow(existing) }.value = existing
            return existing
        }

        return createAndRegisterPlayer(camera)
    }

    private fun createAndRegisterPlayer(camera: CameraEntity, attempt: Int = 0): ExoPlayer {
        val currentSignature = computeStreamConfigSignature(camera)
        val targetVolume = if (FloatingOverlayStateHolder.isGlobalMuted.value) 0f else 1f
        val isLiveRtsp = isLiveRtspCamera(camera)

        activeCameraMap[camera.id] = camera
        activeUrlMap[camera.id] = currentSignature
        hasRenderedFrameMap[camera.id] = false
        preparedAtMsMap[camera.id] = System.currentTimeMillis()
        stalledSecondsMap[camera.id] = 0
        healthySecondsMap[camera.id] = 0
        lastRenderedFramesMap.remove(camera.id)
        lastPlaybackPosMap.remove(camera.id)

        val surveillanceAudioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1_500,
                /* maxBufferMs = */ 8_000,
                /* bufferForPlaybackMs = */ 300,
                /* bufferForPlaybackAfterRebufferMs = */ 600
            )
            .setTargetBufferBytes(MAX_LIVE_BUFFER_BYTES_PER_CAMERA)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(surveillanceAudioAttrs, /* handleAudioFocus = */ false)
            .build()
            .apply {
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                setWakeMode(C.WAKE_MODE_NETWORK)
                volume = targetVolume
            }

        val flow = telemetryMap.getOrPut(camera.id) {
            MutableStateFlow(StreamTelemetry())
        }

        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    lastVideoSizeMap[camera.id] = videoSize.width to videoSize.height
                    flow.value = flow.value.copy(
                        resolution = "${videoSize.width}x${videoSize.height}"
                    )
                }
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFrameMap[camera.id] = true
                stalledSecondsMap[camera.id] = 0
                recoveryAttemptsMap[camera.id] = 0
                flow.value = flow.value.copy(
                    isLive = true,
                    isReconnecting = false,
                    hasError = false,
                    errorMessage = null
                )
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (isLiveRtsp && player.playbackState == Player.STATE_READY) {
                    val hasVideoTrack = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                    if (!hasVideoTrack && hasRenderedFrameMap[camera.id] == true) {
                        flow.value = flow.value.copy(
                            isLive = false,
                            isReconnecting = true
                        )
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        val rendered = hasRenderedFrameMap[camera.id] == true
                        flow.value = flow.value.copy(
                            isLive = rendered,
                            isReconnecting = false,
                            hasError = false,
                            errorMessage = null
                        )
                    }
                    Player.STATE_BUFFERING -> {
                        val isRetrying = (recoveryAttemptsMap[camera.id] ?: 0) > 0
                        flow.value = flow.value.copy(
                            isLive = false,
                            isReconnecting = isRetrying
                        )
                    }
                    Player.STATE_ENDED -> {
                        val latestCam = activeCameraMap[camera.id] ?: camera
                        if (isLiveRtsp) {
                            // Live RTSP reached END_OF_SOURCE (e.g. camera 30s session drop):
                            // never loop a closed period; rebuild session cleanly after cooldown.
                            hasRenderedFrameMap[camera.id] = false
                            flow.value = flow.value.copy(
                                isLive = false,
                                isReconnecting = true,
                                latencyMs = 0L
                            )
                            val now = System.currentTimeMillis()
                            val lastRetry = lastRetryAtMap[camera.id] ?: 0L
                            if (now - lastRetry >= EOS_RETRY_COOLDOWN_MS) {
                                recoverCameraStream(latestCam)
                            }
                        } else if (!flow.value.hasError) {
                            try {
                                player.seekToDefaultPosition()
                                player.playWhenReady = true
                                player.prepare()
                            } catch (_: Exception) {}
                        }
                    }
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    flow.value = flow.value.copy(isLive = false)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                hasRenderedFrameMap[camera.id] = false
                lastRetryAtMap[camera.id] = System.currentTimeMillis()
                val cause = error.cause
                val msg = when {
                    cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException ->
                        "HTTP ${cause.responseCode} Access Denied / Stream Unavailable"
                    cause is java.net.UnknownHostException -> "Network Offline / Unreachable Host"
                    cause is java.net.ConnectException -> "Connection Refused (RTSP Port 554 Closed)"
                    cause is RtspMediaSource.RtspPlaybackException -> "RTSP Feed Error: ${cause.message}"
                    else -> error.errorCodeName
                }
                flow.value = flow.value.copy(
                    isLive = false,
                    isReconnecting = false,
                    hasError = true,
                    errorMessage = msg,
                    codec = msg
                )
            }
        })

        try {
            val mediaSource = createMediaSource(camera, attempt)
            player.setMediaSource(mediaSource)
            player.repeatMode = if (isLiveRtsp) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
            player.prepare()
            player.playWhenReady = true
        } catch (t: Throwable) {
            flow.value = flow.value.copy(
                isLive = false,
                isReconnecting = false,
                hasError = true,
                errorMessage = t.message ?: "Failed to open stream"
            )
        }

        playerMap[camera.id] = player
        playerFlowMap.getOrPut(camera.id) { MutableStateFlow(player) }.value = player

        // Re-bind the active UI TextureView or background HeadlessGlFrameGrabber immediately
        // whenever ExoPlayer is rebuilt (e.g. watchdog recovery), so decoding never stalls.
        val activeTv = activeTextureViews[camera.id]?.get()
        if (isAppInForeground &&
            (attachedSurfaceCountMap[camera.id] ?: 0) > 0 &&
            activeTv != null &&
            activeTv.isAvailable
        ) {
            runCatching { player.setVideoTextureView(activeTv) }
        } else {
            val existingGrabber = headlessGrabbers[camera.id]
            if (existingGrabber != null && existingGrabber.isReady) {
                existingGrabber.surface?.let { s ->
                    runCatching { player.setVideoSurface(s) }
                    grabberBoundPlayerMap[camera.id] = WeakReference(player)
                }
            } else if (recordingCameraIds[camera.id] == true) {
                ensureHeadlessGrabberLocked(camera.id, player, 640, 368)
            }
        }
        return player
    }

    fun retryPlayer(camera: CameraEntity) {
        recoveryAttemptsMap[camera.id] = 0
        recoverCameraStream(camera, forceRebuild = false)
    }

    private fun recoverCameraStream(camera: CameraEntity, forceRebuild: Boolean = false) {
        activeCameraMap[camera.id] = camera
        val player = playerMap[camera.id]
        val flow = telemetryMap[camera.id]
        val attempt = (recoveryAttemptsMap[camera.id] ?: 0)
        val now = System.currentTimeMillis()
        recoveryAttemptsMap[camera.id] = attempt + 1
        stalledSecondsMap[camera.id] = 0
        healthySecondsMap[camera.id] = 0
        hasRenderedFrameMap[camera.id] = false
        preparedAtMsMap[camera.id] = now
        lastRenderedFramesMap.remove(camera.id)
        lastPlaybackPosMap.remove(camera.id)
        lastRetryAtMap[camera.id] = now

        flow?.value = flow.value.copy(
            isLive = false,
            isReconnecting = true,
            hasError = false,
            errorMessage = null,
            latencyMs = 0L
        )

        if (player == null || forceRebuild || attempt >= 5) {
            // Release old ExoPlayer first, then drop stale headless GL grabber so the rebuilt
            // player never collides with an already-connected MediaCodec BufferQueue surface.
            playerMap.remove(camera.id)?.let { old ->
                try {
                    old.clearVideoSurface()
                    old.stop()
                    old.release()
                } catch (_: Exception) {}
            }
            if (!isForegroundCaptureActive(camera.id)) {
                releaseHeadlessGrabber(camera.id)
            }
            createAndRegisterPlayer(camera, attempt = attempt)
            return
        }

        val isLiveRtsp = isLiveRtspCamera(camera)
        try {
            player.volume = if (FloatingOverlayStateHolder.isGlobalMuted.value) 0f else 1f
            player.stop()
            player.clearMediaItems()
            val mediaSource = createMediaSource(camera, attempt)
            player.setMediaSource(mediaSource)
            player.repeatMode = if (isLiveRtsp) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
            player.prepare()
            player.playWhenReady = true
        } catch (t: Throwable) {
            flow?.value = flow.value.copy(
                isLive = false,
                isReconnecting = false,
                hasError = true,
                errorMessage = t.message ?: "Reconnect failed"
            )
        }
    }

    fun getPlayer(cameraId: String): ExoPlayer? = playerMap[cameraId]

    fun getTelemetryFlow(cameraId: String): StateFlow<StreamTelemetry> {
        return telemetryMap.getOrPut(cameraId) {
            MutableStateFlow(StreamTelemetry())
        }.asStateFlow()
    }

    fun updateRecordingState(cameraId: String, isRecording: Boolean) {
        if (isRecording) {
            recordingCameraIds[cameraId] = true
            mainHandler.post {
                val player = playerMap[cameraId]
                if (player != null && !isForegroundCaptureActive(cameraId)) {
                    val vw = player.videoSize.width.takeIf { it > 0 } ?: 640
                    val vh = player.videoSize.height.takeIf { it > 0 } ?: 368
                    ensureHeadlessGrabberLocked(cameraId, player, vw, vh)
                }
            }
        } else {
            recordingCameraIds.remove(cameraId)
            if (isForegroundCaptureActive(cameraId)) {
                mainHandler.post {
                    if (recordingCameraIds[cameraId] != true && isForegroundCaptureActive(cameraId)) {
                        releaseHeadlessGrabber(cameraId)
                    }
                }
            }
        }
        telemetryMap[cameraId]?.let { flow ->
            flow.value = flow.value.copy(isRecording = isRecording)
        }
    }

    fun hasLiveOrGrabberFrames(cameraId: String): Boolean {
        if (hasRenderedFrameMap[cameraId] == true) return true
        val grabber = headlessGrabbers[cameraId]
        if (grabber != null && grabber.getReceivedFrameCount() > 0L) return true
        val telemetry = telemetryMap[cameraId]?.value
        return telemetry?.isLive == true
    }

    fun updateMotionState(cameraId: String, isMotion: Boolean) {
        telemetryMap[cameraId]?.let { flow ->
            flow.value = flow.value.copy(isMotionDetected = isMotion)
        }
    }

    private fun resolveEffectiveUrl(camera: CameraEntity): String {
        return camera.rtspUrl.trim()
    }

    private val activeTextureViews = ConcurrentHashMap<String, WeakReference<TextureView>>()

    fun registerTextureView(cameraId: String, textureView: TextureView) {
        val existing = activeTextureViews[cameraId]?.get()
        if (existing !== textureView) {
            activeTextureViews[cameraId] = WeakReference(textureView)
        }
    }

    fun hasHeadlessGrabber(cameraId: String): Boolean {
        return headlessGrabbers[cameraId]?.isReady == true
    }

    fun isForegroundCaptureActive(cameraId: String): Boolean {
        val tv = activeTextureViews[cameraId]?.get()
        return isAppInForeground &&
            (attachedSurfaceCountMap[cameraId] ?: 0) > 0 &&
            tv != null && tv.isAvailable && tv.width > 0 && tv.height > 0
    }

    fun unregisterTextureView(cameraId: String, textureView: TextureView) {
        val current = activeTextureViews[cameraId]?.get()
        if (current == null || current === textureView) {
            activeTextureViews.remove(cameraId)
        }
    }

    private fun ensureHeadlessGrabberLocked(
        cameraId: String,
        player: ExoPlayer,
        width: Int,
        height: Int
    ): HeadlessGlFrameGrabber? {
        val safeW = width.coerceIn(320, 960).let { if (it % 2 == 0) it else it - 1 }
        val safeH = height.coerceIn(180, 960).let { if (it % 2 == 0) it else it - 1 }
        val existing = headlessGrabbers[cameraId]
        if (existing != null && existing.isReady &&
            kotlin.math.abs(existing.width - safeW) <= 16 &&
            kotlin.math.abs(existing.height - safeH) <= 16
        ) {
            if (needsSurfaceRebind(grabberBoundPlayerMap[cameraId]?.get(), player)) {
                existing.surface?.let { surface ->
                    try {
                        player.setVideoSurface(surface)
                        grabberBoundPlayerMap[cameraId] = WeakReference(player)
                    } catch (_: Exception) {}
                }
            }
            return existing
        }
        if (existing != null) {
            releaseHeadlessGrabber(cameraId)
        }
        val created = HeadlessGlFrameGrabber(cameraId, safeW, safeH)
        if (!created.isReady) {
            created.release()
            return null
        }
        headlessGrabbers[cameraId] = created
        val surface = created.surface
        if (surface != null) {
            try {
                player.setVideoSurface(surface)
                grabberBoundPlayerMap[cameraId] = WeakReference(player)
            } catch (_: Exception) {}
        }
        return created
    }

    fun captureFrame(cameraId: String, width: Int = 640, height: Int = 368): Bitmap? {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val cam = activeCameraMap[cameraId] ?: return null
            return captureFrameForBackgroundWorker(
                camera = cam,
                maxSide = maxOf(width, height),
                alignTo = 2,
                waitHeadlessFirstFrameMs = 200L
            )
        }

        val tv = activeTextureViews[cameraId]?.get()
        val hasForegroundUiSurface = tv != null && tv.isAvailable && tv.width > 0 && tv.height > 0 &&
            isAppInForeground && (attachedSurfaceCountMap[cameraId] ?: 0) > 0
        if (hasForegroundUiSurface) {
            if (hasRenderedFrameMap[cameraId] == true) {
                return try {
                    tv?.getBitmap(width, height)
                } catch (_: Throwable) {
                    null
                }
            }
            return null
        }

        val player = playerMap[cameraId] ?: return null
        val grabber = ensureHeadlessGrabberLocked(cameraId, player, width, height) ?: return null
        return grabber.captureBitmap(width, height, waitIfEmptyMs = 0L)
    }

    /**
     * Thread-safe capture helper intended for IO workers (RemoteHubServer, snapshot export).
     * Never blocks the Main thread waiting for GPU frames: attaches a HeadlessGlFrameGrabber on Main
     * only when no foreground UI TextureView is active, then awaits the first GL frame on the caller's IO thread.
     */
    fun captureFrameForBackgroundWorker(
        camera: CameraEntity,
        maxSide: Int = 960,
        alignTo: Int = 2,
        waitHeadlessFirstFrameMs: Long = 350L
    ): Bitmap? {
        return captureFrameForRecording(
            camera = camera,
            maxSide = maxSide,
            alignTo = alignTo,
            waitHeadlessFirstFrameMs = waitHeadlessFirstFrameMs,
            reuseBitmap = null,
            previousRenderedToken = Int.MIN_VALUE
        ).bitmap
    }

    /**
     * Low-overhead recording capture helper that deduplicates unchanged ExoPlayer decoded frames
     * and reuses caller-provided mutable ARGB_8888 Bitmaps to avoid Main-Thread GPU stalls and GC churn.
     */
    fun captureFrameForRecording(
        camera: CameraEntity,
        maxSide: Int = 640,
        alignTo: Int = 16,
        waitHeadlessFirstFrameMs: Long = 0L,
        reuseBitmap: Bitmap? = null,
        previousRenderedToken: Int = Int.MIN_VALUE
    ): CapturedVideoFrame {
        val validReuse = reuseBitmap?.takeIf {
            !it.isRecycled && it.isMutable && it.config == Bitmap.Config.ARGB_8888
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            val existing = playerMap[camera.id] ?: return CapturedVideoFrame(null)
            runCatching { existing.videoDecoderCounters?.ensureUpdated() }
            val renderedCount = existing.videoDecoderCounters?.renderedOutputBufferCount ?: -1
            val (w, h) = computeAspectPreservingDimensions(
                videoWidth = existing.videoSize.width,
                videoHeight = existing.videoSize.height,
                maxSide = maxSide,
                alignTo = alignTo
            )
            val tv = activeTextureViews[camera.id]?.get()
            val foregroundSurfaceMounted = isAppInForeground &&
                (attachedSurfaceCountMap[camera.id] ?: 0) > 0 &&
                tv != null && tv.isAvailable && tv.width > 0 && tv.height > 0
            if (foregroundSurfaceMounted && tv != null) {
                if (previousRenderedToken != Int.MIN_VALUE &&
                    renderedCount > 0 &&
                    renderedCount == previousRenderedToken
                ) {
                    return CapturedVideoFrame(
                        bitmap = null,
                        renderedFrameToken = renderedCount,
                        unchanged = true
                    )
                }
                val canReadSurface = hasRenderedFrameMap[camera.id] == true ||
                    renderedCount > 0 ||
                    (existing.videoSize.width > 0 && existing.playbackState == Player.STATE_READY)
                if (!canReadSurface) {
                    return CapturedVideoFrame(null, renderedFrameToken = renderedCount)
                }
                val bmp = readTextureViewBitmap(tv, w, h, validReuse)
                return CapturedVideoFrame(
                    bitmap = bmp,
                    renderedFrameToken = renderedCount,
                    unchanged = false
                )
            }
            val bmp = ensureHeadlessGrabberLocked(camera.id, existing, w, h)
                ?.captureBitmap(w, h, waitIfEmptyMs = 0L, reuseBitmap = validReuse)
            return CapturedVideoFrame(
                bitmap = bmp,
                renderedFrameToken = renderedCount,
                isFromHeadless = true
            )
        }

        // Lock-free background fast-path: if headless GL grabber is already attached and ready for the
        // current ExoPlayer, read directly from it on the recorder thread without touching MainLooper.
        val fastGrabber = headlessGrabbers[camera.id]
        val existingPlayer = playerMap[camera.id]
        val boundPlayer = grabberBoundPlayerMap[camera.id]?.get()
        if (shouldUseFastPathHeadlessCapture(
                isForegroundCaptureActive = isForegroundCaptureActive(camera.id),
                hasReadyGrabber = fastGrabber?.isReady == true,
                hasPlayer = existingPlayer != null,
                boundMatchesPlayer = existingPlayer != null && !needsSurfaceRebind(boundPlayer, existingPlayer)
            ) && fastGrabber != null
        ) {
            val (gw, gh) = getAspectPreservingTargetSize(camera.id, maxSide, alignTo)
            val headlessCount = fastGrabber.getReceivedFrameCount().toInt()
            if (previousRenderedToken != Int.MIN_VALUE &&
                headlessCount > 0 &&
                headlessCount == previousRenderedToken
            ) {
                return CapturedVideoFrame(
                    bitmap = null,
                    renderedFrameToken = headlessCount,
                    unchanged = true,
                    isFromHeadless = true
                )
            }
            val bmp = fastGrabber.captureBitmap(
                targetWidth = gw,
                targetHeight = gh,
                waitIfEmptyMs = waitHeadlessFirstFrameMs,
                reuseBitmap = validReuse
            )
            return CapturedVideoFrame(
                bitmap = bmp,
                renderedFrameToken = if (headlessCount > 0) headlessCount else -1,
                isFromHeadless = true
            )
        }

        var directBitmap: Bitmap? = null
        var grabberRef: HeadlessGlFrameGrabber? = null
        var targetW = 640
        var targetH = 368
        var renderedToken = -1
        var isUnchanged = false

        val inFlight = uiCaptureInFlight.getOrPut(camera.id) { java.util.concurrent.atomic.AtomicBoolean(false) }
        if (!inFlight.compareAndSet(false, true)) {
            val cachedGrabber = headlessGrabbers[camera.id]
            if (cachedGrabber != null && cachedGrabber.isReady && !isForegroundCaptureActive(camera.id)) {
                val (gw, gh) = getAspectPreservingTargetSize(camera.id, maxSide, alignTo)
                val bmp = cachedGrabber.captureBitmap(gw, gh, waitIfEmptyMs = 0L, reuseBitmap = validReuse)
                return CapturedVideoFrame(bmp, renderedFrameToken = -1, isFromHeadless = true)
            }
            return CapturedVideoFrame(null)
        }

        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = CountDownLatch(1)
        mainHandler.post {
            if (cancelled.get()) {
                inFlight.set(false)
                latch.countDown()
                return@post
            }
            try {
                val player = playerMap[camera.id] ?: getOrCreatePlayer(camera)
                runCatching { player.videoDecoderCounters?.ensureUpdated() }
                val renderedCount = player.videoDecoderCounters?.renderedOutputBufferCount ?: -1
                renderedToken = renderedCount
                if (player.videoSize.width > 0 && player.videoSize.height > 0) {
                    lastVideoSizeMap[camera.id] = player.videoSize.width to player.videoSize.height
                }

                val (w, h) = computeAspectPreservingDimensions(
                    videoWidth = player.videoSize.width,
                    videoHeight = player.videoSize.height,
                    maxSide = maxSide,
                    alignTo = alignTo
                )
                targetW = w
                targetH = h

                val tv = activeTextureViews[camera.id]?.get()
                val foregroundSurfaceMounted = isAppInForeground &&
                    (attachedSurfaceCountMap[camera.id] ?: 0) > 0 &&
                    tv != null && tv.isAvailable && tv.width > 0 && tv.height > 0

                if (foregroundSurfaceMounted && tv != null) {
                    if (previousRenderedToken != Int.MIN_VALUE &&
                        renderedCount > 0 &&
                        renderedCount == previousRenderedToken
                    ) {
                        isUnchanged = true
                    } else {
                        val canReadSurface = hasRenderedFrameMap[camera.id] == true ||
                            renderedCount > 0 ||
                            (player.videoSize.width > 0 && player.playbackState == Player.STATE_READY)
                        if (canReadSurface && !cancelled.get()) {
                            directBitmap = readTextureViewBitmap(tv, w, h, validReuse)
                        }
                    }
                } else {
                    grabberRef = ensureHeadlessGrabberLocked(camera.id, player, w, h)
                }
            } catch (_: Throwable) {
            } finally {
                inFlight.set(false)
                latch.countDown()
            }
        }

        try {
            if (!latch.await(360, TimeUnit.MILLISECONDS)) {
                cancelled.set(true)
            }
        } catch (_: Exception) {
            cancelled.set(true)
        }

        if (isUnchanged) {
            return CapturedVideoFrame(
                bitmap = null,
                renderedFrameToken = renderedToken,
                unchanged = true
            )
        }
        directBitmap?.let { bmp ->
            return CapturedVideoFrame(
                bitmap = bmp,
                renderedFrameToken = renderedToken
            )
        }
        val headlessBmp = grabberRef?.captureBitmap(
            targetWidth = targetW,
            targetHeight = targetH,
            waitIfEmptyMs = waitHeadlessFirstFrameMs,
            reuseBitmap = validReuse
        )
        return CapturedVideoFrame(
            bitmap = headlessBmp,
            renderedFrameToken = renderedToken,
            isFromHeadless = grabberRef != null
        )
    }

    private fun readTextureViewBitmap(
        tv: TextureView,
        width: Int,
        height: Int,
        reuseBitmap: Bitmap?
    ): Bitmap? {
        return try {
            if (reuseBitmap != null && reuseBitmap.width == width && reuseBitmap.height == height) {
                tv.getBitmap(reuseBitmap)
            } else {
                tv.getBitmap(width, height)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun getAspectPreservingTargetSize(
        cameraId: String,
        maxSide: Int = 640,
        alignTo: Int = 16
    ): Pair<Int, Int> {
        val cachedSize = lastVideoSizeMap[cameraId]
        val (vw, vh) = cachedSize ?: if (Looper.myLooper() == Looper.getMainLooper()) {
            val vs = playerMap[cameraId]?.videoSize
            (vs?.width ?: 0) to (vs?.height ?: 0)
        } else {
            0 to 0
        }
        return computeAspectPreservingDimensions(
            videoWidth = vw,
            videoHeight = vh,
            maxSide = maxSide,
            alignTo = alignTo
        )
    }


    fun isLiveRtspCamera(camera: CameraEntity): Boolean {
        val rawUrl = resolveEffectiveUrl(camera)
        return Uri.parse(rawUrl).scheme.equals("rtsp", ignoreCase = true)
    }

    private fun createMediaSource(camera: CameraEntity, attempt: Int = 0): MediaSource {
        var rawUrl = resolveEffectiveUrl(camera)
        if (!isValidStreamUrl(rawUrl)) {
            throw IllegalArgumentException("Invalid stream URL")
        }
        val uri = Uri.parse(rawUrl)
        val scheme = uri.scheme?.lowercase() ?: ""

        if (scheme == "rtsp" || scheme == "rtsps") {
            if (camera.username.isNotBlank() && !rawUrl.contains("@")) {
                val schemeIdx = rawUrl.indexOf("://")
                if (schemeIdx >= 0) {
                    val prefix = rawUrl.substring(0, schemeIdx + 3)
                    val rest = rawUrl.substring(schemeIdx + 3)
                    val encodedUser = Uri.encode(camera.username.trim())
                    val encodedPass = Uri.encode(camera.password.trim())
                    rawUrl = "$prefix$encodedUser:$encodedPass@$rest"
                }
            }
            val factory = RtspMediaSource.Factory()
                .setTimeoutMs(20_000L)
                .setSocketFactory(createRtspSocketFactory())
            val useTcp = resolveUseTcpTransport(camera.transport, attempt)
            factory.setForceUseRtpTcp(useTcp)
            return factory.createMediaSource(MediaItem.fromUri(Uri.parse(rawUrl)))
        } else {
            val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent("OmniView-RTSP/1.0 (Android; Surveillance Engine)")
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true)
            val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
            return ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(uri))
        }
    }

    /**
     * Reads actual metrics directly from ExoPlayer's decoder counters & format, sanitizes
     * TIME_END_OF_SOURCE buffer overflows, and runs an aggressive frame-level watchdog
     * so RTSP streams recover automatically instead of showing a dark screen.
     */
    private fun startTelemetryLoop() {
        telemetryJob = scope.launch {
            while (isActive) {
                delay(1000)
                val muted = FloatingOverlayStateHolder.isGlobalMuted.value
                val expectedVolume = if (muted) 0f else 1f
                val now = System.currentTimeMillis()

                for ((id, flow) in telemetryMap) {
                    try {
                    val player = playerMap[id] ?: continue
                    val cam = activeCameraMap[id]
                    val isLiveRtsp = cam?.let { isLiveRtspCamera(it) } ?: false

                    if (player.volume != expectedVolume) {
                        player.volume = expectedVolume
                    }

                    val format = player.videoFormat
                    val rawBuffered = player.totalBufferedDuration
                    val safeLatency = quantizeLatencyMs(sanitizeBufferedLatencyMs(rawBuffered))
                    runCatching { player.videoDecoderCounters?.ensureUpdated() }
                    val renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount ?: -1
                    val previousRendered = lastRenderedFramesMap[id] ?: -1
                    if (renderedFrames >= 0) {
                        lastRenderedFramesMap[id] = renderedFrames
                    }

                    val posMs = player.currentPosition
                    val prevPos = lastPlaybackPosMap[id] ?: -1L
                    lastPlaybackPosMap[id] = posMs

                    val hasSelectedVideo = player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                    val currentlyStalled = stalledSecondsMap[id] ?: 0
                    val hasRenderedBefore = hasRenderedFrameMap[id] == true
                    val preparedAt = preparedAtMsMap[id] ?: now
                    val elapsedSincePrepare = (now - preparedAt).coerceAtLeast(0L)
                    val inStartupGrace = isWithinStartupGrace(
                        hasRenderedFrame = hasRenderedBefore,
                        elapsedSincePrepareMs = elapsedSincePrepare,
                        hasError = flow.value.hasError,
                        playbackState = player.playbackState
                    )
                    val inSurfaceGrace = now < (surfaceGraceUntilMsMap[id] ?: 0L)
                    val hasSurface = isAppInForeground && (attachedSurfaceCountMap[id] ?: 0) > 0
                    val grabberFrameCount = headlessGrabbers[id]?.getReceivedFrameCount() ?: -1L
                    val prevGrabberFrameCount = lastGrabberFrameCounts[id] ?: -1L
                    if (grabberFrameCount >= 0L) {
                        lastGrabberFrameCounts[id] = grabberFrameCount
                    } else {
                        lastGrabberFrameCounts.remove(id)
                    }
                    val headlessProgressed = grabberFrameCount >= 0L &&
                        prevGrabberFrameCount >= 0L &&
                        grabberFrameCount > prevGrabberFrameCount

                    val eval = evaluateLiveVideoHealth(
                        isLiveRtsp = isLiveRtsp,
                        isPlaying = player.isPlaying,
                        playbackState = player.playbackState,
                        hasError = flow.value.hasError,
                        hasSelectedVideoTrack = hasSelectedVideo,
                        rawBufferedMs = rawBuffered,
                        currentRenderedFrames = renderedFrames,
                        previousRenderedFrames = previousRendered,
                        currentPositionMs = posMs,
                        previousPositionMs = prevPos,
                        hasAttachedSurface = hasSurface,
                        headlessFrameProgressed = headlessProgressed
                    )

                    if (eval.healthyProgress) {
                        stalledSecondsMap[id] = 0
                        hasRenderedFrameMap[id] = true
                        val healthyStreak = (healthySecondsMap[id] ?: 0) + 1
                        healthySecondsMap[id] = healthyStreak
                        if (healthyStreak >= 12) {
                            recoveryAttemptsMap[id] = 0
                        }
                    } else if (inStartupGrace || inSurfaceGrace) {
                        // Allow initial RTSP TCP handshake + first IDR keyframe and surface handoffs without false stalls
                        stalledSecondsMap[id] = 0
                    } else if (!flow.value.hasError) {
                        healthySecondsMap[id] = 0
                        stalledSecondsMap[id] = currentlyStalled + eval.stallIncrement
                    }

                    val updatedStalled = stalledSecondsMap[id] ?: 0
                    val measuredFps = if (renderedFrames >= 0 && previousRendered >= 0 && renderedFrames >= previousRendered) {
                        renderedFrames - previousRendered
                    } else {
                        0
                    }
                    val realFps = if (format?.frameRate != null && format.frameRate > 0) {
                        if (eval.healthyProgress || updatedStalled < 4) format.frameRate.toInt() else 0
                    } else if (eval.healthyProgress) {
                        measuredFps
                    } else {
                        0
                    }

                    val realBitrate = if (format?.bitrate != null && format.bitrate > 0) {
                        if (eval.healthyProgress || updatedStalled < 4) format.bitrate / 1000 else 0
                    } else {
                        0
                    }

                    val realCodec = format?.sampleMimeType ?: flow.value.codec.takeIf { !flow.value.hasError } ?: ""
                    val showLiveBadge = !flow.value.hasError && (eval.isLive && updatedStalled < 4)
                    val attemptCount = recoveryAttemptsMap[id] ?: 0
                    val showReconnectingBadge = !flow.value.hasError && when {
                        eval.healthyProgress -> false
                        inStartupGrace || inSurfaceGrace -> attemptCount > 0
                        else -> (eval.isReconnecting && updatedStalled >= 3) ||
                            updatedStalled >= 5 ||
                            isAbnormalBufferSentinel(rawBuffered, isLiveRtsp) ||
                            player.playbackState == Player.STATE_ENDED
                    }

                    val nextTelemetry = flow.value.copy(
                        fps = realFps,
                        bitrateKbps = realBitrate,
                        codec = realCodec,
                        latencyMs = safeLatency,
                        isLive = showLiveBadge,
                        isReconnecting = showReconnectingBadge
                    )
                    if (nextTelemetry != flow.value) {
                        flow.value = nextTelemetry
                    }

                    // Background recording guard: keep playWhenReady true and ensure a healthy
                    // HeadlessGlFrameGrabber is bound whenever no foreground UI TextureView is active.
                    if (recordingCameraIds[id] == true && !isForegroundCaptureActive(id)) {
                        if (!flow.value.hasError && !player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
                            player.playWhenReady = true
                        }
                        val currentGrabber = headlessGrabbers[id]
                        if (currentGrabber == null || !currentGrabber.isReady) {
                            val vw = player.videoSize.width.takeIf { it > 0 } ?: 640
                            val vh = player.videoSize.height.takeIf { it > 0 } ?: 368
                            ensureHeadlessGrabberLocked(id, player, vw, vh)
                        } else if (player.isPlaying &&
                            currentGrabber.lastFrameArrivalAtMs > 0L &&
                            (now - currentGrabber.lastFrameArrivalAtMs) >= HEADLESS_STALL_RECREATE_MS
                        ) {
                            val vw = player.videoSize.width.takeIf { it > 0 } ?: 640
                            val vh = player.videoSize.height.takeIf { it > 0 } ?: 368
                            releaseHeadlessGrabber(id)
                            ensureHeadlessGrabberLocked(id, player, vw, vh)
                        }
                    }

                    // Automatic Watchdog: recover from frozen frames, TIME_END_OF_SOURCE, track loss, or errors
                    if (cam != null) {
                        val lastRetry = lastRetryAtMap[id] ?: 0L
                        val sinceLastRetry = now - lastRetry
                        val canRetryError = sinceLastRetry >= ERROR_RETRY_COOLDOWN_MS
                        val canRetryEos = sinceLastRetry >= EOS_RETRY_COOLDOWN_MS
                        val canRetryNormal = sinceLastRetry >= RETRY_COOLDOWN_MS

                        val shouldRecover = when {
                            flow.value.hasError -> canRetryError
                            isAbnormalBufferSentinel(rawBuffered, isLiveRtsp) -> updatedStalled >= 2 && canRetryEos
                            player.playbackState == Player.STATE_ENDED -> updatedStalled >= 2 && canRetryEos
                            inStartupGrace || inSurfaceGrace -> false
                            player.playbackState == Player.STATE_IDLE -> updatedStalled >= 4 && canRetryNormal
                            isLiveRtsp && player.playbackState == Player.STATE_READY && !hasSelectedVideo ->
                                updatedStalled >= 5 && canRetryNormal
                            player.isPlaying && !eval.healthyProgress -> updatedStalled >= 9 && canRetryNormal
                            else -> updatedStalled >= 12 && canRetryNormal
                        }

                        if (shouldRecover) {
                            recoverCameraStream(cam)
                        }
                    }
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    fun releasePlayer(cameraId: String) {
        releaseHeadlessGrabber(cameraId)
        grabberBoundPlayerMap.remove(cameraId)
        playerMap.remove(cameraId)?.release()
        playerFlowMap.remove(cameraId)
        telemetryMap.remove(cameraId)
        activeUrlMap.remove(cameraId)
        activeCameraMap.remove(cameraId)
        hasRenderedFrameMap.remove(cameraId)
        preparedAtMsMap.remove(cameraId)
        attachedSurfaceCountMap.remove(cameraId)
        surfaceGraceUntilMsMap.remove(cameraId)
        stalledSecondsMap.remove(cameraId)
        lastRetryAtMap.remove(cameraId)
        lastRenderedFramesMap.remove(cameraId)
        lastPlaybackPosMap.remove(cameraId)
        recoveryAttemptsMap.remove(cameraId)
        healthySecondsMap.remove(cameraId)
        lastVideoSizeMap.remove(cameraId)
        recordingCameraIds.remove(cameraId)
        uiCaptureInFlight.remove(cameraId)
        lastGrabberFrameCounts.remove(cameraId)
    }

    fun releaseAll() {
        headlessGrabbers.keys.toList().forEach { releaseHeadlessGrabber(it) }
        grabberBoundPlayerMap.clear()
        for ((_, player) in playerMap) {
            try {
                player.release()
            } catch (_: Exception) {}
        }
        playerMap.clear()
        playerFlowMap.clear()
        telemetryMap.clear()
        activeUrlMap.clear()
        activeCameraMap.clear()
        hasRenderedFrameMap.clear()
        preparedAtMsMap.clear()
        attachedSurfaceCountMap.clear()
        surfaceGraceUntilMsMap.clear()
        stalledSecondsMap.clear()
        lastRetryAtMap.clear()
        lastRenderedFramesMap.clear()
        lastPlaybackPosMap.clear()
        recoveryAttemptsMap.clear()
        healthySecondsMap.clear()
        lastVideoSizeMap.clear()
        recordingCameraIds.clear()
        uiCaptureInFlight.clear()
        lastGrabberFrameCounts.clear()
    }

    companion object {
        const val ABNORMAL_BUFFERED_THRESHOLD_MS = 3_600_000L
        const val STARTUP_GRACE_MS = 14_000L
        const val SURFACE_SWITCH_GRACE_MS = 3_000L
        const val EOS_RETRY_COOLDOWN_MS = 3_000L
        const val RETRY_COOLDOWN_MS = 8_000L
        const val ERROR_RETRY_COOLDOWN_MS = 15_000L
        const val HEADLESS_STALL_RECREATE_MS = 6_000L
        const val MAX_LIVE_BUFFER_BYTES_PER_CAMERA = 12 * 1024 * 1024

        fun computeStreamConfigSignature(camera: CameraEntity): String {
            return "${camera.rtspUrl.trim()}|${camera.username.trim()}|${camera.password.trim()}|${camera.transport.name}"
        }

        fun isValidStreamUrl(rawUrl: String): Boolean {
            val trimmed = rawUrl.trim()
            if (trimmed.isBlank() || trimmed.any { it.isWhitespace() }) return false
            return try {
                val parsed = java.net.URI(trimmed)
                val scheme = parsed.scheme?.lowercase() ?: return false
                if (scheme != "rtsp" && scheme != "rtsps" && scheme != "http" && scheme != "https") {
                    return false
                }
                val host = parsed.host
                !host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }

        @Volatile
        private var instance: RtspStreamManager? = null

        fun getInstance(context: Context): RtspStreamManager {
            return instance ?: synchronized(this) {
                instance ?: RtspStreamManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Clamps invalid Media3 buffer durations such as Long.MAX_VALUE / 1000 - pos
         * (e.g. 9223372036821781ms at TIME_END_OF_SOURCE) down to 0ms.
         */
        fun sanitizeBufferedLatencyMs(rawBufferedMs: Long): Long {
            return if (rawBufferedMs in 0L until ABNORMAL_BUFFERED_THRESHOLD_MS) {
                rawBufferedMs
            } else {
                0L
            }
        }

        /**
         * Detects when a live RTSP stream has leaked into TIME_END_OF_SOURCE / EOF buffer space.
         */
        fun isAbnormalBufferSentinel(rawBufferedMs: Long, isLiveRtsp: Boolean): Boolean {
            return isLiveRtsp && rawBufferedMs >= ABNORMAL_BUFFERED_THRESHOLD_MS
        }

        /**
         * Avoids connect/reconnect thrashing during the initial RTSP TCP handshake + first keyframe window.
         */
        fun isWithinStartupGrace(
            hasRenderedFrame: Boolean,
            elapsedSincePrepareMs: Long,
            hasError: Boolean,
            playbackState: Int,
            gracePeriodMs: Long = STARTUP_GRACE_MS
        ): Boolean {
            return !hasRenderedFrame &&
                !hasError &&
                playbackState != Player.STATE_ENDED &&
                elapsedSincePrepareMs in 0L until gracePeriodMs
        }

        /**
         * Evaluates actual frame-level stream health instead of blindly trusting player.isPlaying.
         */
        fun evaluateLiveVideoHealth(
            isLiveRtsp: Boolean,
            isPlaying: Boolean,
            playbackState: Int,
            hasError: Boolean,
            hasSelectedVideoTrack: Boolean,
            rawBufferedMs: Long,
            currentRenderedFrames: Int,
            previousRenderedFrames: Int,
            currentPositionMs: Long,
            previousPositionMs: Long,
            hasAttachedSurface: Boolean = true,
            headlessFrameProgressed: Boolean = false
        ): StreamHealthEvaluation {
            if (hasError) {
                return StreamHealthEvaluation(
                    healthyProgress = false,
                    stallIncrement = 0,
                    isLive = false,
                    isReconnecting = false
                )
            }

            if (isAbnormalBufferSentinel(rawBufferedMs, isLiveRtsp)) {
                return StreamHealthEvaluation(
                    healthyProgress = false,
                    stallIncrement = 3,
                    isLive = false,
                    isReconnecting = true
                )
            }

            if (playbackState == Player.STATE_ENDED) {
                return StreamHealthEvaluation(
                    healthyProgress = false,
                    stallIncrement = if (isLiveRtsp) 3 else 1,
                    isLive = false,
                    isReconnecting = true
                )
            }

            if (playbackState == Player.STATE_IDLE) {
                return StreamHealthEvaluation(
                    healthyProgress = false,
                    stallIncrement = 2,
                    isLive = false,
                    isReconnecting = true
                )
            }

            if (isLiveRtsp && playbackState == Player.STATE_READY && !hasSelectedVideoTrack) {
                return StreamHealthEvaluation(
                    healthyProgress = false,
                    stallIncrement = 2,
                    isLive = false,
                    isReconnecting = true
                )
            }

            if (isPlaying) {
                val frameProgressed = if (hasAttachedSurface) {
                    when {
                        currentRenderedFrames >= 0 && previousRenderedFrames >= 0 ->
                            currentRenderedFrames > previousRenderedFrames
                        currentRenderedFrames > 0 && previousRenderedFrames < 0 -> true
                        else -> currentPositionMs > previousPositionMs || previousPositionMs < 0L
                    }
                } else {
                    // Offscreen/no visible PlayerView: judge liveness by headless GL frames, decoder counters, or timeline progress
                    headlessFrameProgressed ||
                        (currentRenderedFrames >= 0 && previousRenderedFrames >= 0 && currentRenderedFrames > previousRenderedFrames) ||
                        currentPositionMs > previousPositionMs ||
                        previousPositionMs < 0L
                }
                return if (frameProgressed) {
                    StreamHealthEvaluation(
                        healthyProgress = true,
                        stallIncrement = 0,
                        isLive = true,
                        isReconnecting = false
                    )
                } else {
                    StreamHealthEvaluation(
                        healthyProgress = false,
                        stallIncrement = 1,
                        isLive = false,
                        isReconnecting = false
                    )
                }
            }

            return StreamHealthEvaluation(
                healthyProgress = false,
                stallIncrement = 1,
                isLive = false,
                isReconnecting = playbackState == Player.STATE_BUFFERING
            )
        }

        fun computeAspectPreservingDimensions(
            videoWidth: Int,
            videoHeight: Int,
            maxSide: Int = 640,
            alignTo: Int = 16
        ): Pair<Int, Int> {
            val safeAlign = alignTo.coerceAtLeast(2)
            val safeMax = ((maxSide.coerceAtLeast(safeAlign * 4) / safeAlign) * safeAlign)
            if (videoWidth <= 0 || videoHeight <= 0) {
                val defaultH = ((safeMax * 9f / 16f) / safeAlign).roundToInt().coerceAtLeast(2) * safeAlign
                return safeMax to defaultH
            }
            val aspect = videoWidth.toFloat() / videoHeight.toFloat()
            val (rawW, rawH) = if (aspect >= 1f) {
                safeMax.toFloat() to (safeMax / aspect)
            } else {
                (safeMax * aspect) to safeMax.toFloat()
            }
            val alignedW = ((rawW / safeAlign).roundToInt().coerceAtLeast(4) * safeAlign).coerceAtMost(safeMax)
            val alignedH = ((rawH / safeAlign).roundToInt().coerceAtLeast(4) * safeAlign).coerceAtMost(safeMax)
            return alignedW to alignedH
        }

        fun <T : Any> needsSurfaceRebind(boundTarget: T?, currentTarget: T): Boolean {
            return boundTarget !== currentTarget
        }

        fun shouldAttachHeadlessGrabber(
            isAppInForeground: Boolean,
            attachedSurfaceCount: Int,
            hasAvailableTextureView: Boolean
        ): Boolean {
            val foregroundUiMounted = isAppInForeground && attachedSurfaceCount > 0 && hasAvailableTextureView
            return !foregroundUiMounted
        }

        fun quantizeLatencyMs(latencyMs: Long, bucketMs: Long = 50L): Long {
            if (latencyMs <= 0L) return 0L
            return ((latencyMs + bucketMs / 2L) / bucketMs) * bucketMs
        }

        fun shouldUseFastPathHeadlessCapture(
            isForegroundCaptureActive: Boolean,
            hasReadyGrabber: Boolean,
            hasPlayer: Boolean,
            boundMatchesPlayer: Boolean
        ): Boolean {
            return !isForegroundCaptureActive && hasReadyGrabber && hasPlayer && boundMatchesPlayer
        }

        fun resolveUseTcpTransport(transport: RtspTransport, attempt: Int): Boolean {
            return when (transport) {
                RtspTransport.TCP -> true
                RtspTransport.UDP -> false
                RtspTransport.AUTO -> (attempt % 4 != 3)
            }
        }

        private fun createRtspSocketFactory(): javax.net.SocketFactory {
            val base = javax.net.SocketFactory.getDefault()
            return object : javax.net.SocketFactory() {
                private fun configure(socket: java.net.Socket): java.net.Socket = socket.apply {
                    runCatching { tcpNoDelay = true }
                    runCatching { keepAlive = true }
                }

                override fun createSocket(): java.net.Socket = configure(base.createSocket())
                override fun createSocket(host: String?, port: Int): java.net.Socket =
                    configure(base.createSocket(host, port))
                override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket =
                    configure(base.createSocket(host, port, localHost, localPort))
                override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket =
                    configure(base.createSocket(host, port))
                override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket =
                    configure(base.createSocket(address, port, localAddress, localPort))
            }
        }
    }
}
