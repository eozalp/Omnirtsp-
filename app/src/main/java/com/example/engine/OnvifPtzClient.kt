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
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class OnvifPreset(
    val token: String,
    val name: String
)

data class PtzCommandState(
    val cameraId: String = "",
    val isBusy: Boolean = false,
    val isQuerying: Boolean = false,
    val isQueried: Boolean = false,
    val isMoving: Boolean = false,
    val activeAction: String = "PTZ Ready",
    val ptzServiceUrl: String = "",
    val mediaServiceUrl: String = "",
    val profileToken: String = "",
    val availableProfiles: List<String> = emptyList(),
    val presets: List<OnvifPreset> = emptyList(),
    val supportsPanTilt: Boolean = true,
    val supportsZoom: Boolean = true,
    val supportsContinuousMove: Boolean = true,
    val supportsRelativeMove: Boolean = false,
    val resolvedOnvifPort: Int? = null,
    val clockOffsetMs: Long = 0L,
    val deviceModel: String = "",
    val diagnosticSummary: String = "",
    val lastError: String? = null
)

/**
 * Real ONVIF Profile S PTZ SOAP client.
 * Performs a full pre-action ONVIF capability query (GetSystemDateAndTime clock sync ->
 * GetCapabilities / GetServices -> GetDeviceInformation -> GetProfiles -> GetNodes -> GetPresets)
 * before sending ContinuousMove / RelativeMove / Stop / GotoHome / GotoPreset commands.
 */
class OnvifPtzClient(private val context: Context? = null) {

    enum class SoapDialect {
        SOAP12_NO_ACTION,
        SOAP12_WITH_ACTION,
        SOAP11
    }

    data class NodeCapabilities(
        val supportsPanTilt: Boolean = true,
        val supportsZoom: Boolean = true,
        val supportsContinuousMove: Boolean = true,
        val supportsRelativeMove: Boolean = false,
        val continuousPanTiltSpaceUri: String? = null,
        val continuousZoomSpaceUri: String? = null,
        val relativePanTiltSpaceUri: String? = null
    )

    private data class CachedOnvifCapabilities(
        val ptzUrl: String,
        val mediaUrl: String,
        val profileToken: String,
        val availableProfiles: List<String>,
        val presets: List<OnvifPreset>,
        val resolvedPort: Int,
        val deviceModel: String,
        val nodeCaps: NodeCapabilities,
        val clockOffsetMs: Long
    )

    private fun msg(resId: Int, fallback: String, vararg args: Any): String {
        val ctx = context ?: return fallback
        return AppLocaleManager.getString(ctx, resId, *args)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .writeTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    private val capabilityCache = ConcurrentHashMap<String, CachedOnvifCapabilities>()
    private val cameraSoapDialectCache = ConcurrentHashMap<String, SoapDialect>()
    private val cameraClockOffsetCache = ConcurrentHashMap<String, Long>()
    private val cameraUsesRelativeFallback = ConcurrentHashMap<String, Boolean>()
    private val cameraSupportsSpaceUriCache = ConcurrentHashMap<String, Boolean>()

    private val _status = MutableStateFlow(PtzCommandState())
    val status: StateFlow<PtzCommandState> = _status.asStateFlow()

    /**
     * Queries the camera's ONVIF services, system clock, media profiles, PTZ nodes/spaces,
     * and presets BEFORE sending movement commands.
     */
    suspend fun probeCamera(
        camera: CameraEntity,
        forceRefresh: Boolean = true
    ): PtzCommandState = withContext(Dispatchers.IO) {
        val host = extractHost(camera.rtspUrl)
        if (host.isNullOrBlank()) {
            val errState = PtzCommandState(
                cameraId = camera.id,
                isQuerying = false,
                isQueried = false,
                activeAction = msg(R.string.ptz_no_ip_action, "No IP/host in stream URL"),
                lastError = msg(R.string.ptz_no_ip_error, "Set a valid RTSP IP address (e.g. rtsp://192.168.1.100:554/...)")
            )
            _status.value = errState
            return@withContext errState
        }

        val key = cacheKey(camera)
        if (!forceRefresh) {
            capabilityCache[key]?.let { cached ->
                val state = buildSuccessState(camera, cached)
                _status.value = state
                return@withContext state
            }
        } else {
            clearCache(camera.id)
        }

        _status.value = _status.value.copy(
            cameraId = camera.id,
            isBusy = true,
            isQuerying = true,
            activeAction = msg(
                R.string.ptz_querying_ports,
                "Querying ONVIF ($host:${camera.onvifPort})...",
                host,
                camera.onvifPort
            ),
            lastError = null
        )

        val resolution = queryOnvifEndpointAndCapabilities(camera, host)
        val result = if (resolution.error == null && resolution.capabilities != null) {
            capabilityCache[key] = resolution.capabilities
            buildSuccessState(camera, resolution.capabilities)
        } else {
            val partial = resolution.capabilities
            PtzCommandState(
                cameraId = camera.id,
                isBusy = false,
                isQuerying = false,
                isQueried = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_unreachable, "ONVIF unreachable"),
                ptzServiceUrl = partial?.ptzUrl.orEmpty(),
                mediaServiceUrl = partial?.mediaUrl.orEmpty(),
                profileToken = partial?.profileToken.orEmpty(),
                availableProfiles = partial?.availableProfiles.orEmpty(),
                presets = partial?.presets.orEmpty(),
                resolvedOnvifPort = partial?.resolvedPort ?: camera.onvifPort,
                clockOffsetMs = cameraClockOffsetCache[key] ?: 0L,
                diagnosticSummary = resolution.error.orEmpty(),
                lastError = resolution.error ?: msg(R.string.ptz_unreachable, "ONVIF unreachable")
            )
        }
        _status.value = result
        result
    }

