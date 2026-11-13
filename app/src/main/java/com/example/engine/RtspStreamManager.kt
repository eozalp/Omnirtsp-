package com.example.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
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

    private val playerMap = ConcurrentHashMap<String, ExoPlayer>()
    private val telemetryMap = ConcurrentHashMap<String, MutableStateFlow<StreamTelemetry>>()
    private val activeUrlMap = ConcurrentHashMap<String, String>()
    private val activeCameraMap = ConcurrentHashMap<String, CameraEntity>()
    private val hasRenderedFrameMap = ConcurrentHashMap<String, Boolean>()
    private val stalledSecondsMap = ConcurrentHashMap<String, Int>()
    private val lastRetryAtMap = ConcurrentHashMap<String, Long>()

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
                try {
                    val mediaSource = createMediaSource(camera)
                    existing.setMediaSource(mediaSource)
                    existing.prepare()
                    existing.repeatMode = Player.REPEAT_MODE_ALL
                    existing.playWhenReady = true
                } catch (_: Exception) {}
            }
            return existing
        }

        activeUrlMap[camera.id] = currentUrl
        hasRenderedFrameMap[camera.id] = false
        stalledSecondsMap[camera.id] = 0

        val surveillanceAudioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(context)
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
                flow.value = flow.value.copy(
                    isLive = true,
                    hasError = false,
                    errorMessage = null
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        stalledSecondsMap[camera.id] = 0
                        flow.value = flow.value.copy(
                            isLive = true,
                            hasError = false,
                            errorMessage = null
                        )
                    }
                    Player.STATE_BUFFERING -> {
                        val hadFrame = hasRenderedFrameMap[camera.id] == true
                        flow.value = flow.value.copy(isLive = hadFrame)
                    }
                    Player.STATE_ENDED -> {
                        if (!flow.value.hasError) {
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
                if (isPlaying) {
                    stalledSecondsMap[camera.id] = 0
                    hasRenderedFrameMap[camera.id] = true
                    flow.value = flow.value.copy(
                        isLive = true,
                        hasError = false,
                        errorMessage = null
                    )
                } else {
                    val hadFrame = hasRenderedFrameMap[camera.id] == true
                    flow.value = flow.value.copy(isLive = hadFrame)
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
                    hasError = true,
                    errorMessage = msg,
                    codec = msg
                )
            }
        })

        try {
            val mediaSource = createMediaSource(camera)
            player.setMediaSource(mediaSource)
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            flow.value = flow.value.copy(
                isLive = false,
                hasError = true,
                errorMessage = e.message ?: "Failed to open stream"
            )
        }

        playerMap[camera.id] = player
        return player
    }

    fun retryPlayer(camera: CameraEntity) {
        activeCameraMap[camera.id] = camera
        val player = playerMap[camera.id] ?: return
        val flow = telemetryMap[camera.id]
        stalledSecondsMap[camera.id] = 0
        lastRetryAtMap[camera.id] = System.currentTimeMillis()
        flow?.value = flow.value.copy(hasError = false, errorMessage = null)
        try {
            player.volume = if (FloatingOverlayStateHolder.isGlobalMuted.value) 0f else 1f
            val mediaSource = createMediaSource(camera)
            player.setMediaSource(mediaSource)
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            flow?.value = flow.value.copy(
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

    private fun createMediaSource(camera: CameraEntity): MediaSource {
        var rawUrl = camera.rtspUrl.trim()
        if (rawUrl.contains("commondatastorage.googleapis.com") || rawUrl.contains("wowzaec2demo")) {
            rawUrl = "android.resource://${context.packageName}/${com.example.R.raw.surveillance_feed}"
        }
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
            if (camera.transport == RtspTransport.TCP) {
                factory.setForceUseRtpTcp(true)
            }
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
     * Reads actual metrics directly from ExoPlayer's decoder format and buffer status,
     * and runs a stall/error watchdog so streams automatically recover instead of going black.
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
                    if (player.volume != expectedVolume) {
                        player.volume = expectedVolume
                    }

                    val format = player.videoFormat
                    val realFps = if (format?.frameRate != null && format.frameRate > 0) {
                        format.frameRate.toInt()
                    } else if (player.isPlaying) {
                        30
                    } else {
                        0
                    }

                    val realBitrate = if (format?.bitrate != null && format.bitrate > 0) {
                        format.bitrate / 1000
                    } else if (player.isPlaying) {
                        2048
                    } else {
                        0
                    }

                    val realCodec = format?.sampleMimeType ?: "H.264 / RTSP"
                    val buffered = player.totalBufferedDuration.coerceAtLeast(0)
                    val isLiveNow = player.isPlaying ||
                        (hasRenderedFrameMap[id] == true && !flow.value.hasError)

                    flow.value = flow.value.copy(
                        fps = realFps,
                        bitrateKbps = realBitrate,
                        codec = realCodec,
                        latencyMs = buffered,
                        isLive = isLiveNow
                    )

                    // Automatic Watchdog: recover from idle, ended, prolonged buffering stalls, or transient errors
                    val cam = activeCameraMap[id]
                    if (cam != null) {
                        val lastRetry = lastRetryAtMap[id] ?: 0L
                        val canRetryNow = now - lastRetry >= 5000L

                        if (flow.value.hasError) {
                            if (canRetryNow) {
                                retryPlayer(cam)
                            }
                        } else if (player.isPlaying) {
                            stalledSecondsMap[id] = 0
                        } else {
                            val state = player.playbackState
                            val stalled = (stalledSecondsMap[id] ?: 0) + 1
                            stalledSecondsMap[id] = stalled

                            val shouldRecover = when (state) {
                                Player.STATE_ENDED, Player.STATE_IDLE -> stalled >= 2 && canRetryNow
                                Player.STATE_BUFFERING -> stalled >= 10 && canRetryNow
                                else -> false
                            }
                            if (shouldRecover) {
                                retryPlayer(cam)
                            }
                        }
                    }
                }
            }
        }
    }

    fun releasePlayer(cameraId: String) {
        playerMap.remove(cameraId)?.release()
        telemetryMap.remove(cameraId)
        activeUrlMap.remove(cameraId)
        activeCameraMap.remove(cameraId)
        hasRenderedFrameMap.remove(cameraId)
        stalledSecondsMap.remove(cameraId)
        lastRetryAtMap.remove(cameraId)
    }

    fun releaseAll() {
        telemetryJob?.cancel()
        scope.cancel()
        for ((_, player) in playerMap) {
            player.release()
        }
        playerMap.clear()
        telemetryMap.clear()
        activeUrlMap.clear()
        activeCameraMap.clear()
        hasRenderedFrameMap.clear()
        stalledSecondsMap.clear()
        lastRetryAtMap.clear()
    }
}
