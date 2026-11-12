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

enum class LocalSaveDestination {
    APP_INTERNAL,
    PUBLIC_MOVIES,
    CUSTOM_SAF_FOLDER
}

@Entity(tableName = "cloud_config")
data class CloudConfigEntity(
    @PrimaryKey val id: String = "primary_cloud_config",
    val provider: CloudProviderType = CloudProviderType.GOOGLE_DRIVE,
    val endpointUrl: String = "https://cloud.surveillance.storage/v1/sync",
    val bucketOrFolderName: String = "OmniView-RTSP-Clips",
    val apiKeyOrToken: String = "",
    // Local / System File Manager destination options
    val localSaveDestination: LocalSaveDestination = LocalSaveDestination.PUBLIC_MOVIES,
    val safFolderUri: String = "",
    val safFolderDisplayName: String = "",
    val autoSaveToDeviceFolder: Boolean = true,
    // Real S3 / MinIO / R2 credentials
    val s3AccessKeyId: String = "",
    val s3SecretAccessKey: String = "",
    val s3Region: String = "us-east-1",
    val s3Bucket: String = "omniview-recordings",
    val s3Endpoint: String = "https://s3.amazonaws.com",
    // Real WebDAV / NextCloud credentials
    val webdavUsername: String = "",
    val webdavPassword: String = "",
    // Real NAS / Local Gateway credentials
    val nasUsername: String = "",
    val nasPassword: String = "",
    // Real Webhook / REST headers
    val customHeaderName: String = "Authorization",
    val customHeaderValue: String = "",
    // Policies & Rolling Storage Limit
    val autoSyncRecordings: Boolean = true,
    val autoSyncMotionSnapshots: Boolean = true,
    val wifiOnly: Boolean = true,
    val retentionEnabled: Boolean = true,
    val retentionDays: Int = 3,
    val retentionHours: Int = 0,
    val retentionMinutes: Int = 0,
    val lastSyncTimestamp: Long = 0L,
    val isConnected: Boolean = true
) {
    fun totalRetentionMillis(): Long {
        if (!retentionEnabled) return 0L
        val daysMs = retentionDays.coerceAtLeast(0).toLong() * 24L * 60L * 60L * 1000L
        val hoursMs = retentionHours.coerceAtLeast(0).toLong() * 60L * 60L * 1000L
        val minutesMs = retentionMinutes.coerceAtLeast(0).toLong() * 60L * 1000L
        return daysMs + hoursMs + minutesMs
    }

    fun formatRetentionWindow(): String {
        if (!retentionEnabled) return "Unlimited (Auto-replace off)"
        val d = retentionDays.coerceAtLeast(0)
        val h = retentionHours.coerceAtLeast(0)
        val m = retentionMinutes.coerceAtLeast(0)
        if (d == 0 && h == 0 && m == 0) return "Unlimited"
        return buildList {
            if (d > 0) add("${d}d")
            if (h > 0) add("${h}h")
            if (m > 0) add("${m}m")
        }.joinToString(" ")
    }
}
