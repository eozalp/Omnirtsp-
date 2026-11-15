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
    val location: String = "Entrance",
    val isEnabled: Boolean = true,
    val rotationDegrees: Float = 0f,
    val onvifPort: Int = 80,
    val ptzProfileToken: String = ""
)

data class StreamTelemetry(
    val fps: Int = 0,
    val bitrateKbps: Int = 0,
    val resolution: String = "",
    val codec: String = "",
    val latencyMs: Long = 0L,
    val isLive: Boolean = false,
    val isReconnecting: Boolean = false,
    val isRecording: Boolean = false,
    val isMotionDetected: Boolean = false,
    val hasError: Boolean = false,
    val errorMessage: String? = null
)
