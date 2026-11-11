package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RecordingMode {
    CONTINUOUS,
    MOTION_ONLY,
    ARMED_ALERT_ONLY
}

@Entity(tableName = "recording_schedules")
data class RecordingScheduleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val daysOfWeek: String, // Comma-separated: "MON,TUE,WED,THU,FRI,SAT,SUN"
    val startHour: Int, // 0..23
    val startMinute: Int, // 0..59
    val endHour: Int, // 0..23
    val endMinute: Int, // 0..59
    val mode: RecordingMode = RecordingMode.MOTION_ONLY,
    val isEnabled: Boolean = true,
    val targetCameraIds: String = "ALL", // "ALL" or comma separated camera IDs
    val cloudBackupOnFinish: Boolean = true
)
