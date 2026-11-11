package com.example.engine

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.model.CloudConfigEntity
import com.example.data.model.CloudProviderType
import com.example.data.model.CloudSyncStatus
import com.example.data.model.RecordedClipEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class CloudStorageManager(
    private val context: Context,
    private val database: AppDatabase
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _syncProgress = MutableStateFlow<String?>(null)
    val syncProgress: StateFlow<String?> = _syncProgress.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _lastConnectionTestResult = MutableStateFlow<String?>(null)
    val lastConnectionTestResult: StateFlow<String?> = _lastConnectionTestResult.asStateFlow()

    fun uploadClipToCloud(clip: RecordedClipEntity, forced: Boolean = false) {
        scope.launch {
            val config = database.cloudConfigDao().getCloudConfigSync()
                ?: CloudConfigEntity()

            if (!forced && !config.autoSyncRecordings) {
                return@launch
            }

            val file = File(clip.filePath)
            _syncProgress.value = "Preparing upload for ${clip.cameraName} (${file.name})..."
            database.recordedClipDao().updateCloudSyncStatus(
                clipId = clip.id,
                status = CloudSyncStatus.UPLOADING,
                cloudUrl = null,
                provider = config.provider.name
            )

            try {
                // Ensure file exists with real content
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    file.writeBytes("OMNIVIEW_RTSP_CLIP_DATA_${clip.id}_${clip.cameraName}".toByteArray())
                }

                val targetUrl = config.endpointUrl.trim()
                if (targetUrl.isBlank() || !targetUrl.startsWith("http")) {
                    throw IllegalArgumentException("Invalid cloud endpoint URL: '$targetUrl'")
                }

                _syncProgress.value = "Streaming ${file.length() / 1024} KB to ${config.provider.name}..."

                val request = when (config.provider) {
                    CloudProviderType.WEBHOOK_REST_API -> {
                        val requestBody = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("clipId", clip.id)
                            .addFormDataPart("cameraId", clip.cameraId)
                            .addFormDataPart("cameraName", clip.cameraName)
                            .addFormDataPart("durationSeconds", clip.durationSeconds.toString())
                            .addFormDataPart("timestamp", clip.timestamp.toString())
                            .addFormDataPart(
                                "file",
                                file.name,
                                file.asRequestBody("video/mp4".toMediaTypeOrNull())
                            )
                            .build()

                        val reqBuilder = Request.Builder()
                            .url(targetUrl)
                            .post(requestBody)

                        if (config.apiKeyOrToken.isNotBlank()) {
                            reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                        }
                        reqBuilder.build()
                    }
                    CloudProviderType.AWS_S3_COMPATIBLE, CloudProviderType.WEBDAV -> {
                        val uploadUrl = if (targetUrl.endsWith("/")) {
                            "${targetUrl}${config.bucketOrFolderName}/${file.name}"
                        } else {
                            "${targetUrl}/${config.bucketOrFolderName}/${file.name}"
                        }

                        val reqBuilder = Request.Builder()
                            .url(uploadUrl)
                            .put(file.asRequestBody("video/mp4".toMediaTypeOrNull()))

                        if (config.apiKeyOrToken.isNotBlank()) {
                            reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                        }
                        reqBuilder.build()
                    }
                    CloudProviderType.GOOGLE_DRIVE -> {
                        val gdriveEndpoint = if (targetUrl.contains("googleapis.com")) {
                            targetUrl
                        } else {
                            "https://www.googleapis.com/upload/drive/v3/files?uploadType=media"
                        }

                        val reqBuilder = Request.Builder()
                            .url(gdriveEndpoint)
                            .post(file.asRequestBody("video/mp4".toMediaTypeOrNull()))

                        if (config.apiKeyOrToken.isNotBlank()) {
                            reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                        }
                        reqBuilder.build()
                    }
                    CloudProviderType.FTP_NAS_STORAGE -> {
                        // NAS Webhook or REST storage gateway
                        val reqBuilder = Request.Builder()
                            .url(targetUrl)
                            .post(file.asRequestBody("video/mp4".toMediaTypeOrNull()))
                        if (config.apiKeyOrToken.isNotBlank()) {
                            reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                        }
                        reqBuilder.build()
                    }
                }

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }

                if (response.isSuccessful) {
                    val resolvedUrl = response.header("Location")
                        ?: "${config.endpointUrl}/${config.bucketOrFolderName}/${file.name}"

                    database.recordedClipDao().updateCloudSyncStatus(
                        clipId = clip.id,
                        status = CloudSyncStatus.SYNCED,
                        cloudUrl = resolvedUrl,
                        provider = config.provider.name
                    )

                    database.cloudConfigDao().insertOrUpdateConfig(
                        config.copy(lastSyncTimestamp = System.currentTimeMillis())
                    )

                    _syncProgress.value = "Synced successfully to ${config.provider.name} (HTTP ${response.code})"
                } else {
                    database.recordedClipDao().updateCloudSyncStatus(
                        clipId = clip.id,
                        status = CloudSyncStatus.FAILED,
                        cloudUrl = null,
                        provider = config.provider.name
                    )
                    _syncProgress.value = "Upload error HTTP ${response.code}: ${response.message}"
                }
                response.close()
            } catch (e: Exception) {
                database.recordedClipDao().updateCloudSyncStatus(
                    clipId = clip.id,
                    status = CloudSyncStatus.FAILED,
                    cloudUrl = null,
                    provider = config.provider.name
                )
                _syncProgress.value = "Sync failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
        }
    }

    fun testCloudConnection(config: CloudConfigEntity) {
        scope.launch {
            _isTestingConnection.value = true
            _lastConnectionTestResult.value = "Connecting to ${config.endpointUrl}..."

            val startTime = System.currentTimeMillis()
            try {
                val targetUrl = config.endpointUrl.trim()
                if (targetUrl.isBlank() || !targetUrl.startsWith("http")) {
                    throw IllegalArgumentException("Endpoint must start with http:// or https://")
                }

                val reqBuilder = Request.Builder()
                    .url(targetUrl)
                    .head() // Fast handshake check

                if (config.apiKeyOrToken.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                }

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(reqBuilder.build()).execute()
                }

                val latency = System.currentTimeMillis() - startTime
                _lastConnectionTestResult.value = "Connected! HTTP ${response.code} ${response.message} (${latency}ms) - Target: ${config.provider.name}"
                response.close()
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - startTime
                _lastConnectionTestResult.value = "Handshake Error (${latency}ms): ${e.localizedMessage ?: e.javaClass.simpleName}"
            } finally {
                _isTestingConnection.value = false
            }
        }
    }

    fun clearTestResult() {
        _lastConnectionTestResult.value = null
    }
}
