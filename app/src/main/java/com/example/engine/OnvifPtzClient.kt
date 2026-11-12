package com.example.engine

import android.content.Context
import com.example.R
import com.example.data.model.CameraEntity
import com.example.i18n.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class PtzCommandState(
    val cameraId: String = "",
    val isBusy: Boolean = false,
    val isMoving: Boolean = false,
    val activeAction: String = "PTZ Ready",
    val ptzServiceUrl: String = "",
    val profileToken: String = "",
    val lastError: String? = null
)

/**
 * Real ONVIF Profile S PTZ SOAP client.
 * Communicates directly with IP cameras over HTTP/HTTPS using WS-Security UsernameToken
 * (and HTTP Basic fallback) to drive actual camera Pan, Tilt, Zoom motors and presets.
 */
class OnvifPtzClient(private val context: Context? = null) {

    private fun msg(resId: Int, fallback: String, vararg args: Any): String {
        val ctx = context ?: return fallback
        return AppLocaleManager.getString(ctx, resId, *args)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val soapMediaType = "application/soap+xml; charset=utf-8".toMediaType()

    private val discoveredPtzUrlCache = ConcurrentHashMap<String, String>()
    private val discoveredMediaUrlCache = ConcurrentHashMap<String, String>()
    private val discoveredProfileTokenCache = ConcurrentHashMap<String, String>()

    private val _status = MutableStateFlow(PtzCommandState())
    val status: StateFlow<PtzCommandState> = _status.asStateFlow()

    suspend fun probeCamera(camera: CameraEntity): PtzCommandState = withContext(Dispatchers.IO) {
        val host = extractHost(camera.rtspUrl)
        if (host.isNullOrBlank()) {
            val errState = PtzCommandState(
                cameraId = camera.id,
                activeAction = msg(R.string.ptz_no_ip_action, "No IP/host in stream URL"),
                lastError = msg(R.string.ptz_no_ip_error, "Set a valid RTSP IP address (e.g. rtsp://192.168.1.100:554/...)")
            )
            _status.value = errState
            return@withContext errState
        }

        _status.value = _status.value.copy(
            cameraId = camera.id,
            isBusy = true,
            activeAction = msg(R.string.ptz_connecting_onvif, "Connecting ONVIF ($host:${camera.onvifPort})...", host, camera.onvifPort),
            lastError = null
        )

        val (ptzUrl, profileToken, err) = resolveEndpointAndProfile(camera, host)
        val result = if (err == null) {
            PtzCommandState(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_connected_profile, "Connected • Profile $profileToken", profileToken),
                ptzServiceUrl = ptzUrl,
                profileToken = profileToken,
                lastError = null
            )
        } else {
            PtzCommandState(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_unreachable, "ONVIF unreachable"),
                ptzServiceUrl = ptzUrl,
                profileToken = profileToken,
                lastError = err
            )
        }
        _status.value = result
        result
    }

    suspend fun continuousMove(
        camera: CameraEntity,
        panVelocity: Float,
        tiltVelocity: Float,
        zoomVelocity: Float,
        actionLabel: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val host = extractHost(camera.rtspUrl)
            ?: return@withContext reportNoHost(camera.id)

        _status.value = _status.value.copy(
            cameraId = camera.id,
            isBusy = true,
            isMoving = true,
            activeAction = actionLabel,
            lastError = null
        )

        val (ptzUrl, profileToken, resolveErr) = resolveEndpointAndProfile(camera, host)
        if (resolveErr != null && ptzUrl.isBlank()) {
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                lastError = resolveErr
            )
            return@withContext Result.failure(IllegalStateException(resolveErr))
        }

        val bodyXml = buildContinuousMoveBody(
            profileToken = profileToken,
            panX = panVelocity.coerceIn(-1f, 1f),
            tiltY = tiltVelocity.coerceIn(-1f, 1f),
            zoomX = zoomVelocity.coerceIn(-1f, 1f)
        )
        val response = executeSoap(ptzUrl, camera, SOAP_ACTION_CONTINUOUS_MOVE, bodyXml)
        response.onSuccess {
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = true,
                activeAction = actionLabel,
                ptzServiceUrl = ptzUrl,
                profileToken = profileToken,
                lastError = null
            )
        }.onFailure { err ->
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_failed, "PTZ failed"),
                ptzServiceUrl = ptzUrl,
                profileToken = profileToken,
                lastError = err.message ?: msg(R.string.ptz_rejected, "Camera rejected PTZ command")
            )
        }
        response.map { }
    }

    suspend fun stop(camera: CameraEntity): Result<Unit> = withContext(Dispatchers.IO) {
        val host = extractHost(camera.rtspUrl)
            ?: return@withContext reportNoHost(camera.id)

        val cacheKey = cacheKey(camera)
        val ptzUrl = discoveredPtzUrlCache[cacheKey] ?: defaultPtzUrl(host, camera.onvifPort)
        val profileToken = camera.ptzProfileToken.ifBlank {
            discoveredProfileTokenCache[cacheKey] ?: "Profile_1"
        }

        val bodyXml = buildStopBody(profileToken)
        val response = executeSoap(ptzUrl, camera, SOAP_ACTION_STOP, bodyXml)
        response.onSuccess {
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_stopped_ready, "Stopped • Ready"),
                ptzServiceUrl = ptzUrl,
                profileToken = profileToken,
                lastError = null
            )
        }.onFailure { err ->
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                lastError = err.message
            )
        }
        response.map { }
    }

    suspend fun gotoHome(camera: CameraEntity): Result<Unit> = withContext(Dispatchers.IO) {
        val host = extractHost(camera.rtspUrl)
            ?: return@withContext reportNoHost(camera.id)

        _status.value = _status.value.copy(
            cameraId = camera.id,
            isBusy = true,
            activeAction = msg(R.string.ptz_moving_home, "Moving to Home..."),
            lastError = null
        )
        val (ptzUrl, profileToken, _) = resolveEndpointAndProfile(camera, host)
        val bodyXml = """
            <tptz:GotoHomePosition>
                <tptz:ProfileToken>${escapeXml(profileToken)}</tptz:ProfileToken>
            </tptz:GotoHomePosition>
        """.trimIndent()
        val response = executeSoap(ptzUrl, camera, SOAP_ACTION_GOTO_HOME, bodyXml)
        response.onSuccess {
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_home_sent, "Home position command sent"),
                ptzServiceUrl = ptzUrl,
                profileToken = profileToken,
                lastError = null
            )
        }.onFailure { err ->
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_home_failed, "Home failed"),
                lastError = err.message
            )
        }
        response.map { }
    }

    suspend fun gotoPreset(camera: CameraEntity, presetToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        val host = extractHost(camera.rtspUrl)
            ?: return@withContext reportNoHost(camera.id)

        _status.value = _status.value.copy(
            cameraId = camera.id,
            isBusy = true,
            activeAction = msg(R.string.ptz_moving_preset, "Moving to Preset $presetToken...", presetToken),
            lastError = null
        )
        val (ptzUrl, profileToken, _) = resolveEndpointAndProfile(camera, host)
        val bodyXml = """
            <tptz:GotoPreset>
                <tptz:ProfileToken>${escapeXml(profileToken)}</tptz:ProfileToken>
                <tptz:PresetToken>${escapeXml(presetToken)}</tptz:PresetToken>
            </tptz:GotoPreset>
        """.trimIndent()
        val response = executeSoap(ptzUrl, camera, SOAP_ACTION_GOTO_PRESET, bodyXml)
        response.onSuccess {
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_preset_active, "Preset $presetToken active", presetToken),
                ptzServiceUrl = ptzUrl,
                profileToken = profileToken,
                lastError = null
            )
        }.onFailure { err ->
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_failed, "PTZ failed"),
                lastError = err.message
            )
        }
        response.map { }
    }

    suspend fun fetchRtspStreamUri(
        host: String,
        onvifPort: Int = 80,
        username: String = "",
        password: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val tempCam = CameraEntity(
            id = "probe_$host",
            name = host,
            rtspUrl = "rtsp://$host:554/",
            username = username.trim(),
            password = password.trim(),
            onvifPort = onvifPort.coerceIn(1, 65535)
        )
        val deviceUrl = "http://$host:${tempCam.onvifPort}/onvif/device_service"
        val capBody = """
            <tds:GetCapabilities>
                <tds:Category>All</tds:Category>
            </tds:GetCapabilities>
        """.trimIndent()
        var mediaUrl: String? = null
        executeSoap(deviceUrl, tempCam, SOAP_ACTION_GET_CAPABILITIES, capBody)
            .onSuccess { xml ->
                mediaUrl = extractXAddr(xml, "Media")
            }

        val targetMediaUrl = mediaUrl ?: "http://$host:${tempCam.onvifPort}/onvif/media_service"
        var profileToken: String? = null
        val profilesRes = executeSoap(targetMediaUrl, tempCam, SOAP_ACTION_GET_PROFILES, "<trt:GetProfiles/>")
        val profXml = profilesRes.getOrElse { err ->
            return@withContext Result.failure(err)
        }
        profileToken = extractFirstProfileToken(profXml) ?: "Profile_1"

        val streamUriBody = """
            <trt:GetStreamUri>
                <trt:StreamSetup>
                    <tt:Stream>RTP-Unicast</tt:Stream>
                    <tt:Transport>
                        <tt:Protocol>RTSP</tt:Protocol>
                    </tt:Transport>
                </trt:StreamSetup>
                <trt:ProfileToken>${escapeXml(profileToken)}</trt:ProfileToken>
            </trt:GetStreamUri>
        """.trimIndent()
        val uriRes = executeSoap(targetMediaUrl, tempCam, SOAP_ACTION_GET_STREAM_URI, streamUriBody)
        val uriXml = uriRes.getOrElse { err ->
            return@withContext Result.failure(err)
        }
        val rtspUri = extractStreamUriFromXml(uriXml)
            ?: return@withContext Result.failure(IllegalStateException("Camera returned no RTSP URI"))
        Result.success(rtspUri)
    }

    fun clearCache(cameraId: String) {
        discoveredPtzUrlCache.keys.filter { it.startsWith("$cameraId|") }.forEach {
            discoveredPtzUrlCache.remove(it)
            discoveredMediaUrlCache.remove(it)
            discoveredProfileTokenCache.remove(it)
        }
    }

    private fun reportNoHost(cameraId: String): Result<Unit> {
        val errorMsg = msg(R.string.ptz_no_ip_error, "Set a valid RTSP IP address (e.g. rtsp://192.168.1.100:554/...)")
        _status.value = PtzCommandState(
            cameraId = cameraId,
            activeAction = msg(R.string.ptz_no_ip_action, "No IP/host in stream URL"),
            lastError = errorMsg
        )
        return Result.failure(IllegalArgumentException(errorMsg))
    }

    private fun cacheKey(camera: CameraEntity): String =
        "${camera.id}|${camera.rtspUrl}|${camera.onvifPort}"

    private fun resolveEndpointAndProfile(camera: CameraEntity, host: String): Triple<String, String, String?> {
        val key = cacheKey(camera)
        var ptzUrl = discoveredPtzUrlCache[key]
        var mediaUrl = discoveredMediaUrlCache[key]
        var profileToken = camera.ptzProfileToken.ifBlank { discoveredProfileTokenCache[key] }
        var lastErr: String? = null

        if (ptzUrl.isNullOrBlank()) {
            val deviceUrl = "http://$host:${camera.onvifPort}/onvif/device_service"
            val capBody = """
                <tds:GetCapabilities>
                    <tds:Category>All</tds:Category>
                </tds:GetCapabilities>
            """.trimIndent()
            val capRes = executeSoap(deviceUrl, camera, SOAP_ACTION_GET_CAPABILITIES, capBody)
            capRes.onSuccess { xml ->
                extractXAddr(xml, "PTZ")?.let { ptzUrl = it }
                extractXAddr(xml, "Media")?.let { mediaUrl = it }
            }.onFailure { err ->
                lastErr = err.message
            }
            if (ptzUrl.isNullOrBlank()) {
                ptzUrl = defaultPtzUrl(host, camera.onvifPort)
            } else {
                discoveredPtzUrlCache[key] = ptzUrl!!
            }
            if (!mediaUrl.isNullOrBlank()) {
                discoveredMediaUrlCache[key] = mediaUrl!!
            }
        }

        if (profileToken.isNullOrBlank()) {
            val targetMediaUrl = mediaUrl ?: "http://$host:${camera.onvifPort}/onvif/media_service"
            val profilesBody = "<trt:GetProfiles/>"
            val profRes = executeSoap(targetMediaUrl, camera, SOAP_ACTION_GET_PROFILES, profilesBody)
            profRes.onSuccess { xml ->
                extractFirstProfileToken(xml)?.let { token ->
                    profileToken = token
                    discoveredProfileTokenCache[key] = token
                }
            }.onFailure { err ->
                if (lastErr == null) lastErr = err.message
            }
        }

        val finalPtz = ptzUrl ?: defaultPtzUrl(host, camera.onvifPort)
        val finalProfile = profileToken?.ifBlank { null } ?: "Profile_1"
        discoveredPtzUrlCache.putIfAbsent(key, finalPtz)
        discoveredProfileTokenCache.putIfAbsent(key, finalProfile)
        return Triple(finalPtz, finalProfile, lastErr)
    }

    private fun executeSoap(
        url: String,
        camera: CameraEntity,
        soapAction: String,
        bodyContent: String
    ): Result<String> {
        return runCatching {
            val envelope = buildSoapEnvelope(
                username = camera.username,
                password = camera.password,
                bodyContent = bodyContent
            )

            val builder = Request.Builder()
                .url(url)
                .post(envelope.toRequestBody(soapMediaType))
                .header("Content-Type", "application/soap+xml; charset=utf-8; action=\"$soapAction\"")

            if (camera.username.isNotBlank()) {
                val raw = "${camera.username}:${camera.password}"
                val basic = raw.toByteArray(Charsets.UTF_8).toByteString().base64()
                builder.header("Authorization", "Basic $basic")
            }

            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val fault = extractSoapFault(text)
                    val detail = fault ?: "HTTP ${resp.code} ${resp.message}".trim()
                    throw IllegalStateException(detail)
                }
                text
            }
        }
    }

    companion object {
        private const val SOAP_ACTION_GET_CAPABILITIES = "http://www.onvif.org/ver10/device/wsdl/GetCapabilities"
        private const val SOAP_ACTION_GET_PROFILES = "http://www.onvif.org/ver10/media/wsdl/GetProfiles"
        private const val SOAP_ACTION_GET_STREAM_URI = "http://www.onvif.org/ver10/media/wsdl/GetStreamUri"
        private const val SOAP_ACTION_CONTINUOUS_MOVE = "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove"
        private const val SOAP_ACTION_STOP = "http://www.onvif.org/ver20/ptz/wsdl/Stop"
        private const val SOAP_ACTION_GOTO_HOME = "http://www.onvif.org/ver20/ptz/wsdl/GotoHomePosition"
        private const val SOAP_ACTION_GOTO_PRESET = "http://www.onvif.org/ver20/ptz/wsdl/GotoPreset"

        fun defaultPtzUrl(host: String, onvifPort: Int): String =
            "http://$host:$onvifPort/onvif/ptz_service"

        fun extractHost(rtspUrl: String): String? {
            val trimmed = rtspUrl.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("android.resource://", ignoreCase = true)) {
                return null
            }
            val parsed = runCatching { URI.create(trimmed) }.getOrNull() ?: return null
            val host = parsed.host?.trim()
            return if (host.isNullOrBlank()) null else host
        }

        fun buildContinuousMoveBody(
            profileToken: String,
            panX: Float,
            tiltY: Float,
            zoomX: Float
        ): String {
            return """
                <tptz:ContinuousMove>
                    <tptz:ProfileToken>${escapeXml(profileToken)}</tptz:ProfileToken>
                    <tptz:Velocity>
                        <tt:PanTilt x="${String.format(Locale.US, "%.2f", panX)}" y="${String.format(Locale.US, "%.2f", tiltY)}"/>
                        <tt:Zoom x="${String.format(Locale.US, "%.2f", zoomX)}"/>
                    </tptz:Velocity>
                </tptz:ContinuousMove>
            """.trimIndent()
        }

        fun buildStopBody(profileToken: String): String {
            return """
                <tptz:Stop>
                    <tptz:ProfileToken>${escapeXml(profileToken)}</tptz:ProfileToken>
                    <tptz:PanTilt>true</tptz:PanTilt>
                    <tptz:Zoom>true</tptz:Zoom>
                </tptz:Stop>
            """.trimIndent()
        }

        fun buildSoapEnvelope(
            username: String,
            password: String,
            bodyContent: String
        ): String {
            val securityHeader = if (username.isNotBlank()) {
                val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val nonceB64 = nonceBytes.toByteString().base64()
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val created = sdf.format(Date())
                val digestInput = nonceBytes + created.toByteArray(Charsets.UTF_8) + password.toByteArray(Charsets.UTF_8)
                val sha1 = MessageDigest.getInstance("SHA-1").digest(digestInput)
                val digestB64 = sha1.toByteString().base64()

                """
                <s:Header>
                    <Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                        <UsernameToken>
                            <Username>${escapeXml(username)}</Username>
                            <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd#PasswordDigest">$digestB64</Password>
                            <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd#Base64Binary">$nonceB64</Nonce>
                            <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
                        </UsernameToken>
                    </Security>
                </s:Header>
                """.trimIndent()
            } else {
                ""
            }

            return """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
                            xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                            xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"
                            xmlns:tt="http://www.onvif.org/ver10/schema">
                    $securityHeader
                    <s:Body>
                        $bodyContent
                    </s:Body>
                </s:Envelope>
            """.trimIndent()
        }

        private fun extractXAddr(xml: String, sectionTag: String): String? {
            val sectionRegex = Regex("<(?:[a-zA-Z0-9]+:)?$sectionTag\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9]+:)?$sectionTag>")
            val sectionMatch = sectionRegex.find(xml) ?: return null
            val xaddrRegex = Regex("<(?:[a-zA-Z0-9]+:)?XAddr>([^<]+)</(?:[a-zA-Z0-9]+:)?XAddr>")
            return xaddrRegex.find(sectionMatch.groupValues[1])?.groupValues?.getOrNull(1)?.trim()
        }

        private fun extractFirstProfileToken(xml: String): String? {
            val tokenRegex = Regex("<(?:[a-zA-Z0-9]+:)?Profiles\\b[^>]*\\btoken=\"([^\"]+)\"")
            return tokenRegex.find(xml)?.groupValues?.getOrNull(1)?.trim()
        }

        fun extractStreamUriFromXml(xml: String): String? {
            val uriRegex = Regex("<(?:[a-zA-Z0-9]+:)?Uri>([^<]+)</(?:[a-zA-Z0-9]+:)?Uri>")
            return uriRegex.find(xml)?.groupValues?.getOrNull(1)?.trim()?.replace("&amp;", "&")
        }

        private fun extractSoapFault(xml: String): String? {
            val reasonRegex = Regex("<(?:[a-zA-Z0-9]+:)?Text[^>]*>([^<]+)</(?:[a-zA-Z0-9]+:)?Text>")
            return reasonRegex.find(xml)?.groupValues?.getOrNull(1)?.trim()
                ?: Regex("<faultstring>([^<]+)</faultstring>").find(xml)?.groupValues?.getOrNull(1)?.trim()
        }

        private fun escapeXml(input: String): String = input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
