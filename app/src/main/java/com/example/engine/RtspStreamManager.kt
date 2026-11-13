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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.data.model.CameraEntity
import com.example.data.model.RtspTransport
import com.example.data.model.StreamTelemetry
import com.example.service.FloatingOverlayStateHolder
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

    private val playerMap = ConcurrentHashMap<String, ExoPlayer>()
    private val playerFlowMap = ConcurrentHashMap<String, MutableStateFlow<ExoPlayer?>>()
    private val telemetryMap = ConcurrentHashMap<String, MutableStateFlow<StreamTelemetry>>()
    private val activeUrlMap = ConcurrentHashMap<String, String>()
    private val activeCameraMap = ConcurrentHashMap<String, CameraEntity>()
    private val hasRenderedFrameMap = ConcurrentHashMap<String, Boolean>()
    private val stalledSecondsMap = ConcurrentHashMap<String, Int>()
    private val lastRetryAtMap = ConcurrentHashMap<String, Long>()
    private val lastRenderedFramesMap = ConcurrentHashMap<String, Int>()
    private val lastPlaybackPosMap = ConcurrentHashMap<String, Long>()
    private val recoveryAttemptsMap = ConcurrentHashMap<String, Int>()
    private val healthySecondsMap = ConcurrentHashMap<String, Int>()

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

    fun getPlayerFlow(cameraId: String): StateFlow<ExoPlayer?> {
        return playerFlowMap.getOrPut(cameraId) {
            MutableStateFlow(playerMap[cameraId])
        }.asStateFlow()
    }

    fun getOrCreatePlayer(camera: CameraEntity): ExoPlayer {
        activeCameraMap[camera.id] = camera
        val existing = playerMap[camera.id]
        val currentUrl = camera.rtspUrl.trim()
        val targetVolume = if (FloatingOverlayStateHolder.isGlobalMuted.value) 0f else 1f

        if (existing != null) {
            if (existing.volume != targetVolume) {
                existing.volume = targetVolume
            }
            val previousUrl = activeUrlMap[camera.id]
            if (previousUrl != null && previousUrl != currentUrl) {
                activeUrlMap[camera.id] = currentUrl
                hasRenderedFrameMap[camera.id] = false
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
                } catch (_: Exception) {}
            }
            playerFlowMap.getOrPut(camera.id) { MutableStateFlow(existing) }.value = existing
            return existing
        }

        return createAndRegisterPlayer(camera)
    }

    private fun createAndRegisterPlayer(camera: CameraEntity, attempt: Int = 0): ExoPlayer {
        val currentUrl = camera.rtspUrl.trim()
        val targetVolume = if (FloatingOverlayStateHolder.isGlobalMuted.value) 0f else 1f
        val isLiveRtsp = isLiveRtspCamera(camera)

        activeCameraMap[camera.id] = camera
        activeUrlMap[camera.id] = currentUrl
        hasRenderedFrameMap[camera.id] = false
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
                /* maxBufferMs = */ 10_000,
                /* bufferForPlaybackMs = */ 400,
                /* bufferForPlaybackAfterRebufferMs = */ 1_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()

        val player = ExoPlayer.Builder(context)
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
                        val hadFrame = hasRenderedFrameMap[camera.id] == true
                        flow.value = flow.value.copy(
                            isLive = false,
                            isReconnecting = hadFrame
                        )
                    }
                    Player.STATE_ENDED -> {
                        val latestCam = activeCameraMap[camera.id] ?: camera
                        if (isLiveRtsp) {
                            // Live RTSP reached END_OF_SOURCE (e.g. camera 30s session drop):
                            // never loop a closed period; immediately mark reconnecting and rebuild session.
                            hasRenderedFrameMap[camera.id] = false
                            flow.value = flow.value.copy(
                                isLive = false,
                                isReconnecting = true,
                                latencyMs = 0L
                            )
                            val now = System.currentTimeMillis()
                            val lastRetry = lastRetryAtMap[camera.id] ?: 0L
                            if (now - lastRetry >= 3000L) {
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
        } catch (e: Exception) {
            flow.value = flow.value.copy(
                isLive = false,
                isReconnecting = false,
                hasError = true,
                errorMessage = e.message ?: "Failed to open stream"
            )
        }

        playerMap[camera.id] = player
        playerFlowMap.getOrPut(camera.id) { MutableStateFlow(player) }.value = player
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
        recoveryAttemptsMap[camera.id] = attempt + 1
        stalledSecondsMap[camera.id] = 0
        healthySecondsMap[camera.id] = 0
        hasRenderedFrameMap[camera.id] = false
        lastRenderedFramesMap.remove(camera.id)
        lastPlaybackPosMap.remove(camera.id)
        lastRetryAtMap[camera.id] = System.currentTimeMillis()

        flow?.value = flow.value.copy(
            isLive = false,
            isReconnecting = true,
            hasError = false,
            errorMessage = null,
            latencyMs = 0L
        )

        if (player == null || forceRebuild || attempt >= 1) {
            playerMap.remove(camera.id)?.let { old ->
                try {
                    old.stop()
                    old.release()
                } catch (_: Exception) {}
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
        } catch (e: Exception) {
            flow?.value = flow.value.copy(
                isLive = false,
                isReconnecting = false,
                hasError = true,
                errorMessage = e.message ?: "Reconnect failed"
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
        telemetryMap[cameraId]?.let { flow ->
            flow.value = flow.value.copy(isRecording = isRecording)
        }
    }

    fun updateMotionState(cameraId: String, isMotion: Boolean) {
        telemetryMap[cameraId]?.let { flow ->
            flow.value = flow.value.copy(isMotionDetected = isMotion)
        }
    }

    private fun resolveEffectiveUrl(camera: CameraEntity): String {
        var rawUrl = camera.rtspUrl.trim()
        if (rawUrl.contains("commondatastorage.googleapis.com") || rawUrl.contains("wowzaec2demo")) {
            rawUrl = "android.resource://${context.packageName}/${com.example.R.raw.surveillance_feed}"
        }
        return rawUrl
    }

    private fun isLiveRtspCamera(camera: CameraEntity): Boolean {
        val rawUrl = resolveEffectiveUrl(camera)
        return Uri.parse(rawUrl).scheme.equals("rtsp", ignoreCase = true)
    }

    private fun createMediaSource(camera: CameraEntity, attempt: Int = 0): MediaSource {
        var rawUrl = resolveEffectiveUrl(camera)
        val uri = Uri.parse(rawUrl)
        val scheme = uri.scheme?.lowercase() ?: ""

        if (scheme == "rtsp") {
            if (camera.username.isNotBlank() && !rawUrl.contains("@")) {
                val prefix = "rtsp://"
                if (rawUrl.startsWith(prefix, ignoreCase = true)) {
                    val rest = rawUrl.substring(prefix.length)
                    val encodedUser = Uri.encode(camera.username.trim())
                    val encodedPass = Uri.encode(camera.password.trim())
                    rawUrl = "$prefix$encodedUser:$encodedPass@$rest"
                }
            }
            val factory = RtspMediaSource.Factory()
                .setTimeoutMs(10_000L)
            val useTcp = when (camera.transport) {
                RtspTransport.TCP -> true
                RtspTransport.UDP -> false
                RtspTransport.AUTO -> (attempt % 2 == 0)
            }
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
                    val player = playerMap[id] ?: continue
                    val cam = activeCameraMap[id]
                    val isLiveRtsp = cam?.let { isLiveRtspCamera(it) } ?: false

                    if (player.volume != expectedVolume) {
                        player.volume = expectedVolume
                    }

                    val format = player.videoFormat
                    val rawBuffered = player.totalBufferedDuration
                    val safeLatency = sanitizeBufferedLatencyMs(rawBuffered)
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
                        previousPositionMs = prevPos
                    )

                    if (eval.healthyProgress) {
                        stalledSecondsMap[id] = 0
                        hasRenderedFrameMap[id] = true
                        val healthyStreak = (healthySecondsMap[id] ?: 0) + 1
                        healthySecondsMap[id] = healthyStreak
                        if (healthyStreak >= 15) {
                            recoveryAttemptsMap[id] = 0
                        }
                    } else if (!flow.value.hasError) {
                        healthySecondsMap[id] = 0
                        stalledSecondsMap[id] = currentlyStalled + eval.stallIncrement
                    }

                    val updatedStalled = stalledSecondsMap[id] ?: 0
                    val realFps = if (format?.frameRate != null && format.frameRate > 0) {
                        if (eval.healthyProgress || updatedStalled < 3) format.frameRate.toInt() else 0
                    } else if (eval.healthyProgress) {
                        30
                    } else {
                        0
                    }

                    val realBitrate = if (format?.bitrate != null && format.bitrate > 0) {
                        if (eval.healthyProgress || updatedStalled < 3) format.bitrate / 1000 else 0
                    } else if (eval.healthyProgress) {
                        2048
                    } else {
                        0
                    }

                    val realCodec = format?.sampleMimeType ?: flow.value.codec.takeIf { !flow.value.hasError } ?: "H.264 / RTSP"
                    val showLiveBadge = !flow.value.hasError && (eval.isLive && updatedStalled < 3)
                    val showReconnectingBadge = !flow.value.hasError &&
                        (eval.isReconnecting || updatedStalled >= 3 || flow.value.isReconnecting)

                    flow.value = flow.value.copy(
                        fps = realFps,
                        bitrateKbps = realBitrate,
                        codec = realCodec,
                        latencyMs = safeLatency,
                        isLive = showLiveBadge,
                        isReconnecting = if (eval.healthyProgress) false else showReconnectingBadge
                    )

                    // Automatic Watchdog: recover from frozen frames, TIME_END_OF_SOURCE, track loss, or errors
                    if (cam != null) {
                        val lastRetry = lastRetryAtMap[id] ?: 0L
                        val canRetryNow = now - lastRetry >= 4000L

                        val shouldRecover = when {
                            flow.value.hasError -> canRetryNow
                            isAbnormalBufferSentinel(rawBuffered, isLiveRtsp) -> updatedStalled >= 2 && canRetryNow
                            player.playbackState == Player.STATE_ENDED -> updatedStalled >= 1 && canRetryNow
                            player.playbackState == Player.STATE_IDLE -> updatedStalled >= 2 && canRetryNow
                            isLiveRtsp && player.playbackState == Player.STATE_READY && !hasSelectedVideo ->
                                updatedStalled >= 3 && canRetryNow
                            player.isPlaying && !eval.healthyProgress -> updatedStalled >= 5 && canRetryNow
                            else -> updatedStalled >= 8 && canRetryNow
                        }

                        if (shouldRecover) {
                            recoverCameraStream(cam)
                        }
                    }
                }
            }
        }
    }

    fun releasePlayer(cameraId: String) {
        playerMap.remove(cameraId)?.release()
        playerFlowMap.remove(cameraId)
        telemetryMap.remove(cameraId)
        activeUrlMap.remove(cameraId)
        activeCameraMap.remove(cameraId)
        hasRenderedFrameMap.remove(cameraId)
        stalledSecondsMap.remove(cameraId)
        lastRetryAtMap.remove(cameraId)
        lastRenderedFramesMap.remove(cameraId)
        lastPlaybackPosMap.remove(cameraId)
        recoveryAttemptsMap.remove(cameraId)
        healthySecondsMap.remove(cameraId)
    }

    fun releaseAll() {
        telemetryJob?.cancel()
        scope.cancel()
        for ((_, player) in playerMap) {
            player.release()
        }
        playerMap.clear()
        playerFlowMap.clear()
        telemetryMap.clear()
        activeUrlMap.clear()
        activeCameraMap.clear()
        hasRenderedFrameMap.clear()
        stalledSecondsMap.clear()
        lastRetryAtMap.clear()
        lastRenderedFramesMap.clear()
        lastPlaybackPosMap.clear()
        recoveryAttemptsMap.clear()
        healthySecondsMap.clear()
    }

    companion object {
        const val ABNORMAL_BUFFERED_THRESHOLD_MS = 3_600_000L

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
            previousPositionMs: Long
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
                val frameProgressed = when {
                    currentRenderedFrames >= 0 && previousRenderedFrames >= 0 ->
                        currentRenderedFrames > previousRenderedFrames
                    currentRenderedFrames > 0 && previousRenderedFrames < 0 -> true
                    else -> currentPositionMs > previousPositionMs || previousPositionMs < 0L
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
    }
}
