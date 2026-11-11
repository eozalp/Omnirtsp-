package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MotionSeverity {
    LOW,
    MEDIUM,
    HIGH
}

@Entity(tableName = "motion_alerts")
data class MotionAlertEntity(
    @PrimaryKey val id: String,
    val cameraId: String,
    val cameraName: String,
    val timestamp: Long,
    val motionLevel: Int, // 1 to 100
    val severity: MotionSeverity,
    val description: String,
    val isRead: Boolean = false,
    val snapshotPath: String? = null,
    val recordingClipId: String? = null
)
