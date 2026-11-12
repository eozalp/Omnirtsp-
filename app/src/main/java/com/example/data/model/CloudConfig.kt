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
    // Policies
    val autoSyncRecordings: Boolean = true,
    val autoSyncMotionSnapshots: Boolean = true,
    val wifiOnly: Boolean = true,
    val retentionDays: Int = 30,
    val lastSyncTimestamp: Long = 0L,
    val isConnected: Boolean = true
)