    private fun buildSuccessState(camera: CameraEntity, caps: CachedOnvifCapabilities): PtzCommandState {
        val axesLabel = buildString {
            if (caps.nodeCaps.supportsPanTilt) append("PT")
            if (caps.nodeCaps.supportsZoom) append("Z")
            if (isEmpty()) append("PT")
            if (!caps.nodeCaps.supportsContinuousMove && caps.nodeCaps.supportsRelativeMove) {
                append(" • Rel")
            }
        }
        val summary = buildString {
            append(
                msg(
                    R.string.ptz_queried_summary,
                    "Port ${caps.resolvedPort} • ${caps.profileToken} • $axesLabel",
                    caps.resolvedPort,
                    caps.profileToken,
                    axesLabel
                )
            )
            if (caps.deviceModel.isNotBlank()) {
                append(" • ${caps.deviceModel}")
            }
        }
        return PtzCommandState(
            cameraId = camera.id,
            isBusy = false,
            isQuerying = false,
            isQueried = true,
            isMoving = false,
            activeAction = msg(
                R.string.ptz_connected_profile,
                "Connected • Profile ${caps.profileToken}",
                caps.profileToken
            ),
            ptzServiceUrl = caps.ptzUrl,
            mediaServiceUrl = caps.mediaUrl,
            profileToken = caps.profileToken,
            availableProfiles = caps.availableProfiles,
            presets = caps.presets,
            supportsPanTilt = caps.nodeCaps.supportsPanTilt,
            supportsZoom = caps.nodeCaps.supportsZoom,
            supportsContinuousMove = caps.nodeCaps.supportsContinuousMove,
            supportsRelativeMove = caps.nodeCaps.supportsRelativeMove,
            resolvedOnvifPort = caps.resolvedPort,
            clockOffsetMs = caps.clockOffsetMs,
            deviceModel = caps.deviceModel,
            diagnosticSummary = summary,
            lastError = null
        )
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

        // Ensure ONVIF is queried BEFORE issuing any PTZ movement command
        val caps = ensureQueriedBeforeAction(camera, host)
            ?: return@withContext Result.failure(
                IllegalStateException(_status.value.lastError ?: msg(R.string.ptz_unreachable, "ONVIF unreachable"))
            )

        _status.value = _status.value.copy(
            cameraId = camera.id,
            isBusy = true,
            isQuerying = false,
            isMoving = true,
            activeAction = actionLabel,
            lastError = null
        )

        val ptzUrl = caps.ptzUrl
        val profileToken = caps.profileToken
        val panX = panVelocity.coerceIn(-1f, 1f)
        val tiltY = tiltVelocity.coerceIn(-1f, 1f)
        val zoomX = zoomVelocity.coerceIn(-1f, 1f)
        val key = cacheKey(camera)
        cameraUsesRelativeFallback[camera.id] = false

        if (abs(zoomX) > 0.001f && !caps.nodeCaps.supportsZoom && abs(panX) < 0.001f && abs(tiltY) < 0.001f) {
            val noZoomMsg = msg(R.string.ptz_pan_tilt_only, "Pan/Tilt Only (No Zoom)")
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = noZoomMsg,
                lastError = noZoomMsg
            )
            return@withContext Result.failure(UnsupportedOperationException(noZoomMsg))
        }

        val discoveredPanSpace = caps.nodeCaps.continuousPanTiltSpaceUri
        val discoveredZoomSpace = caps.nodeCaps.continuousZoomSpaceUri
        val preferSpace = cameraSupportsSpaceUriCache[key] ?: (discoveredPanSpace != null || discoveredZoomSpace != null)

        var lastResponse: Result<String> = Result.failure(IllegalStateException("PTZ failed"))
        if (caps.nodeCaps.supportsContinuousMove) {
            val attempts = linkedSetOf(
                buildContinuousMoveBody(
                    profileToken = profileToken,
                    panX = panX,
                    tiltY = tiltY,
                    zoomX = zoomX,
                    includeSpaceUri = preferSpace,
                    includeAllAxes = false,
                    panTiltSpaceUri = discoveredPanSpace ?: PAN_TILT_VELOCITY_SPACE,
                    zoomSpaceUri = discoveredZoomSpace ?: ZOOM_VELOCITY_SPACE,
                    includeTimeout = false
                ),
                buildContinuousMoveBody(
                    profileToken = profileToken,
                    panX = panX,
                    tiltY = tiltY,
                    zoomX = zoomX,
                    includeSpaceUri = !preferSpace,
                    includeAllAxes = false,
                    panTiltSpaceUri = discoveredPanSpace ?: PAN_TILT_VELOCITY_SPACE,
                    zoomSpaceUri = discoveredZoomSpace ?: ZOOM_VELOCITY_SPACE,
                    includeTimeout = false
                ),
                buildContinuousMoveBody(
                    profileToken = profileToken,
                    panX = panX,
                    tiltY = tiltY,
                    zoomX = zoomX,
                    includeSpaceUri = false,
                    includeAllAxes = false,
                    includeTimeout = true
                ),
                buildContinuousMoveBody(
                    profileToken = profileToken,
                    panX = panX,
                    tiltY = tiltY,
                    zoomX = zoomX,
                    includeSpaceUri = true,
                    includeAllAxes = true,
                    panTiltSpaceUri = discoveredPanSpace ?: PAN_TILT_VELOCITY_SPACE,
                    zoomSpaceUri = discoveredZoomSpace ?: ZOOM_VELOCITY_SPACE,
                    includeTimeout = false
                )
            )

            for ((index, bodyXml) in attempts.withIndex()) {
                val res = executeSoap(ptzUrl, camera, SOAP_ACTION_CONTINUOUS_MOVE, bodyXml)
                lastResponse = res
                if (res.isSuccess) {
                    cameraSupportsSpaceUriCache[key] = bodyXml.contains("space=")
                    break
                }
                val errMsg = res.exceptionOrNull()?.message.orEmpty()
                if (index == 0 && !isSchemaOrActionRejection(errMsg)) {
                    break
                }
            }
        }

        // RelativeMove fallback when camera rejects ContinuousMove or only supports RelativeMove
        if (lastResponse.isFailure) {
            val relPan = (panX * 0.12f).coerceIn(-0.5f, 0.5f)
            val relTilt = (tiltY * 0.12f).coerceIn(-0.5f, 0.5f)
            val relZoom = (zoomX * 0.12f).coerceIn(-0.5f, 0.5f)
            val relSpace = caps.nodeCaps.relativePanTiltSpaceUri
            val relAttempts = linkedSetOf(
                buildRelativeMoveBody(profileToken, relPan, relTilt, relZoom, spaceUri = relSpace),
                buildRelativeMoveBody(profileToken, relPan, relTilt, relZoom, spaceUri = null)
            )
            for (relBody in relAttempts) {
                val relRes = executeSoap(ptzUrl, camera, SOAP_ACTION_RELATIVE_MOVE, relBody)
                if (relRes.isSuccess) {
                    cameraUsesRelativeFallback[camera.id] = true
                    lastResponse = relRes
                    break
                }
            }
        }

