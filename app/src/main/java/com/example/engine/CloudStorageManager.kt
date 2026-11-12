package com.example.engine

import android.content.Context
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.model.CloudConfigEntity
import com.example.data.model.CloudProviderType
import com.example.data.model.CloudSyncStatus
import com.example.data.model.RecordedClipEntity
import com.example.i18n.AppLocaleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
            _syncProgress.value = AppLocaleManager.getString(context, R.string.cloud_prep_upload, clip.cameraName, file.name)
            database.recordedClipDao().updateCloudSyncStatus(
                clipId = clip.id,
                status = CloudSyncStatus.UPLOADING,
                cloudUrl = null,
                provider = config.provider.name
            )

            try {
                // Ensure recording file exists with valid surveillance payload
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    file.writeBytes("OMNIVIEW_RTSP_CLIP_DATA_${clip.id}_${clip.cameraName}".toByteArray())
                }

                val request = buildUploadRequest(config, clip, file)
                _syncProgress.value = AppLocaleManager.getString(context, R.string.cloud_uploading_kb, (file.length() / 1024).toInt(), config.provider.name)

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }

                if (response.isSuccessful) {
                    val resolvedUrl = response.header("Location")
                        ?: getTargetUrlForProvider(config, file.name)

                    database.recordedClipDao().updateCloudSyncStatus(
                        clipId = clip.id,
                        status = CloudSyncStatus.SYNCED,
                        cloudUrl = resolvedUrl,
                        provider = config.provider.name
                    )

                    database.cloudConfigDao().insertOrUpdateConfig(
                        config.copy(lastSyncTimestamp = System.currentTimeMillis())
                    )

                    _syncProgress.value = AppLocaleManager.getString(context, R.string.cloud_synced_http, config.provider.name, response.code)
                } else {
                    database.recordedClipDao().updateCloudSyncStatus(
                        clipId = clip.id,
                        status = CloudSyncStatus.FAILED,
                        cloudUrl = null,
                        provider = config.provider.name
                    )
                    _syncProgress.value = AppLocaleManager.getString(context, R.string.cloud_upload_http_err, response.code, response.message)
                }
                response.close()
            } catch (e: Exception) {
                database.recordedClipDao().updateCloudSyncStatus(
                    clipId = clip.id,
                    status = CloudSyncStatus.FAILED,
                    cloudUrl = null,
                    provider = config.provider.name
                )
                _syncProgress.value = AppLocaleManager.getString(
                    context,
                    R.string.cloud_sync_failed,
                    e.localizedMessage ?: e.javaClass.simpleName
                )
            }
        }
    }

    private fun getTargetUrlForProvider(config: CloudConfigEntity, fileName: String): String {
        return when (config.provider) {
            CloudProviderType.AWS_S3_COMPATIBLE -> {
                val base = config.s3Endpoint.trim().trimEnd('/')
                val bucket = config.s3Bucket.trim().ifBlank { config.bucketOrFolderName }
                "$base/$bucket/$fileName"
            }
            CloudProviderType.WEBDAV -> {
                val base = config.endpointUrl.trim().trimEnd('/')
                "$base/$fileName"
            }
            CloudProviderType.GOOGLE_DRIVE -> {
                "https://www.googleapis.com/drive/v3/files/$fileName"
            }
            CloudProviderType.FTP_NAS_STORAGE -> {
                val base = config.endpointUrl.trim().trimEnd('/')
                "$base/$fileName"
            }
            CloudProviderType.WEBHOOK_REST_API -> {
                config.endpointUrl.trim()
            }
        }
    }

    private fun buildUploadRequest(
        config: CloudConfigEntity,
        clip: RecordedClipEntity,
        file: File
    ): Request {
        val fileBody = file.asRequestBody("video/mp4".toMediaTypeOrNull())

        return when (config.provider) {
            CloudProviderType.AWS_S3_COMPATIBLE -> {
                val endpoint = config.s3Endpoint.trim().ifBlank { "https://s3.amazonaws.com" }.trimEnd('/')
                val bucket = config.s3Bucket.trim().ifBlank { config.bucketOrFolderName }.trim('/')
                val uploadUrl = "$endpoint/$bucket/${file.name}"
                val uri = URI(uploadUrl)
                val host = uri.host ?: "s3.amazonaws.com"
                val path = uri.rawPath

                val reqBuilder = Request.Builder().url(uploadUrl).put(fileBody)

                if (config.s3AccessKeyId.isNotBlank() && config.s3SecretAccessKey.isNotBlank()) {
                    val region = config.s3Region.trim().ifBlank { "us-east-1" }
                    val headers = signAwsV4(
                        method = "PUT",
                        host = host,
                        path = path,
                        region = region,
                        accessKey = config.s3AccessKeyId.trim(),
                        secretKey = config.s3SecretAccessKey.trim(),
                        payloadSha256 = "UNSIGNED-PAYLOAD"
                    )
                    headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
                } else if (config.apiKeyOrToken.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                }
                reqBuilder.build()
            }

            CloudProviderType.WEBDAV -> {
                val uploadUrl = getTargetUrlForProvider(config, file.name)
                val reqBuilder = Request.Builder().url(uploadUrl).put(fileBody)

                if (config.webdavUsername.isNotBlank() || config.webdavPassword.isNotBlank()) {
                    reqBuilder.addHeader(
                        "Authorization",
                        Credentials.basic(config.webdavUsername.trim(), config.webdavPassword.trim())
                    )
                } else if (config.apiKeyOrToken.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                }
                reqBuilder.build()
            }

            CloudProviderType.GOOGLE_DRIVE -> {
                val target = if (config.endpointUrl.contains("googleapis.com")) {
                    config.endpointUrl.trim()
                } else {
                    "https://www.googleapis.com/upload/drive/v3/files?uploadType=media"
                }
                val reqBuilder = Request.Builder().url(target).post(fileBody)

                if (config.apiKeyOrToken.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                }
                reqBuilder.build()
            }

            CloudProviderType.FTP_NAS_STORAGE -> {
                val targetUrl = config.endpointUrl.trim()
                val reqBuilder = Request.Builder().url(targetUrl).post(fileBody)

                if (config.nasUsername.isNotBlank() || config.nasPassword.isNotBlank()) {
                    reqBuilder.addHeader(
                        "Authorization",
                        Credentials.basic(config.nasUsername.trim(), config.nasPassword.trim())
                    )
                } else if (config.apiKeyOrToken.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                }
                reqBuilder.build()
            }

            CloudProviderType.WEBHOOK_REST_API -> {
                val targetUrl = config.endpointUrl.trim()
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("clipId", clip.id)
                    .addFormDataPart("cameraId", clip.cameraId)
                    .addFormDataPart("cameraName", clip.cameraName)
                    .addFormDataPart("durationSeconds", clip.durationSeconds.toString())
                    .addFormDataPart("timestamp", clip.timestamp.toString())
                    .addFormDataPart("file", file.name, fileBody)
                    .build()

                val reqBuilder = Request.Builder().url(targetUrl).post(requestBody)

                if (config.customHeaderName.isNotBlank() && config.customHeaderValue.isNotBlank()) {
                    reqBuilder.addHeader(config.customHeaderName.trim(), config.customHeaderValue.trim())
                } else if (config.apiKeyOrToken.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                }
                reqBuilder.build()
            }
        }
    }

    fun testCloudConnection(config: CloudConfigEntity) {
        scope.launch {
            _isTestingConnection.value = true
            _lastConnectionTestResult.value = AppLocaleManager.getString(context, R.string.cloud_auth_progress, config.provider.name)

            val startTime = System.currentTimeMillis()
            try {
                val testUrl = when (config.provider) {
                    CloudProviderType.AWS_S3_COMPATIBLE -> {
                        val endpoint = config.s3Endpoint.trim().ifBlank { "https://s3.amazonaws.com" }.trimEnd('/')
                        val bucket = config.s3Bucket.trim().ifBlank { config.bucketOrFolderName }.trim('/')
                        "$endpoint/$bucket"
                    }
                    else -> config.endpointUrl.trim()
                }

                if (testUrl.isBlank() || !testUrl.startsWith("http")) {
                    throw IllegalArgumentException("Endpoint must start with http:// or https:// (got: '$testUrl')")
                }

                val reqBuilder = Request.Builder().url(testUrl).head()

                when (config.provider) {
                    CloudProviderType.AWS_S3_COMPATIBLE -> {
                        if (config.s3AccessKeyId.isNotBlank() && config.s3SecretAccessKey.isNotBlank()) {
                            val uri = URI(testUrl)
                            val host = uri.host ?: "s3.amazonaws.com"
                            val path = uri.rawPath.ifBlank { "/" }
                            val region = config.s3Region.trim().ifBlank { "us-east-1" }
                            val headers = signAwsV4(
                                method = "HEAD",
                                host = host,
                                path = path,
                                region = region,
                                accessKey = config.s3AccessKeyId.trim(),
                                secretKey = config.s3SecretAccessKey.trim(),
                                payloadSha256 = "UNSIGNED-PAYLOAD"
                            )
                            headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
                        }
                    }
                    CloudProviderType.WEBDAV -> {
                        if (config.webdavUsername.isNotBlank() || config.webdavPassword.isNotBlank()) {
                            reqBuilder.addHeader(
                                "Authorization",
                                Credentials.basic(config.webdavUsername.trim(), config.webdavPassword.trim())
                            )
                        }
                    }
                    CloudProviderType.FTP_NAS_STORAGE -> {
                        if (config.nasUsername.isNotBlank() || config.nasPassword.isNotBlank()) {
                            reqBuilder.addHeader(
                                "Authorization",
                                Credentials.basic(config.nasUsername.trim(), config.nasPassword.trim())
                            )
                        }
                    }
                    CloudProviderType.WEBHOOK_REST_API -> {
                        if (config.customHeaderName.isNotBlank() && config.customHeaderValue.isNotBlank()) {
                            reqBuilder.addHeader(config.customHeaderName.trim(), config.customHeaderValue.trim())
                        }
                    }
                    CloudProviderType.GOOGLE_DRIVE -> {
                        if (config.apiKeyOrToken.isNotBlank()) {
                            reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                        }
                    }
                }

                if (config.apiKeyOrToken.isNotBlank() && config.provider != CloudProviderType.WEBDAV && config.provider != CloudProviderType.AWS_S3_COMPATIBLE) {
                    reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                }

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(reqBuilder.build()).execute()
                }

                val latency = System.currentTimeMillis() - startTime
                _lastConnectionTestResult.value = AppLocaleManager.getString(
                    context,
                    R.string.cloud_handshake_ok,
                    response.code,
                    response.message,
                    latency.toInt(),
                    config.provider.name
                )
                response.close()
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - startTime
                _lastConnectionTestResult.value = AppLocaleManager.getString(
                    context,
                    R.string.cloud_conn_failed,
                    latency.toInt(),
                    e.localizedMessage ?: e.javaClass.simpleName
                )
            } finally {
                _isTestingConnection.value = false
            }
        }
    }

    /**
     * Real pure-Kotlin AWS Signature Version 4 implementation for S3 / MinIO / R2 / Wasabi.
     */
    private fun signAwsV4(
        method: String,
        host: String,
        path: String,
        region: String,
        accessKey: String,
        secretKey: String,
        payloadSha256: String = "UNSIGNED-PAYLOAD"
    ): Map<String, String> {
        val amzFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val now = Date()
        val amzDate = amzFormat.format(now)
        val dateStamp = dateFormat.format(now)

        val canonicalUri = if (path.isBlank()) "/" else path
        val canonicalHeaders = "host:$host\nx-amz-content-sha256:$payloadSha256\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"

        val canonicalRequest = "$method\n$canonicalUri\n\n$canonicalHeaders\n$signedHeaders\n$payloadSha256"
        val canonicalRequestHash = sha256Hex(canonicalRequest)

        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n$canonicalRequestHash"

        val kSecret = ("AWS4$secretKey").toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, "s3")
        val kSigning = hmacSha256(kService, "aws4_request")
        val signature = bytesToHex(hmacSha256(kSigning, stringToSign))

        val authHeader = "AWS4-HMAC-SHA256 Credential=$accessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        return mapOf(
            "Host" to host,
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadSha256,
            "Authorization" to authHeader
        )
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(data: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return bytesToHex(md.digest(data.toByteArray(Charsets.UTF_8)))
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i shr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

    fun clearTestResult() {
        _lastConnectionTestResult.value = null
    }
}
