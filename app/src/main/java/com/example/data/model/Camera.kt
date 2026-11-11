package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RtspTransport {
    TCP,
    UDP,
    AUTO
}

enum class StreamQuality {
    MAIN_HD,
    SUB_SD
}

@Entity(tableName = "cameras")
data class CameraEntity(
    @PrimaryKey val id: String,
    val name: String,
    val rtspUrl: String,
    val username: String = "",
    val password: String = "",
    val transport: RtspTransport = RtspTransport.TCP,
    val quality: StreamQuality = StreamQuality.MAIN_HD,
    val isMotionDetectionEnabled: Boolean = true,
    val motionSensitivity: Int = 65, // 1 to 100
    val motionCooldownSeconds: Int = 10,
    val isDemoCamera: Boolean = false,
    val location: String = "Entrance",
    val isEnabled: Boolean = true
)

data class StreamTelemetry(
    val fps: Int = 30,
    val bitrateKbps: Int = 2450,
    val resolution: String = "1920x1080",
    val codec: String = "H.264 / AAC",
    val latencyMs: Long = 120,
    val isLive: Boolean = true,
    val isRecording: Boolean = false,
    val isMotionDetected: Boolean = false,
    val hasError: Boolean = false,
    val errorMessage: String? = null
)