        lastResponse.onSuccess {
            val finalLabel = if (cameraUsesRelativeFallback[camera.id] == true) {
                msg(R.string.ptz_relative_step_sent, "Step $actionLabel sent (RelativeMove)", actionLabel)
            } else {
                actionLabel
            }
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isQueried = true,
                isMoving = cameraUsesRelativeFallback[camera.id] != true,
                activeAction = finalLabel,
                ptzServiceUrl = ptzUrl,
                profileToken = profileToken,
                resolvedOnvifPort = caps.resolvedPort,
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
                resolvedOnvifPort = caps.resolvedPort,
                lastError = err.message ?: msg(R.string.ptz_rejected, "Camera rejected PTZ command")
            )
        }
        lastResponse.map { }
    }

    suspend fun stop(camera: CameraEntity): Result<Unit> = withContext(Dispatchers.IO) {
        val host = extractHost(camera.rtspUrl)
            ?: return@withContext reportNoHost(camera.id)

        if (cameraUsesRelativeFallback.remove(camera.id) == true) {
            _status.value = _status.value.copy(
                cameraId = camera.id,
                isBusy = false,
                isMoving = false,
                activeAction = msg(R.string.ptz_stopped_ready, "Stopped • Ready"),
                lastError = null
            )
            return@withContext Result.success(Unit)
        }

        val caps = ensureQueriedBeforeAction(camera, host)
            ?: return@withContext Result.failure(
                IllegalStateException(_status.value.lastError ?: msg(R.string.ptz_unreachable, "ONVIF unreachable"))
            )

        val ptzUrl = caps.ptzUrl
        val profileToken = caps.profileToken
        val includeZoomFirst = caps.nodeCaps.supportsZoom

        var response = executeSoap(
            ptzUrl,
            camera,
            SOAP_ACTION_STOP,
            buildStopBody(profileToken, includeZoom = includeZoomFirst)
        )
        if (response.isFailure && includeZoomFirst) {
            val errMsg = response.exceptionOrNull()?.message.orEmpty()
            if (isSchemaOrActionRejection(errMsg)) {
                response = executeSoap(
                    ptzUrl,
                    camera,
                    SOAP_ACTION_STOP,
                    buildStopBody(profileToken, includeZoom = false)
                )
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
                resolvedOnvifPort = caps.resolvedPort,
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

        val caps = ensureQueriedBeforeAction(camera, host)
            ?: return@withContext Result.failure(
                IllegalStateException(_status.value.lastError ?: msg(R.string.ptz_unreachable, "ONVIF unreachable"))
            )

        _status.value = _status.value.copy(
            cameraId = camera.id,
            isBusy = true,
            activeAction = msg(R.string.ptz_moving_home, "Moving to Home..."),
            lastError = null
        )
        val ptzUrl = caps.ptzUrl
        val profileToken = caps.profileToken
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
                resolvedOnvifPort = caps.resolvedPort,
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

        val caps = ensureQueriedBeforeAction(camera, host)
            ?: return@withContext Result.failure(
                IllegalStateException(_status.value.lastError ?: msg(R.string.ptz_unreachable, "ONVIF unreachable"))
            )

        _status.value = _status.value.copy(
            cameraId = camera.id,
            isBusy = true,
            activeAction = msg(R.string.ptz_moving_preset, "Moving to Preset $presetToken...", presetToken),
            lastError = null
        )
        val ptzUrl = caps.ptzUrl
        val profileToken = caps.profileToken
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
                resolvedOnvifPort = caps.resolvedPort,
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
        val query = queryOnvifEndpointAndCapabilities(tempCam, host)
        val caps = query.capabilities
            ?: return@withContext Result.failure(IllegalStateException(query.error ?: "ONVIF unreachable"))

        val streamUriBody = "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>${escapeXml(caps.profileToken)}</trt:ProfileToken></trt:GetStreamUri>"
        val uriRes = executeSoap(caps.mediaUrl, tempCam, SOAP_ACTION_GET_STREAM_URI, streamUriBody)
        val uriXml = uriRes.getOrElse { err ->
            return@withContext Result.failure(err)
        }
        val rtspUri = extractStreamUriFromXml(uriXml)
            ?: return@withContext Result.failure(IllegalStateException("Camera returned no RTSP URI"))
        Result.success(rtspUri)
    }

    fun clearCache(cameraId: String) {
        val prefix = "$cameraId|"
        capabilityCache.keys.filter { it.startsWith(prefix) }.forEach { capabilityCache.remove(it) }
        cameraSoapDialectCache.keys.filter { it.startsWith(prefix) }.forEach { cameraSoapDialectCache.remove(it) }
        cameraClockOffsetCache.keys.filter { it.startsWith(prefix) }.forEach { cameraClockOffsetCache.remove(it) }
        cameraSupportsSpaceUriCache.keys.filter { it.startsWith(prefix) }.forEach { cameraSupportsSpaceUriCache.remove(it) }
        cameraUsesRelativeFallback.remove(cameraId)
    }

    private suspend fun ensureQueriedBeforeAction(camera: CameraEntity, host: String): CachedOnvifCapabilities? {
        val key = cacheKey(camera)
        capabilityCache[key]?.let { return it }
        val probed = probeCamera(camera, forceRefresh = false)
        if (probed.lastError == null) {
            capabilityCache[key]?.let { return it }
        }
        val resolution = queryOnvifEndpointAndCapabilities(camera, host)
        resolution.capabilities?.let { caps ->
            if (resolution.error == null) {
                capabilityCache[key] = caps
                return caps
            }
        }
        return null
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
        "${camera.id}|${camera.rtspUrl}|${camera.onvifPort}|${camera.ptzProfileToken}"

    private data class QueryResolution(
        val capabilities: CachedOnvifCapabilities? = null,
        val error: String? = null
    )

    private fun queryOnvifEndpointAndCapabilities(camera: CameraEntity, host: String): QueryResolution {
        val key = cacheKey(camera)
        val portsToTry = candidateOnvifPorts(camera.onvifPort)
        val devicePaths = listOf("/onvif/device_service", "/onvif/service", "/onvif/device", "/onvif/devices")

        var workingPort = camera.onvifPort
        var workingDeviceUrl: String? = null
        var ptzUrl: String? = null
        var mediaUrl: String? = null
        var deviceModel = ""
        var lastError: String? = null

        portLoop@ for (port in portsToTry) {
            for (path in devicePaths) {
                val candidateUrl = "http://$host:$port$path"

                // 1) Pre-auth GetSystemDateAndTime: detects live port/path + syncs WS-Security clock
                val timeRes = executeSoap(
                    url = candidateUrl,
                    camera = camera,
                    soapAction = SOAP_ACTION_GET_SYSTEM_DATE_AND_TIME,
                    bodyContent = "<tds:GetSystemDateAndTime/>",
                    includeSecurityHeader = false,
                    allowUnauthenticatedFallback = false,
                    useProbeTimeout = true
                )
                if (timeRes.isSuccess) {
                    parseSystemDateAndTimeUtcMs(timeRes.getOrThrow())?.let { camUtcMs ->
                        cameraClockOffsetCache[key] = camUtcMs - System.currentTimeMillis()
                    }
                    workingPort = port
                    workingDeviceUrl = candidateUrl
                } else {
                    val err = timeRes.exceptionOrNull()
                    if (isConnectionUnreachable(err)) {
                        lastError = err?.message
                        continue@portLoop
                    }
                }

                // 2) GetCapabilities (tries authenticated first, then unauthenticated pre-auth fallback)
                val capRes = executeSoap(
                    url = candidateUrl,
                    camera = camera,
                    soapAction = SOAP_ACTION_GET_CAPABILITIES,
                    bodyContent = "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>",
                    includeSecurityHeader = true,
                    allowUnauthenticatedFallback = true,
                    useProbeTimeout = true
                )
                if (capRes.isSuccess) {
                    val xml = capRes.getOrThrow()
                    workingPort = port
                    workingDeviceUrl = candidateUrl
                    ptzUrl = extractXAddr(xml, "PTZ")?.let { normalizeXAddrHost(it, host, port) }
                    mediaUrl = extractXAddr(xml, "Media")?.let { normalizeXAddrHost(it, host, port) }
                    lastError = null
                    break@portLoop
                } else {
                    lastError = capRes.exceptionOrNull()?.message ?: lastError
                }

                // 3) GetServices fallback (some firmwares expose PTZ XAddr only here)
                val svcRes = executeSoap(
                    url = candidateUrl,
                    camera = camera,
                    soapAction = SOAP_ACTION_GET_SERVICES,
                    bodyContent = "<tds:GetServices><tds:IncludeCapability>false</tds:IncludeCapability></tds:GetServices>",
                    includeSecurityHeader = true,
                    allowUnauthenticatedFallback = true,
                    useProbeTimeout = true
                )
                if (svcRes.isSuccess) {
                    val xml = svcRes.getOrThrow()
                    workingPort = port
                    workingDeviceUrl = candidateUrl
                    ptzUrl = extractServiceXAddr(xml, "ptz/wsdl")?.let { normalizeXAddrHost(it, host, port) }
                    mediaUrl = (extractServiceXAddr(xml, "ver10/media/wsdl") ?: extractServiceXAddr(xml, "media/wsdl"))
                        ?.let { normalizeXAddrHost(it, host, port) }
                    lastError = null
                    break@portLoop
                } else {
                    lastError = svcRes.exceptionOrNull()?.message ?: lastError
                    if (workingDeviceUrl != null) {
                        break@portLoop
                    }
                }
            }
        }

        if (workingDeviceUrl == null) {
            return QueryResolution(
                capabilities = null,
                error = lastError ?: msg(R.string.ptz_unreachable, "ONVIF unreachable")
            )
        }

        // Best-effort device info query
        val infoRes = executeSoap(
            url = workingDeviceUrl,
            camera = camera,
            soapAction = SOAP_ACTION_GET_DEVICE_INFORMATION,
            bodyContent = "<tds:GetDeviceInformation/>",
            includeSecurityHeader = true,
            allowUnauthenticatedFallback = true,
            useProbeTimeout = true
        )
        if (infoRes.isSuccess) {
            deviceModel = extractDeviceModel(infoRes.getOrThrow())
        }

        // 4) Query Media Profiles (trying discovered XAddr and common single/split service paths)
        val mediaCandidates = listOfNotNull(
            mediaUrl,
            workingDeviceUrl,
            "http://$host:$workingPort/onvif/media_service",
            "http://$host:$workingPort/onvif/service"
        ).distinct()

        var resolvedProfiles: List<String> = emptyList()
        var chosenProfile: String? = null
        var resolvedMediaUrl = mediaUrl ?: mediaCandidates.first()

        for (candidateMedia in mediaCandidates) {
            val profRes = executeSoap(
                url = candidateMedia,
                camera = camera,
                soapAction = SOAP_ACTION_GET_PROFILES,
                bodyContent = "<trt:GetProfiles/>",
                includeSecurityHeader = true,
                allowUnauthenticatedFallback = false,
                useProbeTimeout = true
            )
            if (profRes.isSuccess) {
                val profXml = profRes.getOrThrow()
                resolvedMediaUrl = candidateMedia
                resolvedProfiles = extractAllProfileTokens(profXml)
                chosenProfile = when {
                    camera.ptzProfileToken.isNotBlank() && resolvedProfiles.contains(camera.ptzProfileToken) ->
                        camera.ptzProfileToken
                    else -> extractFirstProfileToken(profXml) ?: resolvedProfiles.firstOrNull()
                }
                if (!chosenProfile.isNullOrBlank()) {
                    lastError = null
                    break
                }
            } else {
                lastError = profRes.exceptionOrNull()?.message ?: lastError
            }
        }

        val finalProfile = chosenProfile ?: camera.ptzProfileToken.ifBlank { null } ?: "Profile_1"
        val ptzCandidates = listOfNotNull(
            ptzUrl,
            workingDeviceUrl,
            "http://$host:$workingPort/onvif/ptz_service",
            "http://$host:$workingPort/onvif/service"
        ).distinct()

        // 5) Query PTZ Nodes, Presets, and Status BEFORE action to detect working PTZ endpoint & spaces
        var resolvedPtzUrl = ptzCandidates.first()
        var nodeCaps = NodeCapabilities()
        var presets = emptyList<OnvifPreset>()
        var ptzVerified = false

        for (candidatePtz in ptzCandidates) {
            val nodesRes = executeSoap(
                url = candidatePtz,
                camera = camera,
                soapAction = SOAP_ACTION_GET_NODES,
                bodyContent = "<tptz:GetNodes/>",
                includeSecurityHeader = true,
                allowUnauthenticatedFallback = false,
                useProbeTimeout = true
            )
            if (nodesRes.isSuccess) {
                resolvedPtzUrl = candidatePtz
                nodeCaps = parseNodeCapabilities(nodesRes.getOrThrow())
                ptzVerified = true
                lastError = null
                break
            }

            // Some cameras deny GetNodes but allow GetStatus / GetPresets directly
            val statusRes = executeSoap(
                url = candidatePtz,
                camera = camera,
                soapAction = SOAP_ACTION_GET_STATUS,
                bodyContent = "<tptz:GetStatus><tptz:ProfileToken>${escapeXml(finalProfile)}</tptz:ProfileToken></tptz:GetStatus>",
                includeSecurityHeader = true,
                allowUnauthenticatedFallback = false,
                useProbeTimeout = true
            )
            if (statusRes.isSuccess) {
                resolvedPtzUrl = candidatePtz
                ptzVerified = true
                lastError = null
                break
            } else {
                lastError = nodesRes.exceptionOrNull()?.message ?: statusRes.exceptionOrNull()?.message ?: lastError
            }
        }

        val presetsRes = executeSoap(
            url = resolvedPtzUrl,
            camera = camera,
            soapAction = SOAP_ACTION_GET_PRESETS,
            bodyContent = "<tptz:GetPresets><tptz:ProfileToken>${escapeXml(finalProfile)}</tptz:ProfileToken></tptz:GetPresets>",
            includeSecurityHeader = true,
            allowUnauthenticatedFallback = false,
            useProbeTimeout = true
        )
        if (presetsRes.isSuccess) {
            presets = extractPresets(presetsRes.getOrThrow())
            if (!ptzVerified) {
                ptzVerified = true
                lastError = null
            }
        }

        val caps = CachedOnvifCapabilities(
            ptzUrl = resolvedPtzUrl,
            mediaUrl = resolvedMediaUrl,
            profileToken = finalProfile,
            availableProfiles = if (resolvedProfiles.isNotEmpty()) resolvedProfiles else listOf(finalProfile),
            presets = presets,
            resolvedPort = workingPort,
            deviceModel = deviceModel,
            nodeCaps = nodeCaps,
            clockOffsetMs = cameraClockOffsetCache[key] ?: 0L
        )

        // If device responded and we verified at least Media GetProfiles or PTZ endpoint, allow action cleanly
        val isUsable = ptzVerified || chosenProfile != null
        return QueryResolution(
            capabilities = caps,
            error = if (isUsable) null else (lastError ?: msg(R.string.ptz_unreachable, "ONVIF unreachable"))
        )
    }

    private fun executeSoap(
        url: String,
        camera: CameraEntity,
        soapAction: String,
        bodyContent: String,
        includeSecurityHeader: Boolean = true,
        allowUnauthenticatedFallback: Boolean = false,
        useProbeTimeout: Boolean = false
    ): Result<String> {
        val (user, pass) = resolveCredentials(camera)
        val key = cacheKey(camera)
        val clockOffsetMs = cameraClockOffsetCache[key] ?: 0L
        val httpClient = if (useProbeTimeout) probeClient else client

        val preferredDialect = cameraSoapDialectCache[key]
        val orderedDialects = listOfNotNull(
            preferredDialect,
            SoapDialect.SOAP12_NO_ACTION,
            SoapDialect.SOAP12_WITH_ACTION,
            SoapDialect.SOAP11
        ).distinct()

        var bestFailure: SoapCallOutcome? = null

        if (includeSecurityHeader) {
            for (dialect in orderedDialects) {
                val outcome = sendSoapRequest(
                    httpClient = httpClient,
                    url = url,
                    username = user,
                    password = pass,
                    soapAction = soapAction,
                    bodyContent = bodyContent,
                    dialect = dialect,
                    clockOffsetMs = clockOffsetMs,
                    includeSecurityHeader = true,
                    usePasswordText = false,
                    authHeader = null
                )
                if (outcome.isSuccess) {
                    cameraSoapDialectCache[key] = dialect
                    return outcome.toKotlinResult()
                }
                bestFailure = pickMoreInformativeFailure(bestFailure, outcome)

                if (isConnectionUnreachable(outcome.error)) {
                    return outcome.toKotlinResult()
                }

                // Handle 401 HTTP Digest / Basic challenges cleanly
                if (outcome.httpCode == 401 && user.isNotBlank()) {
                    val digestHeader = outcome.wwwAuthenticate?.let { challenge ->
                        if (challenge.startsWith("Digest", ignoreCase = true)) {
                            buildDigestAuthHeader(challenge, url, "POST", user, pass)
                        } else null
                    }
                    if (digestHeader != null) {
                        val digestAttempt = sendSoapRequest(
                            httpClient = httpClient,
                            url = url,
                            username = user,
                            password = pass,
                            soapAction = soapAction,
                            bodyContent = bodyContent,
                            dialect = dialect,
                            clockOffsetMs = clockOffsetMs,
                            includeSecurityHeader = true,
                            usePasswordText = false,
                            authHeader = digestHeader
                        )
                        if (digestAttempt.isSuccess) {
                            cameraSoapDialectCache[key] = dialect
                            return digestAttempt.toKotlinResult()
                        }
                        bestFailure = pickMoreInformativeFailure(bestFailure, digestAttempt)
                    }

                    val basicAttempt = sendSoapRequest(
                        httpClient = httpClient,
                        url = url,
                        username = user,
                        password = pass,
                        soapAction = soapAction,
                        bodyContent = bodyContent,
                        dialect = dialect,
                        clockOffsetMs = clockOffsetMs,
                        includeSecurityHeader = true,
                        usePasswordText = false,
                        authHeader = buildBasicAuthHeader(user, pass)
                    )
                    if (basicAttempt.isSuccess) {
                        cameraSoapDialectCache[key] = dialect
                        return basicAttempt.toKotlinResult()
                    }
                    bestFailure = pickMoreInformativeFailure(bestFailure, basicAttempt)
                }

                // If 400 wasn't a schema/Content-Type mismatch, stop probing dialects needlessly unless 400/415/500
                if (outcome.httpCode !in listOf(400, 401, 415, 500)) {
                    break
                }
            }

            // Fallback: some cameras expect WS-Security PasswordText or reject Digest due to custom auth rules
            if (user.isNotBlank() && (bestFailure?.httpCode == 400 || bestFailure?.httpCode == 401)) {
                val textOutcome = sendSoapRequest(
                    httpClient = httpClient,
                    url = url,
                    username = user,
                    password = pass,
                    soapAction = soapAction,
                    bodyContent = bodyContent,
                    dialect = preferredDialect ?: SoapDialect.SOAP12_NO_ACTION,
                    clockOffsetMs = clockOffsetMs,
                    includeSecurityHeader = true,
                    usePasswordText = true,
                    authHeader = null
                )
                if (textOutcome.isSuccess) {
                    return textOutcome.toKotlinResult()
                }
                bestFailure = pickMoreInformativeFailure(bestFailure, textOutcome)
            }
        }

        // Pre-auth ONVIF calls (GetSystemDateAndTime, GetCapabilities, GetServices)
        if (!includeSecurityHeader || allowUnauthenticatedFallback) {
            for (dialect in orderedDialects) {
                val noSec = sendSoapRequest(
                    httpClient = httpClient,
                    url = url,
                    username = "",
                    password = "",
                    soapAction = soapAction,
                    bodyContent = bodyContent,
                    dialect = dialect,
                    clockOffsetMs = clockOffsetMs,
                    includeSecurityHeader = false,
                    usePasswordText = false,
                    authHeader = null
                )
                if (noSec.isSuccess) {
                    cameraSoapDialectCache[key] = dialect
                    return noSec.toKotlinResult()
                }
                bestFailure = pickMoreInformativeFailure(bestFailure, noSec)
                if (isConnectionUnreachable(noSec.error)) {
                    break
                }
            }
        }

        return (bestFailure ?: SoapCallOutcome(error = IllegalStateException("ONVIF request failed"))).toKotlinResult()
    }

    private data class SoapCallOutcome(
        val body: String? = null,
        val httpCode: Int = -1,
        val wwwAuthenticate: String? = null,
        val error: Throwable? = null
    ) {
        val isSuccess: Boolean get() = error == null && body != null
        fun toKotlinResult(): Result<String> = if (isSuccess) Result.success(body!!) else Result.failure(error ?: IllegalStateException("SOAP error"))
    }

    private fun pickMoreInformativeFailure(prev: SoapCallOutcome?, next: SoapCallOutcome): SoapCallOutcome {
        if (prev == null) return next
        val prevMsg = prev.error?.message.orEmpty()
        val nextMsg = next.error?.message.orEmpty()
        return when {
            next.httpCode == 401 && prev.httpCode != 401 -> next
            !nextMsg.startsWith("HTTP ") && prevMsg.startsWith("HTTP ") -> next
            next.httpCode > 0 && prev.httpCode <= 0 -> next
            else -> prev
        }
    }

    private fun isConnectionUnreachable(err: Throwable?): Boolean {
        return err is ConnectException ||
            err is SocketTimeoutException ||
            err is NoRouteToHostException
    }

    private fun isSchemaOrActionRejection(errMsg: String): Boolean {
        return errMsg.contains("400") ||
            errMsg.contains("Bad Request", ignoreCase = true) ||
            errMsg.contains("Invalid", ignoreCase = true) ||
            errMsg.contains("ActionNotSupported", ignoreCase = true) ||
            errMsg.contains("WellKnown", ignoreCase = true) ||
            errMsg.contains("Validation", ignoreCase = true)
    }

    private fun sendSoapRequest(
        httpClient: OkHttpClient,
        url: String,
        username: String,
        password: String,
        soapAction: String,
        bodyContent: String,
        dialect: SoapDialect,
        clockOffsetMs: Long,
        includeSecurityHeader: Boolean,
        usePasswordText: Boolean,
        authHeader: String?
    ): SoapCallOutcome {
        return try {
            val useSoap11 = dialect == SoapDialect.SOAP11
            val envelope = buildSoapEnvelope(
                username = if (includeSecurityHeader) username else "",
                password = password,
                bodyContent = bodyContent,
                useSoap11 = useSoap11,
                clockOffsetMs = clockOffsetMs,
                usePasswordText = usePasswordText
            )

            val contentTypeString = when (dialect) {
                SoapDialect.SOAP11 -> "text/xml; charset=utf-8"
                SoapDialect.SOAP12_WITH_ACTION -> "application/soap+xml; charset=utf-8; action=\"$soapAction\""
                SoapDialect.SOAP12_NO_ACTION -> "application/soap+xml; charset=utf-8"
            }
            val mediaType = contentTypeString.toMediaType()
            val builder = Request.Builder()
                .url(url)
                .header("Connection", "close")
                .post(envelope.toRequestBody(mediaType))

            if (useSoap11) {
                builder.header("SOAPAction", "\"$soapAction\"")
            }
            if (!authHeader.isNullOrBlank()) {
                builder.header("Authorization", authHeader)
            }

            httpClient.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                resp.header("Date")?.let { dateHeader ->
                    parseHttpDateMillis(dateHeader)?.let { serverMs ->
                        // Used only if GetSystemDateAndTime didn't already provide exact UTC
                        val diff = serverMs - System.currentTimeMillis()
                        if (abs(diff) > 30_000L && clockOffsetMs == 0L) {
                            // Best-effort hint handled naturally when needed
                        }
                    }
                }
                if (!resp.isSuccessful) {
                    val fault = extractSoapFault(text)
                    val detail = fault ?: "HTTP ${resp.code} ${resp.message}".trim()
                    SoapCallOutcome(
                        httpCode = resp.code,
                        wwwAuthenticate = resp.header("WWW-Authenticate"),
                        error = IllegalStateException(detail)
                    )
                } else {
                    SoapCallOutcome(body = text, httpCode = resp.code)
                }
            }
        } catch (t: Throwable) {
            SoapCallOutcome(error = t)
        }
    }

    companion object {
        private const val SOAP_ACTION_GET_SYSTEM_DATE_AND_TIME = "http://www.onvif.org/ver10/device/wsdl/GetSystemDateAndTime"
        private const val SOAP_ACTION_GET_DEVICE_INFORMATION = "http://www.onvif.org/ver10/device/wsdl/GetDeviceInformation"
        private const val SOAP_ACTION_GET_CAPABILITIES = "http://www.onvif.org/ver10/device/wsdl/GetCapabilities"
        private const val SOAP_ACTION_GET_SERVICES = "http://www.onvif.org/ver10/device/wsdl/GetServices"
        private const val SOAP_ACTION_GET_PROFILES = "http://www.onvif.org/ver10/media/wsdl/GetProfiles"
        private const val SOAP_ACTION_GET_STREAM_URI = "http://www.onvif.org/ver10/media/wsdl/GetStreamUri"
        private const val SOAP_ACTION_GET_NODES = "http://www.onvif.org/ver20/ptz/wsdl/GetNodes"
        private const val SOAP_ACTION_GET_PRESETS = "http://www.onvif.org/ver20/ptz/wsdl/GetPresets"
        private const val SOAP_ACTION_GET_STATUS = "http://www.onvif.org/ver20/ptz/wsdl/GetStatus"
        private const val SOAP_ACTION_CONTINUOUS_MOVE = "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove"
        private const val SOAP_ACTION_RELATIVE_MOVE = "http://www.onvif.org/ver20/ptz/wsdl/RelativeMove"
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
            includeAllAxes: Boolean = false,
            panTiltSpaceUri: String = PAN_TILT_VELOCITY_SPACE,
            zoomSpaceUri: String = ZOOM_VELOCITY_SPACE,
            includeTimeout: Boolean = false
        ): String {
            val hasPanTilt = includeAllAxes || abs(panX) > 0.001f || abs(tiltY) > 0.001f
            val hasZoom = includeAllAxes || abs(zoomX) > 0.001f
            val panTiltAttr = if (includeSpaceUri) " space=\"${escapeXml(panTiltSpaceUri)}\"" else ""
            val zoomAttr = if (includeSpaceUri) " space=\"${escapeXml(zoomSpaceUri)}\"" else ""

            val panTiltXml = if (hasPanTilt || !hasZoom) {
                "<tt:PanTilt x=\"${String.format(Locale.US, "%.2f", panX)}\" y=\"${String.format(Locale.US, "%.2f", tiltY)}\"$panTiltAttr/>"
            } else ""
            val zoomXml = if (hasZoom) {
                "<tt:Zoom x=\"${String.format(Locale.US, "%.2f", zoomX)}\"$zoomAttr/>"
            } else ""
            val timeoutXml = if (includeTimeout) "<tptz:Timeout>PT5S</tptz:Timeout>" else ""

            return "<tptz:ContinuousMove>" +
                "<tptz:ProfileToken>${escapeXml(profileToken)}</tptz:ProfileToken>" +
                "<tptz:Velocity>$panTiltXml$zoomXml</tptz:Velocity>" +
                timeoutXml +
                "</tptz:ContinuousMove>"
        }

        fun buildRelativeMoveBody(
            profileToken: String,
            panDelta: Float,
            tiltDelta: Float,
            zoomDelta: Float,
            spaceUri: String? = null
        ): String {
            val hasPanTilt = abs(panDelta) > 0.001f || abs(tiltDelta) > 0.001f
            val hasZoom = abs(zoomDelta) > 0.001f
            val spaceAttr = if (!spaceUri.isNullOrBlank()) " space=\"${escapeXml(spaceUri)}\"" else ""
            val panTiltXml = if (hasPanTilt || !hasZoom) {
                "<tt:PanTilt x=\"${String.format(Locale.US, "%.2f", panDelta)}\" y=\"${String.format(Locale.US, "%.2f", tiltDelta)}\"$spaceAttr/>"
            } else ""
            val zoomXml = if (hasZoom) {
                "<tt:Zoom x=\"${String.format(Locale.US, "%.2f", zoomDelta)}\"/>"
            } else ""
            return "<tptz:RelativeMove>" +
                "<tptz:ProfileToken>${escapeXml(profileToken)}</tptz:ProfileToken>" +
                "<tptz:Translation>$panTiltXml$zoomXml</tptz:Translation>" +
                "</tptz:RelativeMove>"
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
            useSoap11: Boolean = false,
            clockOffsetMs: Long = 0L,
            usePasswordText: Boolean = false
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
                val effectiveTimeMs = System.currentTimeMillis() + clockOffsetMs
                val created = sdf.format(Date(effectiveTimeMs))

                val passwordType: String
                val passwordValue: String
                if (usePasswordText) {
                    passwordType = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd#PasswordText"
                    passwordValue = escapeXml(password)
                } else {
                    passwordType = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd#PasswordDigest"
                    val digestInput = nonceBytes + created.toByteArray(Charsets.UTF_8) + password.toByteArray(Charsets.UTF_8)
                    passwordValue = MessageDigest.getInstance("SHA-1").digest(digestInput).toByteString().base64()
                }

                "<s:Header>" +
                    "<wsse:Security xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" " +
                    "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" +
                    "<wsse:UsernameToken>" +
                    "<wsse:Username>${escapeXml(username)}</wsse:Username>" +
                    "<wsse:Password Type=\"$passwordType\">$passwordValue</wsse:Password>" +
                    "<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd#Base64Binary\">$nonceB64</wsse:Nonce>" +
                    "<wsu:Created>$created</wsu:Created>" +
                    "</wsse:UsernameToken>" +
                    "</wsse:Security>" +
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

        fun buildBasicAuthHeader(username: String, password: String): String {
            val basic = "$username:$password".toByteArray(Charsets.UTF_8).toByteString().base64()
            return "Basic $basic"
        }

        fun buildDigestAuthHeader(
            wwwAuthenticate: String,
            url: String,
            method: String,
            username: String,
            password: String
        ): String? {
            if (!wwwAuthenticate.contains("Digest", ignoreCase = true)) return null
            val params = parseDigestParams(wwwAuthenticate)
            val realm = params["realm"] ?: return null
            val nonce = params["nonce"] ?: return null
            val qopRaw = params["qop"]
            val qop = qopRaw?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
            val opaque = params["opaque"]
            val uriObj = runCatching { URI.create(url) }.getOrNull() ?: return null
            val digestUri = buildString {
                append(uriObj.rawPath?.takeIf { it.isNotBlank() } ?: "/")
                uriObj.rawQuery?.let { append("?").append(it) }
            }

            val ha1 = md5Hex("$username:$realm:$password")
            val ha2 = md5Hex("$method:$digestUri")

            return if (qop != null) {
                val nc = "00000001"
                val cnonceBytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
                val cnonce = cnonceBytes.toByteString().hex()
                val response = md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
                buildString {
                    append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$digestUri\", response=\"$response\", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
                    if (!opaque.isNullOrBlank()) append(", opaque=\"$opaque\"")
                }
            } else {
                val response = md5Hex("$ha1:$nonce:$ha2")
                buildString {
                    append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$digestUri\", response=\"$response\"")
                    if (!opaque.isNullOrBlank()) append(", opaque=\"$opaque\"")
                }
            }
        }

        private fun parseDigestParams(header: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            val regex = Regex("([a-zA-Z]+)=(\"([^\"]*)\"|([^,\\s]+))")
            for (m in regex.findAll(header)) {
                val key = m.groupValues[1].lowercase(Locale.US)
                val value = m.groupValues[3].ifEmpty { m.groupValues[4] }
                map[key] = value
            }
            return map
        }

        private fun md5Hex(input: String): String {
            return MessageDigest.getInstance("MD5")
                .digest(input.toByteArray(Charsets.UTF_8))
                .toByteString()
                .hex()
        }

        fun parseSystemDateAndTimeUtcMs(xml: String): Long? {
            val utcBlock = Regex("<(?:[a-zA-Z0-9]+:)?UTCDateTime\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9]+:)?UTCDateTime>")
                .find(xml)?.groupValues?.getOrNull(1)
                ?: Regex("<(?:[a-zA-Z0-9]+:)?LocalDateTime\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9]+:)?LocalDateTime>")
                    .find(xml)?.groupValues?.getOrNull(1)
                ?: return null

            fun extractInt(tag: String): Int? =
                Regex("<(?:[a-zA-Z0-9]+:)?$tag>(\\d+)</(?:[a-zA-Z0-9]+:)?$tag>")
                    .find(utcBlock)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val year = extractInt("Year") ?: return null
            val month = extractInt("Month") ?: return null
            val day = extractInt("Day") ?: return null
            val hour = extractInt("Hour") ?: return null
            val minute = extractInt("Minute") ?: return null
            val second = extractInt("Second") ?: 0

            if (year < 2000 || month !in 1..12 || day !in 1..31) return null
            return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(year, month - 1, day, hour, minute, second)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        private fun parseHttpDateMillis(dateHeader: String): Long? {
            return runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }.parse(dateHeader.trim())?.time
            }.getOrNull()
        }

        fun parseNodeCapabilities(xml: String): NodeCapabilities {
            val hasContinuousPt = xml.contains("ContinuousPanTiltVelocitySpace", ignoreCase = true)
            val hasContinuousZoom = xml.contains("ContinuousZoomVelocitySpace", ignoreCase = true)
            val hasRelativePt = xml.contains("RelativePanTiltTranslationSpace", ignoreCase = true)
            val hasRelativeZoom = xml.contains("RelativeZoomTranslationSpace", ignoreCase = true)
            val hasAnyPtSpace = xml.contains("PanTilt", ignoreCase = true)
            val hasAnyZoomSpace = xml.contains("ZoomSpaces", ignoreCase = true) || xml.contains("ZoomSpace", ignoreCase = true)

            val contPtUri = extractSpaceUri(xml, "ContinuousPanTiltVelocitySpace")
            val contZoomUri = extractSpaceUri(xml, "ContinuousZoomVelocitySpace")
            val relPtUri = extractSpaceUri(xml, "RelativePanTiltTranslationSpace")

            return NodeCapabilities(
                supportsPanTilt = hasAnyPtSpace || !xml.contains("PTZNode", ignoreCase = true),
                supportsZoom = hasContinuousZoom || hasRelativeZoom || hasAnyZoomSpace,
                supportsContinuousMove = hasContinuousPt || !hasRelativePt,
                supportsRelativeMove = hasRelativePt || hasRelativeZoom,
                continuousPanTiltSpaceUri = contPtUri,
                continuousZoomSpaceUri = contZoomUri,
                relativePanTiltSpaceUri = relPtUri
            )
        }

        private fun extractSpaceUri(xml: String, blockTag: String): String? {
            val block = Regex("<(?:[a-zA-Z0-9]+:)?$blockTag\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9]+:)?$blockTag>")
                .find(xml)?.groupValues?.getOrNull(1) ?: return null
            return Regex("<(?:[a-zA-Z0-9]+:)?URI>([^<]+)</(?:[a-zA-Z0-9]+:)?URI>")
                .find(block)?.groupValues?.getOrNull(1)?.trim()
        }

        fun extractPresets(xml: String): List<OnvifPreset> {
            val presetRegex = Regex("<(?:[a-zA-Z0-9]+:)?Preset\\b([^>]*)>([\\s\\S]*?)</(?:[a-zA-Z0-9]+:)?Preset>")
            val tokenRegex = Regex("\\btoken=[\"']([^\"']+)[\"']")
            val nameRegex = Regex("<(?:[a-zA-Z0-9]+:)?Name>([^<]*)</(?:[a-zA-Z0-9]+:)?Name>")
            val results = mutableListOf<OnvifPreset>()
            for (match in presetRegex.findAll(xml)) {
                val token = tokenRegex.find(match.groupValues[1])?.groupValues?.getOrNull(1)?.trim()
                if (!token.isNullOrBlank()) {
                    val name = nameRegex.find(match.groupValues[2])?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    results.add(OnvifPreset(token = token, name = name.ifBlank { "P$token" }))
                }
            }
            return results
        }

        private fun extractDeviceModel(xml: String): String {
            val mfg = Regex("<(?:[a-zA-Z0-9]+:)?Manufacturer>([^<]+)</(?:[a-zA-Z0-9]+:)?Manufacturer>")
                .find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val model = Regex("<(?:[a-zA-Z0-9]+:)?Model>([^<]+)</(?:[a-zA-Z0-9]+:)?Model>")
                .find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return listOf(mfg, model).filter { it.isNotBlank() }.distinct().joinToString(" ")
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

        fun extractAllProfileTokens(xml: String): List<String> {
            val tokenRegex = Regex("<(?:[a-zA-Z0-9]+:)?Profiles\\b[^>]*\\btoken=[\"']([^\"']+)[\"']")
            return tokenRegex.findAll(xml)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf { t -> t.isNotBlank() } }
                .distinct()
                .toList()
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
            return extractAllProfileTokens(xml).firstOrNull()
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
