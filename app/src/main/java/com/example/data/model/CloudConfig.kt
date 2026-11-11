package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CloudProviderType {
    GOOGLE_DRIVE,
    AWS_S3_COMPATIBLE,
    WEBHOOK_REST_API,
    FTP_NAS_STORAGE,
    WEBDAV
}

@Entity(tableName = "cloud_config")
data class CloudConfigEntity(
    @PrimaryKey val id: String = "primary_cloud_config",
    val provider: CloudProviderType = CloudProviderType.GOOGLE_DRIVE,
    val endpointUrl: String = "https://cloud.surveillance.storage/v1/sync",
    val bucketOrFolderName: String = "OmniView-RTSP-Clips",
    val apiKeyOrToken: String = "",
    val autoSyncRecordings: Boolean = true,
    val autoSyncMotionSnapshots: Boolean = true,
    val wifiOnly: Boolean = true,
    val retentionDays: Int = 30,
    val lastSyncTimestamp: Long = 0L,
    val isConnected: Boolean = true
)
