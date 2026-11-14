package com.example.engine

import android.content.Context
import android.os.StatFs
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
import kotlinx.coroutines.delay
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

    private val _detailedConnectionResult = MutableStateFlow<StorageConnectionTestResult?>(null)
    val detailedConnectionResult: StateFlow<StorageConnectionTestResult?> = _detailedConnectionResult.asStateFlow()

    private val _extendedConfig = MutableStateFlow(RemoteStoragePreferences.load(context))
    val extendedConfig: StateFlow<ExtendedStorageConfig> = _extendedConfig.asStateFlow()

    private val _isScanningStorage = MutableStateFlow(false)
    val isScanningStorage: StateFlow<Boolean> = _isScanningStorage.asStateFlow()

    private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredStorageEndpoint>>(emptyList())
    val discoveredEndpoints: StateFlow<List<DiscoveredStorageEndpoint>> = _discoveredEndpoints.asStateFlow()

    fun saveExtendedConfig(config: ExtendedStorageConfig) {
        _extendedConfig.value = config
        RemoteStoragePreferences.save(context, config)
    }

    fun scanNetworkStorage() {
        if (_isScanningStorage.value) return
        scope.launch {
            _isScanningStorage.value = true
            try {
                _discoveredEndpoints.value = RemoteStorageDiscovery.discoverStorageServers()
            } catch (_: Exception) {
                _discoveredEndpoints.value = emptyList()
            } finally {
                _isScanningStorage.value = false
            }
        }
    }

    fun checkFreeSpaceMb(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024L * 1024L)
        } catch (_: Exception) {
            0L
        }
    }

    fun uploadClipToCloud(clip: RecordedClipEntity, forced: Boolean = false) {
        scope.launch {
            val config = database.cloudConfigDao().getCloudConfigSync() ?: CloudConfigEntity()
            val ext = _extendedConfig.value

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
                if (!file.exists() || file.length() == 0L) {
                    throw IllegalStateException("Recording file missing or empty: ${file.name}")
                }

                val relativePath = RemoteStorageProtocolClient.buildRemoteRelativePath(
                    layout = ext.folderLayout,
                    cameraName = clip.cameraName,
                    timestampMs = clip.timestamp,
                    fileName = file.name
                )

                var lastErr: Throwable? = null
                var uploadedUrl: String? = null
                var statusLabel = "OK"
                val maxAttempts = (ext.uploadRetries + 1).coerceIn(1, 6)

                for (attempt in 1..maxAttempts) {
                    try {
                        when {
                            config.provider == CloudProviderType.SMB_CIFS -> {
                                if (config.safFolderUri.isNotBlank()) {
                                    _syncProgress.value = AppLocaleManager.getString(
                                        context,
                                        R.string.cloud_uploading_kb,
                                        (file.length() / 1024).toInt(),
                                        "SMB/SAF"
                                    )
                                    val safRes = DeviceStorageExporter.exportClipToSafTree(
                                        context = context,
                                        sourceFile = file,
                                        treeUriString = config.safFolderUri
                                    )
                                    val uri = safRes.getOrThrow()
                                    uploadedUrl = uri.toString()
                                    statusLabel = "SAF-SMB"
                                } else {
                                    throw IllegalStateException(
                                        "SMB direct file write requires selecting your mounted SMB share folder via Android Files (SAF), or switching NAS protocol to FTP / WebDAV."
                                    )
                                }
                            }

                            config.provider == CloudProviderType.FTP_NAS_STORAGE &&
                                ext.nasSubProtocol == NasSubProtocol.FTP -> {
                                val host = ext.nasHost.ifBlank { RemoteStorageProtocolClient.extractHost(config.endpointUrl) }
                                _syncProgress.value = AppLocaleManager.getString(
                                    context,
                                    R.string.cloud_uploading_kb,
                                    (file.length() / 1024).toInt(),
                                    "FTP"
                                )
                                val ftpRes = RemoteStorageProtocolClient.uploadViaFtp(
                                    host = host,
                                    port = ext.nasPort,
                                    username = config.nasUsername,
                                    password = config.nasPassword,
                                    baseDir = ext.nasRemotePath,
                                    relativePath = relativePath,
                                    file = file
                                )
                                uploadedUrl = ftpRes.getOrThrow()
                                statusLabel = "FTP 226"
                            }

                            config.provider == CloudProviderType.FTP_NAS_STORAGE &&
                                ext.nasSubProtocol == NasSubProtocol.SFTP_PROBE -> {
                                throw IllegalStateException(
                                    "SFTP key/SSH stream requires FTP, WebDAV, S3, or mounted SAF share for unattended uploads."
                                )
                            }

                            else -> {
                                val request = buildUploadRequest(config, ext, clip, file, relativePath)
                                _syncProgress.value = AppLocaleManager.getString(
                                    context,
                                    R.string.cloud_uploading_kb,
                                    (file.length() / 1024).toInt(),
                                    config.provider.name
                                )
                                val response = withContext(Dispatchers.IO) {
                                    httpClient.newCall(request).execute()
                                }
                                response.use { resp ->
                                    if (!resp.isSuccessful) {
                                        throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
                                    }
                                    uploadedUrl = resp.header("Location")
                                        ?: getTargetUrlForProvider(config, ext, relativePath)
                                    statusLabel = "HTTP ${resp.code}"
                                }
                            }
                        }
                        if (uploadedUrl != null) break
                    } catch (e: Throwable) {
                        lastErr = e
                        if (attempt < maxAttempts) {
                            delay(800L * attempt)
                        }
                    }
                }

                val finalUrl = uploadedUrl ?: throw (lastErr ?: IllegalStateException("Upload failed"))

                database.recordedClipDao().updateCloudSyncStatus(
                    clipId = clip.id,
                    status = CloudSyncStatus.SYNCED,
                    cloudUrl = finalUrl,
                    provider = config.provider.name
                )
                database.cloudConfigDao().insertOrUpdateConfig(
                    config.copy(lastSyncTimestamp = System.currentTimeMillis())
                )

                if (ext.deleteLocalAfterUpload && file.exists()) {
                    // Only remove if internal copy; metadata keeps cloud URL
                    runCatching { file.delete() }
                }

                _syncProgress.value = AppLocaleManager.getString(
                    context,
                    R.string.cloud_synced_http,
                    config.provider.name,
                    statusLabel.filter { it.isDigit() }.toIntOrNull() ?: 200
                )
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

    private fun getTargetUrlForProvider(
        config: CloudConfigEntity,
        ext: ExtendedStorageConfig,
        relativePath: String
    ): String {
        return when (config.provider) {
            CloudProviderType.AWS_S3_COMPATIBLE -> {
                val base = config.s3Endpoint.trim().trimEnd('/')
                val bucket = config.s3Bucket.trim().ifBlank { config.bucketOrFolderName }.trim('/')
                "$base/$bucket/$relativePath"
            }
            CloudProviderType.WEBDAV -> {
                val base = config.endpointUrl.trim().trimEnd('/')
                "$base/$relativePath"
            }
            CloudProviderType.GOOGLE_DRIVE -> {
                "https://www.googleapis.com/drive/v3/files/${relativePath.substringAfterLast('/')}"
            }
            CloudProviderType.FTP_NAS_STORAGE -> {
                if (ext.nasSubProtocol == NasSubProtocol.HTTP_GATEWAY) {
                    val base = config.endpointUrl.trim().trimEnd('/')
                    "$base/$relativePath"
                } else {
                    val host = ext.nasHost.ifBlank { RemoteStorageProtocolClient.extractHost(config.endpointUrl) }
                    "ftp://$host:${ext.nasPort}/${ext.nasRemotePath.trim('/')}/$relativePath"
                }
            }
            CloudProviderType.SMB_CIFS -> {
                val share = ext.smbShare.trim().ifBlank { "recordings" }
                "smb://${ext.smbHost}/$share/${ext.smbSubfolder.trim('/')}/$relativePath"
            }
            CloudProviderType.WEBHOOK_REST_API -> config.endpointUrl.trim()
        }
    }

    private fun buildUploadRequest(
        config: CloudConfigEntity,
        ext: ExtendedStorageConfig,
        clip: RecordedClipEntity,
        file: File,
        relativePath: String
    ): Request {
        val fileBody = file.asRequestBody("video/mp4".toMediaTypeOrNull())

        return when (config.provider) {
            CloudProviderType.AWS_S3_COMPATIBLE -> {
                val endpoint = config.s3Endpoint.trim().ifBlank { "https://s3.amazonaws.com" }.trimEnd('/')
                val bucket = config.s3Bucket.trim().ifBlank { config.bucketOrFolderName }.trim('/')
                val uploadUrl = "$endpoint/$bucket/$relativePath"
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
                val uploadUrl = getTargetUrlForProvider(config, ext, relativePath)
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
                val target = if (config.endpointUrl.contains("googleapis.com") && config.endpointUrl.contains("upload")) {
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

            CloudProviderType.WEBHOOK_REST_API, CloudProviderType.SMB_CIFS -> {
                val targetUrl = config.endpointUrl.trim()
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("clipId", clip.id)
                    .addFormDataPart("cameraId", clip.cameraId)
                    .addFormDataPart("cameraName", clip.cameraName)
                    .addFormDataPart("remotePath", relativePath)
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

    fun testCloudConnection(
        config: CloudConfigEntity,
        ext: ExtendedStorageConfig = _extendedConfig.value
    ) {
        scope.launch {
            _isTestingConnection.value = true
            _detailedConnectionResult.value = null
            _lastConnectionTestResult.value = AppLocaleManager.getString(context, R.string.cloud_auth_progress, config.provider.name)

            val startTime = System.currentTimeMillis()
            try {
                when (config.provider) {
                    CloudProviderType.SMB_CIFS -> {
                        val res = RemoteStorageProtocolClient.testSmbConnection(
                            context = context,
                            host = ext.smbHost,
                            port = ext.smbPort,
                            share = ext.smbShare,
                            subfolder = ext.smbSubfolder,
                            workgroup = ext.smbWorkgroup,
                            username = ext.smbUsername,
                            password = ext.smbPassword,
                            safTreeUri = config.safFolderUri
                        )
                        _detailedConnectionResult.value = res
                        _lastConnectionTestResult.value = if (res.success) {
                            "Handshake OK: ${res.summary}\n${res.details}"
                        } else {
                            "${res.summary}\n${res.details}"
                        }
                        return@launch
                    }

                    CloudProviderType.FTP_NAS_STORAGE -> {
                        val host = ext.nasHost.ifBlank { RemoteStorageProtocolClient.extractHost(config.endpointUrl) }
                        when (ext.nasSubProtocol) {
                            NasSubProtocol.FTP -> {
                                val res = RemoteStorageProtocolClient.testFtpConnection(
                                    host = host,
                                    port = ext.nasPort,
                                    username = config.nasUsername,
                                    password = config.nasPassword
                                )
                                _detailedConnectionResult.value = res
                                _lastConnectionTestResult.value = if (res.success) {
                                    "Handshake OK: ${res.summary}\n${res.details}"
                                } else {
                                    "${res.summary}\n${res.details}"
                                }
                                return@launch
                            }
                            NasSubProtocol.SFTP_PROBE -> {
                                val port = if (ext.nasPort == 21) 22 else ext.nasPort
                                val res = RemoteStorageProtocolClient.testSftpBanner(host = host, port = port)
                                _detailedConnectionResult.value = res
                                _lastConnectionTestResult.value = if (res.success) {
                                    "Handshake OK: ${res.summary}\n${res.details}"
                                } else {
                                    "${res.summary}\n${res.details}"
                                }
                                return@launch
                            }
                            NasSubProtocol.HTTP_GATEWAY -> Unit
                        }
                    }

                    else -> Unit
                }

                // HTTP-based storage protocols (S3, WebDAV, Google Drive, HTTP NAS Gateway, Webhook)
                val testUrl = when (config.provider) {
                    CloudProviderType.AWS_S3_COMPATIBLE -> {
                        val endpoint = config.s3Endpoint.trim().ifBlank { "https://s3.amazonaws.com" }.trimEnd('/')
                        val bucket = config.s3Bucket.trim().ifBlank { config.bucketOrFolderName }.trim('/')
                        "$endpoint/$bucket"
                    }
                    CloudProviderType.GOOGLE_DRIVE -> "https://www.googleapis.com/drive/v3/about?fields=user"
                    else -> config.endpointUrl.trim()
                }

                if (testUrl.isBlank() || !testUrl.startsWith("http")) {
                    throw IllegalArgumentException("Endpoint must start with http:// or https:// (got: '$testUrl')")
                }

                val reqBuilder = Request.Builder().url(testUrl)
                if (config.provider == CloudProviderType.GOOGLE_DRIVE) {
                    reqBuilder.get()
                } else {
                    reqBuilder.head()
                }

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
                    CloudProviderType.SMB_CIFS -> Unit
                }

                if (config.apiKeyOrToken.isNotBlank() &&
                    config.provider != CloudProviderType.WEBDAV &&
                    config.provider != CloudProviderType.AWS_S3_COMPATIBLE &&
                    config.provider != CloudProviderType.GOOGLE_DRIVE
                ) {
                    reqBuilder.addHeader("Authorization", "Bearer ${config.apiKeyOrToken.trim()}")
                }

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(reqBuilder.build()).execute()
                }

                val latency = (System.currentTimeMillis() - startTime).toInt().coerceAtLeast(1)
                val code = response.code
                val msg = response.message
                val isReachableSuccess = code in 200..299 || code == 405
                response.close()

                _detailedConnectionResult.value = StorageConnectionTestResult(
                    success = isReachableSuccess,
                    protocolLabel = config.provider.name,
                    summary = "HTTP $code $msg (${latency}ms)",
                    details = "Endpoint: $testUrl",
                    latencyMs = latency
                )
                _lastConnectionTestResult.value = AppLocaleManager.getString(
                    context,
                    R.string.cloud_handshake_ok,
                    code,
                    msg,
                    latency,
                    config.provider.name
                )
            } catch (e: Exception) {
                val latency = (System.currentTimeMillis() - startTime).toInt().coerceAtLeast(1)
                val errMsg = e.localizedMessage ?: e.javaClass.simpleName
                _detailedConnectionResult.value = StorageConnectionTestResult(
                    success = false,
                    protocolLabel = config.provider.name,
                    summary = "Connection Failed (${latency}ms)",
                    details = errMsg,
                    latencyMs = latency
                )
                _lastConnectionTestResult.value = AppLocaleManager.getString(
                    context,
                    R.string.cloud_conn_failed,
                    latency,
                    errMsg
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
        _detailedConnectionResult.value = null
    }
}
