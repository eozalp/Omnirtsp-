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
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class PtzCommandState(
    val cameraId: String = "",
    val isBusy: Boolean = false,
    val isMoving: Boolean = false,
    val activeAction: String = "PTZ Ready",
    val ptzServiceUrl: String = "",
    val profileToken: String = "",
    val resolvedOnvifPort: Int? = null,
    val lastError: String? = null
)

/**
 * Real ONVIF Profile S PTZ SOAP client.
 * Communicates directly with IP cameras over HTTP/HTTPS using WS-Security UsernameToken,
 * SOAP 1.2 / SOAP 1.1 fallback, HTTP Digest/Basic auth, and automatic ONVIF port/service
 * discovery (e.g. port 2020 for TP-Link Tapo, 80, 8000, 8080, 8899).
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

    private val discoveredPtzUrlCache = ConcurrentHashMap<String, String>()
    private val discoveredMediaUrlCache = ConcurrentHashMap<String, String>()
    private val discoveredProfileTokenCache = ConcurrentHashMap<String, String>()
    private val discoveredPortCache = ConcurrentHashMap<String, Int>()
    private val cameraSupportsSpaceUriCache = ConcurrentHashMap<String, Boolean>()

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

        val resolution = resolveEndpointAndProfile(camera, host)
        val result = if (resolution.error == null) {
            PtzCommandState(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_connected_profile, "Connected • Profile ${resolution.profileToken}", resolution.profileToken),
                ptzServiceUrl = resolution.ptzUrl,
                profileToken = resolution.profileToken,
                resolvedOnvifPort = resolution.resolvedPort,
                lastError = null
            )
        } else {
            PtzCommandState(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_unreachable, "ONVIF unreachable"),
                ptzServiceUrl = resolution.ptzUrl,
                profileToken = resolution.profileToken,
                resolvedOnvifPort = resolution.resolvedPort,
                lastError = resolution.error
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

        val resolution = resolveEndpointAndProfile(camera, host)
        if (resolution.error != null && resolution.ptzUrl.isBlank()) {
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                lastError = resolution.error
            )
            return@withContext Result.failure(IllegalStateException(resolution.error))
        }

        val ptzUrl = resolution.ptzUrl
        val profileToken = resolution.profileToken
        val panX = panVelocity.coerceIn(-1f, 1f)
        val tiltY = tiltVelocity.coerceIn(-1f, 1f)
        val zoomX = zoomVelocity.coerceIn(-1f, 1f)

        val preferSpace = cameraSupportsSpaceUriCache[cacheKey(camera)] == true
        val attempts = linkedSetOf(
            buildContinuousMoveBody(profileToken, panX, tiltY, zoomX, includeSpaceUri = preferSpace, includeAllAxes = false),
            buildContinuousMoveBody(profileToken, panX, tiltY, zoomX, includeSpaceUri = !preferSpace, includeAllAxes = false),
            buildContinuousMoveBody(profileToken, panX, tiltY, zoomX, includeSpaceUri = true, includeAllAxes = true),
            buildContinuousMoveBody(profileToken, panX, tiltY, zoomX, includeSpaceUri = false, includeAllAxes = true)
        )

        var lastResponse: Result<String> = Result.failure(IllegalStateException("PTZ failed"))
        for ((index, bodyXml) in attempts.withIndex()) {
            val res = executeSoap(ptzUrl, camera, SOAP_ACTION_CONTINUOUS_MOVE, bodyXml)
            lastResponse = res
            if (res.isSuccess) {
                cameraSupportsSpaceUriCache[cacheKey(camera)] = bodyXml.contains("space=")
                break
            }
            val errMsg = res.exceptionOrNull()?.message.orEmpty()
            // Only try alternative Velocity schemas when the camera returned 400 / schema error
            if (index == 0 && !errMsg.contains("400") && !errMsg.contains("Bad Request", ignoreCase = true) && !errMsg.contains("Invalid", ignoreCase = true)) {
                break
            }
        }

        lastResponse.onSuccess {
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = true,
                activeAction = actionLabel,
                ptzServiceUrl = ptzUrl,
                profileToken = profileToken,
                resolvedOnvifPort = resolution.resolvedPort,
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
                resolvedOnvifPort = resolution.resolvedPort,
                lastError = err.message ?: msg(R.string.ptz_rejected, "Camera rejected PTZ command")
            )
        }
        lastResponse.map { }
    }

    suspend fun stop(camera: CameraEntity): Result<Unit> = withContext(Dispatchers.IO) {
        val host = extractHost(camera.rtspUrl)
            ?: return@withContext reportNoHost(camera.id)

        val cacheKey = cacheKey(camera)
        val port = discoveredPortCache[cacheKey] ?: camera.onvifPort
        val ptzUrl = discoveredPtzUrlCache[cacheKey] ?: defaultPtzUrl(host, port)
        val profileToken = camera.ptzProfileToken.ifBlank {
            discoveredProfileTokenCache[cacheKey] ?: "Profile_1"
        }

        var response = executeSoap(ptzUrl, camera, SOAP_ACTION_STOP, buildStopBody(profileToken, includeZoom = true))
        if (response.isFailure) {
            val errMsg = response.exceptionOrNull()?.message.orEmpty()
            if (errMsg.contains("400") || errMsg.contains("Bad Request", ignoreCase = true)) {
                // Some pan/tilt-only cameras reject Stop with Zoom=true; retry with PanTilt only
                response = executeSoap(ptzUrl, camera, SOAP_ACTION_STOP, buildStopBody(profileToken, includeZoom = false))
            }
        }
        response.onSuccess {
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_stopped_ready, "Stopped • Ready"),
                ptzServiceUrl = ptzUrl,
                profileToken = profileToken,
                resolvedOnvifPort = port,
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
        val resolution = resolveEndpointAndProfile(camera, host)
        val ptzUrl = resolution.ptzUrl
        val profileToken = resolution.profileToken
        val bodyXml = "<tptz:GotoHomePosition><tptz:ProfileToken>${escapeXml(profileToken)}</tptz:ProfileToken></tptz:GotoHomePosition>"
        val response = executeSoap(ptzUrl, camera, SOAP_ACTION_GOTO_HOME, bodyXml)
        response.onSuccess {
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_home_sent, "Home position command sent"),
                ptzServiceUrl = ptzUrl,
                profileToken = profileToken,
                resolvedOnvifPort = resolution.resolvedPort,
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
        val resolution = resolveEndpointAndProfile(camera, host)
        val ptzUrl = resolution.ptzUrl
        val profileToken = resolution.profileToken
        val bodyXml = "<tptz:GotoPreset><tptz:ProfileToken>${escapeXml(profileToken)}</tptz:ProfileToken><tptz:PresetToken>${escapeXml(presetToken)}</tptz:PresetToken></tptz:GotoPreset>"
        val response = executeSoap(ptzUrl, camera, SOAP_ACTION_GOTO_PRESET, bodyXml)
        response.onSuccess {
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_preset_active, "Preset $presetToken active", presetToken),
                ptzServiceUrl = ptzUrl,
                profileToken = profileToken,
                resolvedOnvifPort = resolution.resolvedPort,
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
        var mediaUrl: String? = null
        var workingPort = tempCam.onvifPort
        val candidatePorts = candidateOnvifPorts(tempCam.onvifPort)

        for (port in candidatePorts) {
            val deviceUrl = "http://$host:$port/onvif/device_service"
            val capRes = executeSoap(deviceUrl, tempCam, SOAP_ACTION_GET_CAPABILITIES, "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>")
            if (capRes.isSuccess) {
                workingPort = port
                mediaUrl = extractXAddr(capRes.getOrThrow(), "Media")?.let { normalizeXAddrHost(it, host, port) }
                break
            }
            val svcRes = executeSoap(deviceUrl, tempCam, SOAP_ACTION_GET_SERVICES, "<tds:GetServices><tds:IncludeCapability>false</tds:IncludeCapability></tds:GetServices>")
            if (svcRes.isSuccess) {
                workingPort = port
                mediaUrl = extractServiceXAddr(svcRes.getOrThrow(), "media/wsdl")?.let { normalizeXAddrHost(it, host, port) }
                break
            }
        }

        val targetMediaUrl = mediaUrl ?: "http://$host:$workingPort/onvif/media_service"
        val profilesRes = executeSoap(targetMediaUrl, tempCam, SOAP_ACTION_GET_PROFILES, "<trt:GetProfiles/>")
        val profXml = profilesRes.getOrElse { err ->
            return@withContext Result.failure(err)
        }
        val profileToken = extractFirstProfileToken(profXml) ?: "Profile_1"

        val streamUriBody = "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>${escapeXml(profileToken)}</trt:ProfileToken></trt:GetStreamUri>"
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
            discoveredPortCache.remove(it)
            cameraSupportsSpaceUriCache.remove(it)
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

    private data class EndpointResolution(
        val ptzUrl: String,
        val profileToken: String,
        val resolvedPort: Int,
        val error: String?
    )

    private fun resolveEndpointAndProfile(camera: CameraEntity, host: String): EndpointResolution {
        val key = cacheKey(camera)
        val cachedPtz = discoveredPtzUrlCache[key]
        val cachedMedia = discoveredMediaUrlCache[key]
        val cachedProfile = camera.ptzProfileToken.ifBlank { discoveredProfileTokenCache[key] }
        val cachedPort = discoveredPortCache[key] ?: camera.onvifPort

        if (!cachedPtz.isNullOrBlank() && !cachedProfile.isNullOrBlank()) {
            return EndpointResolution(cachedPtz, cachedProfile, cachedPort, null)
        }

        var ptzUrl: String? = cachedPtz
        var mediaUrl: String? = cachedMedia
        var resolvedPort = cachedPort
        var lastErr: String? = null

        if (ptzUrl.isNullOrBlank()) {
            val portsToTry = candidateOnvifPorts(camera.onvifPort)
            for (port in portsToTry) {
                val deviceUrl = "http://$host:$port/onvif/device_service"
                val capRes = executeSoap(
                    deviceUrl,
                    camera,
                    SOAP_ACTION_GET_CAPABILITIES,
                    "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>"
                )
                if (capRes.isSuccess) {
                    val xml = capRes.getOrThrow()
                    resolvedPort = port
                    ptzUrl = extractXAddr(xml, "PTZ")?.let { normalizeXAddrHost(it, host, port) }
                    mediaUrl = extractXAddr(xml, "Media")?.let { normalizeXAddrHost(it, host, port) }
                    lastErr = null
                    break
                } else {
                    lastErr = capRes.exceptionOrNull()?.message
                }

                // Fallback to GetServices on cameras that omit GetCapabilities PTZ block (e.g. certain Tapo / Hikvision firmwares)
                val svcRes = executeSoap(
                    deviceUrl,
                    camera,
                    SOAP_ACTION_GET_SERVICES,
                    "<tds:GetServices><tds:IncludeCapability>false</tds:IncludeCapability></tds:GetServices>"
                )
                if (svcRes.isSuccess) {
                    val xml = svcRes.getOrThrow()
                    resolvedPort = port
                    ptzUrl = extractServiceXAddr(xml, "ptz/wsdl")?.let { normalizeXAddrHost(it, host, port) }
                    mediaUrl = extractServiceXAddr(xml, "media/wsdl")?.let { normalizeXAddrHost(it, host, port) }
                    lastErr = null
                    break
                } else if (lastErr == null) {
                    lastErr = svcRes.exceptionOrNull()?.message
                }
            }

            if (!ptzUrl.isNullOrBlank()) {
                discoveredPtzUrlCache[key] = ptzUrl!!
                discoveredPortCache[key] = resolvedPort
            }
            if (!mediaUrl.isNullOrBlank()) {
                discoveredMediaUrlCache[key] = mediaUrl!!
            }
        }

        var profileToken = cachedProfile
        if (profileToken.isNullOrBlank()) {
            val mediaUrlsToTry = listOfNotNull(
                mediaUrl,
                "http://$host:$resolvedPort/onvif/media_service",
                "http://$host:$resolvedPort/onvif/service"
            ).distinct()

            for (targetMediaUrl in mediaUrlsToTry) {
                val profRes = executeSoap(targetMediaUrl, camera, SOAP_ACTION_GET_PROFILES, "<trt:GetProfiles/>")
                if (profRes.isSuccess) {
                    extractFirstProfileToken(profRes.getOrThrow())?.let { token ->
                        profileToken = token
                        discoveredProfileTokenCache[key] = token
                        discoveredPortCache[key] = resolvedPort
                        if (ptzUrl.isNullOrBlank()) {
                            // If GetProfiles succeeded on this port/path, PTZ is normally on the same port
                            ptzUrl = defaultPtzUrl(host, resolvedPort)
                            discoveredPtzUrlCache[key] = ptzUrl!!
                        }
                        lastErr = null
                    }
                    if (!profileToken.isNullOrBlank()) break
                } else if (lastErr == null) {
                    lastErr = profRes.exceptionOrNull()?.message
                }
            }
        }

        val finalPtz = ptzUrl ?: defaultPtzUrl(host, resolvedPort)
        val finalProfile = profileToken?.ifBlank { null } ?: "Profile_1"
        return EndpointResolution(finalPtz, finalProfile, resolvedPort, lastErr)
    }

    private fun executeSoap(
        url: String,
        camera: CameraEntity,
        soapAction: String,
        bodyContent: String
    ): Result<String> {
        val (user, pass) = resolveCredentials(camera)

        // Attempt 1: Standard ONVIF SOAP 1.2 (clean XML without leading whitespace, WS-Security Digest)
        val attempt1 = sendSoapRequest(
            url = url,
            username = user,
            password = pass,
            soapAction = soapAction,
            bodyContent = bodyContent,
            useSoap11 = false,
            includeActionInContentType = true,
            includeBasicAuth = false
        )
        if (attempt1.isSuccess) return attempt1.toKotlinResult()

        val code1 = attempt1.httpCode
        if (code1 == 401 && user.isNotBlank()) {
            val withBasic = sendSoapRequest(
                url = url,
                username = user,
                password = pass,
                soapAction = soapAction,
                bodyContent = bodyContent,
                useSoap11 = false,
                includeActionInContentType = true,
                includeBasicAuth = true
            )
            if (withBasic.isSuccess) return withBasic.toKotlinResult()
        }

        // Attempt 2: Some cameras (Tapo/embedded web servers) reject `; action="..."` inside Content-Type with HTTP 400
        if (code1 == 400 || code1 == 415 || code1 == 500) {
            val noActionHeader = sendSoapRequest(
                url = url,
                username = user,
                password = pass,
                soapAction = soapAction,
                bodyContent = bodyContent,
                useSoap11 = false,
                includeActionInContentType = false,
                includeBasicAuth = false
            )
            if (noActionHeader.isSuccess) return noActionHeader.toKotlinResult()

            // Attempt 3: Legacy SOAP 1.1 fallback (`text/xml` + `SOAPAction` header)
            val soap11 = sendSoapRequest(
                url = url,
                username = user,
                password = pass,
                soapAction = soapAction,
                bodyContent = bodyContent,
                useSoap11 = true,
                includeActionInContentType = false,
                includeBasicAuth = user.isNotBlank()
            )
            if (soap11.isSuccess) return soap11.toKotlinResult()
        }

        return attempt1.toKotlinResult()
    }

    private data class SoapCallOutcome(
        val body: String? = null,
        val httpCode: Int = -1,
        val error: Throwable? = null
    ) {
        val isSuccess: Boolean get() = error == null && body != null
        fun toKotlinResult(): Result<String> = if (isSuccess) Result.success(body!!) else Result.failure(error ?: IllegalStateException("SOAP error"))
    }

    private fun sendSoapRequest(
        url: String,
        username: String,
        password: String,
        soapAction: String,
        bodyContent: String,
        useSoap11: Boolean,
        includeActionInContentType: Boolean,
        includeBasicAuth: Boolean
    ): SoapCallOutcome {
        return try {
            val envelope = buildSoapEnvelope(
                username = username,
                password = password,
                bodyContent = bodyContent,
                useSoap11 = useSoap11
            )

            val contentTypeString = when {
                useSoap11 -> "text/xml; charset=utf-8"
                includeActionInContentType -> "application/soap+xml; charset=utf-8; action=\"$soapAction\""
                else -> "application/soap+xml; charset=utf-8"
            }
            val mediaType = contentTypeString.toMediaType()
            val builder = Request.Builder()
                .url(url)
                .post(envelope.toRequestBody(mediaType))

            if (useSoap11) {
                builder.header("SOAPAction", "\"$soapAction\"")
            }
            if (includeBasicAuth && username.isNotBlank()) {
                val raw = "$username:$password"
                val basic = raw.toByteArray(Charsets.UTF_8).toByteString().base64()
                builder.header("Authorization", "Basic $basic")
            }

            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val fault = extractSoapFault(text)
                    val detail = fault ?: "HTTP ${resp.code} ${resp.message}".trim()
                    SoapCallOutcome(httpCode = resp.code, error = IllegalStateException(detail))
                } else {
                    SoapCallOutcome(body = text, httpCode = resp.code)
                }
            }
        } catch (t: Throwable) {
            SoapCallOutcome(error = t)
        }
    }

    companion object {
        private const val SOAP_ACTION_GET_CAPABILITIES = "http://www.onvif.org/ver10/device/wsdl/GetCapabilities"
        private const val SOAP_ACTION_GET_SERVICES = "http://www.onvif.org/ver10/device/wsdl/GetServices"
        private const val SOAP_ACTION_GET_PROFILES = "http://www.onvif.org/ver10/media/wsdl/GetProfiles"
        private const val SOAP_ACTION_GET_STREAM_URI = "http://www.onvif.org/ver10/media/wsdl/GetStreamUri"
        private const val SOAP_ACTION_CONTINUOUS_MOVE = "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove"
        private const val SOAP_ACTION_STOP = "http://www.onvif.org/ver20/ptz/wsdl/Stop"
        private const val SOAP_ACTION_GOTO_HOME = "http://www.onvif.org/ver20/ptz/wsdl/GotoHomePosition"
        private const val SOAP_ACTION_GOTO_PRESET = "http://www.onvif.org/ver20/ptz/wsdl/GotoPreset"

        private const val PAN_TILT_VELOCITY_SPACE = "http://www.onvif.org/ver10/tptz/PanTiltSpaces/VelocityGenericSpace"
        private const val ZOOM_VELOCITY_SPACE = "http://www.onvif.org/ver10/tptz/ZoomSpaces/VelocityGenericSpace"

        fun defaultPtzUrl(host: String, onvifPort: Int): String =
            "http://$host:$onvifPort/onvif/ptz_service"

        fun candidateOnvifPorts(configuredPort: Int): List<Int> =
            listOf(configuredPort.coerceIn(1, 65535), 2020, 80, 8000, 8080, 8899).distinct()

        fun extractHost(rtspUrl: String): String? {
            val trimmed = rtspUrl.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("android.resource://", ignoreCase = true)) {
                return null
            }
            val parsed = runCatching { URI.create(trimmed) }.getOrNull() ?: return null
            val host = parsed.host?.trim()
            return if (host.isNullOrBlank()) null else host
        }

        fun resolveCredentials(camera: CameraEntity): Pair<String, String> {
            val explicitUser = camera.username.trim()
            val explicitPass = camera.password
            if (explicitUser.isNotBlank()) {
                return explicitUser to explicitPass
            }
            val parsed = runCatching { URI.create(camera.rtspUrl.trim()) }.getOrNull()
            val userInfo = parsed?.rawUserInfo?.takeIf { it.isNotBlank() } ?: return "" to ""
            val idx = userInfo.indexOf(':')
            return if (idx >= 0) {
                val u = runCatching { URLDecoder.decode(userInfo.substring(0, idx), "UTF-8") }.getOrDefault(userInfo.substring(0, idx))
                val p = runCatching { URLDecoder.decode(userInfo.substring(idx + 1), "UTF-8") }.getOrDefault(userInfo.substring(idx + 1))
                u.trim() to p
            } else {
                val u = runCatching { URLDecoder.decode(userInfo, "UTF-8") }.getOrDefault(userInfo)
                u.trim() to ""
            }
        }

        fun buildContinuousMoveBody(
            profileToken: String,
            panX: Float,
            tiltY: Float,
            zoomX: Float,
            includeSpaceUri: Boolean = false,
            includeAllAxes: Boolean = false
        ): String {
            val hasPanTilt = includeAllAxes || abs(panX) > 0.001f || abs(tiltY) > 0.001f
            val hasZoom = includeAllAxes || abs(zoomX) > 0.001f
            val panTiltAttr = if (includeSpaceUri) " space=\"$PAN_TILT_VELOCITY_SPACE\"" else ""
            val zoomAttr = if (includeSpaceUri) " space=\"$ZOOM_VELOCITY_SPACE\"" else ""

            val panTiltXml = if (hasPanTilt || !hasZoom) {
                "<tt:PanTilt x=\"${String.format(Locale.US, "%.2f", panX)}\" y=\"${String.format(Locale.US, "%.2f", tiltY)}\"$panTiltAttr/>"
            } else ""
            val zoomXml = if (hasZoom) {
                "<tt:Zoom x=\"${String.format(Locale.US, "%.2f", zoomX)}\"$zoomAttr/>"
            } else ""

            return "<tptz:ContinuousMove>" +
                "<tptz:ProfileToken>${escapeXml(profileToken)}</tptz:ProfileToken>" +
                "<tptz:Velocity>$panTiltXml$zoomXml</tptz:Velocity>" +
                "</tptz:ContinuousMove>"
        }

        fun buildStopBody(profileToken: String, includeZoom: Boolean = true): String {
            val zoomTag = if (includeZoom) "<tptz:Zoom>true</tptz:Zoom>" else ""
            return "<tptz:Stop>" +
                "<tptz:ProfileToken>${escapeXml(profileToken)}</tptz:ProfileToken>" +
                "<tptz:PanTilt>true</tptz:PanTilt>" +
                zoomTag +
                "</tptz:Stop>"
        }

        fun buildSoapEnvelope(
            username: String,
            password: String,
            bodyContent: String,
            useSoap11: Boolean = false
        ): String {
            val envelopeNs = if (useSoap11) {
                "http://schemas.xmlsoap.org/soap/envelope/"
            } else {
                "http://www.w3.org/2003/05/soap-envelope"
            }
            val securityHeader = if (username.isNotBlank()) {
                val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val nonceB64 = nonceBytes.toByteString().base64()
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val created = sdf.format(Date())
                val digestInput = nonceBytes + created.toByteArray(Charsets.UTF_8) + password.toByteArray(Charsets.UTF_8)
                val sha1 = MessageDigest.getInstance("SHA-1").digest(digestInput)
                val digestB64 = sha1.toByteString().base64()

                "<s:Header>" +
                    "<Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">" +
                    "<UsernameToken>" +
                    "<Username>${escapeXml(username)}</Username>" +
                    "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd#PasswordDigest\">$digestB64</Password>" +
                    "<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd#Base64Binary\">$nonceB64</Nonce>" +
                    "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">$created</Created>" +
                    "</UsernameToken>" +
                    "</Security>" +
                    "</s:Header>"
            } else {
                ""
            }

            val compactBody = bodyContent.lines().joinToString("") { it.trim() }
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"$envelopeNs\" " +
                "xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" " +
                "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" " +
                "xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\" " +
                "xmlns:tt=\"http://www.onvif.org/ver10/schema\">" +
                securityHeader +
                "<s:Body>$compactBody</s:Body>" +
                "</s:Envelope>"
        }

        private fun normalizeXAddrHost(xAddr: String, targetHost: String, fallbackPort: Int): String {
            return runCatching {
                val uri = URI.create(xAddr.trim())
                val resolvedHost = uri.host?.trim().orEmpty()
                val needsRewrite = resolvedHost.isEmpty() ||
                    resolvedHost == "0.0.0.0" ||
                    resolvedHost == "127.0.0.1" ||
                    resolvedHost.equals("localhost", ignoreCase = true)
                val finalHost = if (needsRewrite) targetHost else resolvedHost
                val finalPort = if (uri.port > 0) uri.port else fallbackPort
                val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: "http"
                val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/onvif/device_service"
                val query = uri.rawQuery?.let { "?$it" } ?: ""
                "$scheme://$finalHost:$finalPort$path$query"
            }.getOrDefault(xAddr.trim())
        }

        private fun extractXAddr(xml: String, sectionTag: String): String? {
            val sectionRegex = Regex("<(?:[a-zA-Z0-9]+:)?$sectionTag\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9]+:)?$sectionTag>")
            val sectionMatch = sectionRegex.find(xml) ?: return null
            val xaddrRegex = Regex("<(?:[a-zA-Z0-9]+:)?XAddr>([^<]+)</(?:[a-zA-Z0-9]+:)?XAddr>")
            return xaddrRegex.find(sectionMatch.groupValues[1])?.groupValues?.getOrNull(1)?.trim()
        }

        private fun extractServiceXAddr(xml: String, namespaceFragment: String): String? {
            val serviceRegex = Regex("<(?:[a-zA-Z0-9]+:)?Service\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9]+:)?Service>")
            for (match in serviceRegex.findAll(xml)) {
                val block = match.groupValues[1]
                if (block.contains(namespaceFragment, ignoreCase = true)) {
                    val xaddrRegex = Regex("<(?:[a-zA-Z0-9]+:)?XAddr>([^<]+)</(?:[a-zA-Z0-9]+:)?XAddr>")
                    val addr = xaddrRegex.find(block)?.groupValues?.getOrNull(1)?.trim()
                    if (!addr.isNullOrBlank()) return addr
                }
            }
            return null
        }

        fun extractFirstProfileToken(xml: String): String? {
            val profileRegex = Regex("<(?:[a-zA-Z0-9]+:)?Profiles\\b([^>]*)>([\\s\\S]*?)</(?:[a-zA-Z0-9]+:)?Profiles>")
            val tokenAttrRegex = Regex("\\btoken=[\"']([^\"']+)[\"']")
            var firstToken: String? = null
            for (match in profileRegex.findAll(xml)) {
                val attrs = match.groupValues[1]
                val inner = match.groupValues[2]
                val token = tokenAttrRegex.find(attrs)?.groupValues?.getOrNull(1)?.trim()
                if (!token.isNullOrBlank()) {
                    if (firstToken == null) firstToken = token
                    if (inner.contains("PTZConfiguration", ignoreCase = true)) {
                        return token
                    }
                }
            }
            if (firstToken != null) return firstToken
            val fallbackRegex = Regex("<(?:[a-zA-Z0-9]+:)?Profiles\\b[^>]*\\btoken=[\"']([^\"']+)[\"']")
            return fallbackRegex.find(xml)?.groupValues?.getOrNull(1)?.trim()
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
