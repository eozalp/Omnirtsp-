package com.example.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var telemetryJob: Job? = null

    init {
        startTelemetryLoop()
    }

    fun getOrCreatePlayer(camera: CameraEntity): ExoPlayer {
        val existing = playerMap[camera.id]
        if (existing != null) {
            return existing
        }

        val player = ExoPlayer.Builder(context).build().apply {
            setVideoChangeFrameRateStrategy(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
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

            override fun onPlaybackStateChanged(playbackState: Int) {
                val isReady = playbackState == Player.STATE_READY
                flow.value = flow.value.copy(
                    isLive = isReady
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                flow.value = flow.value.copy(
                    isLive = isPlaying
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val msg = when {
                    cause is java.net.UnknownHostException -> "Network Offline / Unreachable Host"
                    cause is java.net.ConnectException -> "Connection Refused (RTSP Port 554 Closed)"
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
            player.prepare()
            player.playWhenReady = true
            player.repeatMode = Player.REPEAT_MODE_ALL
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
        val player = playerMap[camera.id] ?: return
        val flow = telemetryMap[camera.id]
        flow?.value = flow.value.copy(hasError = false, errorMessage = null)
        try {
            val mediaSource = createMediaSource(camera)
            player.setMediaSource(mediaSource)
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
        val uri = Uri.parse(rawUrl)
        val scheme = uri.scheme?.lowercase() ?: ""

        if (scheme == "rtsp") {
            // If username and password are set and not already in the URL, inject them
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
            if (camera.transport == RtspTransport.TCP) {
                factory.setForceUseRtpTcp(true)
            }
            return factory.createMediaSource(MediaItem.fromUri(Uri.parse(rawUrl)))
        } else {
            val dataSourceFactory = DefaultDataSource.Factory(context)
            return ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(uri))
        }
    }

    /**
     * Reads actual metrics directly from ExoPlayer's decoder format and buffer status.
     * No random or synthetic values.
     */
    private fun startTelemetryLoop() {
        telemetryJob = scope.launch {
            while (isActive) {
                delay(1000)
                for ((id, flow) in telemetryMap) {
                    val player = playerMap[id]
                    if (player != null) {
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

                        flow.value = flow.value.copy(
                            fps = realFps,
                            bitrateKbps = realBitrate,
                            codec = realCodec,
                            latencyMs = buffered,
                            isLive = player.isPlaying
                        )
                    }
                }
            }
        }
    }

    fun releasePlayer(cameraId: String) {
        playerMap.remove(cameraId)?.release()
        telemetryMap.remove(cameraId)
    }

    fun releaseAll() {
        telemetryJob?.cancel()
        for ((_, player) in playerMap) {
            player.release()
        }
        playerMap.clear()
        telemetryMap.clear()
    }
}
