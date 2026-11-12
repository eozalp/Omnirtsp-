package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ClipTriggerType {
    MANUAL,
    MOTION_ALERT,
    SCHEDULED
}

enum class CloudSyncStatus {
    LOCAL_ONLY,
    PENDING_UPLOAD,
    UPLOADING,
    SYNCED,
    FAILED
}

@Entity(tableName = "recorded_clips")
data class RecordedClipEntity(
    @PrimaryKey val id: String,
    val cameraId: String,
    val cameraName: String,
    val timestamp: Long,
    val durationSeconds: Int,
    val fileSizeMb: Float,
    val triggerType: ClipTriggerType,
    val motionScore: Int = 0,
    val filePath: String,
    val localExportUri: String? = null,
    val cloudSyncStatus: CloudSyncStatus = CloudSyncStatus.LOCAL_ONLY,
    val cloudStorageUrl: String? = null,
    val cloudProvider: String? = null
)
