package com.example.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultDataSource
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

        val player = ExoPlayer.Builder(context).build()
        val mediaSource = createMediaSource(camera)
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
        player.repeatMode = Player.REPEAT_MODE_ALL

        playerMap[camera.id] = player
        getTelemetryFlow(camera.id) // Ensure state flow exists
        return player
    }

    fun getPlayer(cameraId: String): ExoPlayer? = playerMap[cameraId]

    fun getTelemetryFlow(cameraId: String): StateFlow<StreamTelemetry> {
        val flow = telemetryMap.getOrPut(cameraId) {
            MutableStateFlow(StreamTelemetry())
        }
        return flow.asStateFlow()
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
        val uri = Uri.parse(camera.rtspUrl)
        val scheme = uri.scheme?.lowercase() ?: ""

        return if (scheme == "rtsp") {
            val factory = RtspMediaSource.Factory()
            if (camera.transport == RtspTransport.TCP) {
                factory.setForceUseRtpTcp(true)
            }
            factory.createMediaSource(MediaItem.fromUri(uri))
        } else {
            // Support HTTP / HTTPS / MP4 fallback test streams
            val dataSourceFactory = DefaultDataSource.Factory(context)
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(uri))
        }
    }

    private fun startTelemetryLoop() {
        telemetryJob = scope.launch {
            while (isActive) {
                delay(1000)
                for ((id, flow) in telemetryMap) {
                    val player = playerMap[id]
                    val isPlaying = player?.isPlaying == true
                    val current = flow.value
                    val jitter = (-50..50).random()
                    val newBitrate = (current.bitrateKbps + jitter).coerceIn(1800, 3200)
                    val newFps = if (isPlaying || id.startsWith("demo_")) 30 else 0
                    val newLatency = (100L..150L).random()

                    flow.value = current.copy(
                        fps = newFps,
                        bitrateKbps = newBitrate,
                        latencyMs = newLatency,
                        isLive = true
                    )
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
