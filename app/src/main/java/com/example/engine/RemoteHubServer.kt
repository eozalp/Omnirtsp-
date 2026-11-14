package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import com.example.data.model.CameraEntity
import com.example.data.model.MotionAlertEntity
import com.example.data.model.RecordedClipEntity
import com.example.data.model.StreamTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class EndpointKind {
    TAILSCALE,
    WIFI_LAN,
    OTHER
}

data class NetworkEndpoint(
    val interfaceName: String,
    val ipAddress: String,
    val kind: EndpointKind,
    val url: String
)

data class RemoteHubState(
    val isEnabled: Boolean = false,
    val isRunning: Boolean = false,
    val port: Int = RemoteHubServer.DEFAULT_PORT,
    val accessToken: String = "",
    val allowRemoteControl: Boolean = true,
    val endpoints: List<NetworkEndpoint> = emptyList(),
    val activeConnections: Int = 0,
    val lastRequestSummary: String = "",
    val lastError: String? = null,
    val batteryPercent: Int = 100,
    val isPluggedIn: Boolean = false,
    val freeStorageMb: Long = 0L
) {
    val tailscaleEndpoint: NetworkEndpoint?
        get() = endpoints.firstOrNull { it.kind == EndpointKind.TAILSCALE }

    val primaryLanEndpoint: NetworkEndpoint?
        get() = endpoints.firstOrNull { it.kind == EndpointKind.WIFI_LAN }

    val bestAccessUrl: String?
        get() = tailscaleEndpoint?.url ?: primaryLanEndpoint?.url ?: endpoints.firstOrNull()?.url
}

data class HttpByteRange(
    val start: Long,
    val end: Long,
    val totalLength: Long
) {
    val contentLength: Long get() = (end - start + 1L).coerceAtLeast(0L)
}

data class HubCameraSnapshot(
    val id: String,
    val name: String,
    val location: String,
    val onvifPort: Int,
    val rotationDegrees: Float,
    val isMotionDetectionEnabled: Boolean,
    val isLive: Boolean,
    val isReconnecting: Boolean,
    val fps: Int,
    val bitrateKbps: Int,
    val resolution: String,
    val codec: String,
    val isRecording: Boolean
)

data class HubClipSnapshot(
    val id: String,
    val cameraId: String,
    val cameraName: String,
    val timestamp: Long,
    val durationSeconds: Int,
    val fileSizeMb: Float,
    val triggerType: String,
    val filePath: String
)

data class HubAlertSnapshot(
    val id: String,
    val cameraId: String,
    val cameraName: String,
    val timestamp: Long,
    val severity: String,
    val motionLevel: Int,
    val description: String,
    val isRead: Boolean
)

data class HubSystemHealth(
    val deviceName: String = "OmniView Hub",
    val batteryPercent: Int = 100,
    val isPluggedIn: Boolean = false,
    val freeStorageMb: Long = 0L
)

interface RemoteHubBackend {
    fun getSystemHealth(): HubSystemHealth
    fun getCameras(): List<HubCameraSnapshot>
    fun getClips(cameraId: String? = null): List<HubClipSnapshot>
    fun getClipFile(clipId: String): File?
    fun getAlerts(limit: Int = 50): List<HubAlertSnapshot>
    fun captureCameraJpeg(cameraId: String): ByteArray?
    fun performPtz(
        cameraId: String,
        action: String,
        pan: Float,
        tilt: Float,
        zoom: Float,
        presetToken: String
    ): Pair<Boolean, String>

    fun startRecording(cameraId: String): Boolean
    fun stopRecording(cameraId: String): Boolean
    fun triggerSnapshot(cameraId: String): Boolean
}

/**
 * Lightweight, zero-dependency HTTP/1.1 Surveillance Hub Server.
 * Turns a spare Android device into a secure NVR hub reachable over Tailscale VPN or LAN IP.
 */
class RemoteHubServer(
    private val backend: RemoteHubBackend
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var acceptJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val activeClientCount = AtomicInteger(0)
    private val sessions = ConcurrentHashMap<String, Long>()
    private val failedAttemptsByIp = ConcurrentHashMap<String, FailedAuthBucket>()

    private data class FailedAuthBucket(
        val count: Int,
        val windowStartMs: Long,
        val blockedUntilMs: Long = 0L
    )

    private val _state = MutableStateFlow(RemoteHubState())
    val state: StateFlow<RemoteHubState> = _state.asStateFlow()

    val listeningPort: Int
        get() = serverSocket?.localPort ?: _state.value.port

    fun configure(
        enabled: Boolean,
        port: Int,
        accessToken: String,
        allowRemoteControl: Boolean
    ) {
        val safePort = normalizePort(port)
        val safeToken = accessToken.ifBlank { generateSecureToken() }
        val health = safeHealth()
        val endpoints = discoverNetworkEndpoints(safePort)
        val wasRunning = _state.value.isRunning
        val configChanged = _state.value.port != safePort || _state.value.accessToken != safeToken

        if (configChanged) {
            sessions.clear()
        }

        _state.value = _state.value.copy(
            isEnabled = enabled,
            port = safePort,
            accessToken = safeToken,
            allowRemoteControl = allowRemoteControl,
            endpoints = endpoints,
            batteryPercent = health.batteryPercent,
            isPluggedIn = health.isPluggedIn,
            freeStorageMb = health.freeStorageMb,
            lastError = null
        )

        if (enabled) {
            if (!wasRunning || configChanged) {
                restartServerSocket(safePort)
            }
        } else {
            stopServerSocket()
        }
    }

    fun start(
        port: Int = _state.value.port,
        accessToken: String = _state.value.accessToken,
        allowRemoteControl: Boolean = _state.value.allowRemoteControl
    ) {
        configure(
            enabled = true,
            port = port,
            accessToken = accessToken,
            allowRemoteControl = allowRemoteControl
        )
    }

    fun stop() {
        _state.value = _state.value.copy(isEnabled = false)
        stopServerSocket()
    }

    fun refreshNetworkAndHealth() {
        val cur = _state.value
        val activePort = serverSocket?.localPort?.takeIf { it > 0 } ?: cur.port
        val health = safeHealth()
        _state.value = cur.copy(
            endpoints = discoverNetworkEndpoints(activePort),
            batteryPercent = health.batteryPercent,
            isPluggedIn = health.isPluggedIn,
            freeStorageMb = health.freeStorageMb
        )
    }

    private fun safeHealth(): HubSystemHealth {
        return try {
            backend.getSystemHealth()
        } catch (_: Exception) {
            HubSystemHealth()
        }
    }

    @Synchronized
    private fun restartServerSocket(requestedPort: Int) {
        stopServerSocketInternal()
        try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), requestedPort), 32)
            }
            serverSocket = socket
            val boundPort = socket.localPort
            val health = safeHealth()
            _state.value = _state.value.copy(
                isRunning = true,
                port = boundPort,
                endpoints = discoverNetworkEndpoints(boundPort),
                batteryPercent = health.batteryPercent,
                isPluggedIn = health.isPluggedIn,
                freeStorageMb = health.freeStorageMb,
                lastError = null
            )

            acceptJob = scope.launch {
                while (isActive && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        launch(Dispatchers.IO) {
                            handleClientSocket(client)
                        }
                    } catch (e: Exception) {
                        if (!socket.isClosed && isActive) {
                            _state.value = _state.value.copy(lastError = e.message ?: "Accept error")
                            delay(250)
                        } else {
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isRunning = false,
                lastError = e.message ?: "Failed to bind port $requestedPort"
            )
        }
    }

    @Synchronized
    private fun stopServerSocket() {
        stopServerSocketInternal()
        sessions.clear()
        _state.value = _state.value.copy(
            isRunning = false,
            activeConnections = 0
        )
    }

    private fun stopServerSocketInternal() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClientSocket(client: Socket) {
        val currentActive = activeClientCount.incrementAndGet()
        _state.value = _state.value.copy(activeConnections = currentActive)
        try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream(), 16 * 1024)
            val output = client.getOutputStream()
            val remoteIp = client.inetAddress?.hostAddress ?: "unknown"

            val request = readHttpRequest(input) ?: return
            val path = request.pathOnly
            val query = parseQueryParams(request.rawQuery)

            if (path != "/health" && !path.endsWith("/mjpeg") && !path.endsWith("/frame.jpg")) {
                _state.value = _state.value.copy(
                    lastRequestSummary = "$remoteIp • ${request.method} $path"
                )
            }

            // CORS preflight support
            if (request.method.equals("OPTIONS", ignoreCase = true)) {
                sendTextResponse(output, 204, "No Content", "", "text/plain")
                return
            }

            // 1. Public unauthenticated routes
            if (path == "/health") {
                val json = """{"status":"ok","service":"omniview-hub","port":$listeningPort}"""
                sendTextResponse(output, 200, "OK", json, "application/json; charset=utf-8")
                return
            }

            if (path == "/api/login" && request.method.equals("POST", ignoreCase = true)) {
                handleLogin(remoteIp, request, output)
                return
            }

            if (path == "/api/logout" && request.method.equals("POST", ignoreCase = true)) {
                extractSessionCookie(request.headers["cookie"])?.let { sessions.remove(it) }
                val json = """{"ok":true}"""
                sendTextResponse(
                    output = output,
                    statusCode = 200,
                    statusText = "OK",
                    body = json,
                    contentType = "application/json; charset=utf-8",
                    extraHeaders = listOf("Set-Cookie: $SESSION_COOKIE_NAME=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict")
                )
                return
            }

            val isAuthenticated = isRequestAuthenticated(request, query)

            if (path == "/" || path == "/index.html") {
                val html = buildWebConsoleHtml(isAuthenticated = isAuthenticated)
                sendTextResponse(output, 200, "OK", html, "text/html; charset=utf-8")
                return
            }

            // 2. Protected API endpoints
            if (!isAuthenticated) {
                recordFailedAttempt(remoteIp)
                val err = """{"error":"Unauthorized"}"""
                sendTextResponse(output, 401, "Unauthorized", err, "application/json; charset=utf-8")
                return
            }

            routeAuthenticatedApi(
                method = request.method.uppercase(Locale.US),
                path = path,
                query = query,
                request = request,
                client = client,
                output = output
            )
        } catch (_: Exception) {
            // Client disconnected or timeout
        } finally {
            try {
                client.close()
            } catch (_: Exception) {}
            val remaining = activeClientCount.decrementAndGet().coerceAtLeast(0)
            _state.value = _state.value.copy(activeConnections = remaining)
        }
    }

    private fun handleLogin(remoteIp: String, request: ParsedHttpRequest, output: OutputStream) {
        val now = System.currentTimeMillis()
        val bucket = failedAttemptsByIp[remoteIp]
        if (bucket != null && bucket.blockedUntilMs > now) {
            val retrySecs = ((bucket.blockedUntilMs - now) / 1000L).coerceAtLeast(1L)
            val err = """{"error":${jsonStr("Too many failed attempts. Try again in ${retrySecs}s.")}}"""
            sendTextResponse(
                output = output,
                statusCode = 429,
                statusText = "Too Many Requests",
                body = err,
                contentType = "application/json; charset=utf-8",
                extraHeaders = listOf("Retry-After: $retrySecs")
            )
            return
        }

        val bodyStr = request.body.toString(StandardCharsets.UTF_8).trim()
        val submittedToken = if (bodyStr.startsWith("{")) {
            extractJsonString(bodyStr, "token").orEmpty()
        } else {
            parseQueryParams(bodyStr)["token"].orEmpty()
        }

        val expectedToken = _state.value.accessToken
        if (submittedToken.isNotBlank() && constantTimeEquals(submittedToken, expectedToken)) {
            failedAttemptsByIp.remove(remoteIp)
            val sessionId = generateSessionId()
            sessions[sessionId] = now + SESSION_TTL_MS
            val resp = """{"ok":true}"""
            sendTextResponse(
                output = output,
                statusCode = 200,
                statusText = "OK",
                body = resp,
                contentType = "application/json; charset=utf-8",
                extraHeaders = listOf("Set-Cookie: $SESSION_COOKIE_NAME=$sessionId; Path=/; HttpOnly; SameSite=Strict")
            )
        } else {
            recordFailedAttempt(remoteIp)
            val resp = """{"ok":false,"error":"Invalid access token"}"""
            sendTextResponse(output, 401, "Unauthorized", resp, "application/json; charset=utf-8")
        }
    }

    private fun recordFailedAttempt(remoteIp: String) {
        val now = System.currentTimeMillis()
        val existing = failedAttemptsByIp[remoteIp]
        if (existing == null || now - existing.windowStartMs > 60_000L) {
            failedAttemptsByIp[remoteIp] = FailedAuthBucket(count = 1, windowStartMs = now)
        } else {
            val nextCount = existing.count + 1
            val blockedUntil = if (nextCount >= 8) now + 30_000L else existing.blockedUntilMs
            failedAttemptsByIp[remoteIp] = existing.copy(count = nextCount, blockedUntilMs = blockedUntil)
        }
    }

    private fun isRequestAuthenticated(
        request: ParsedHttpRequest,
        query: Map<String, String>
    ): Boolean {
        val expected = _state.value.accessToken
        if (expected.isBlank()) return false

        val authHeader = request.headers["authorization"].orEmpty()
        if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
            val bearer = authHeader.substring(7).trim()
            if (constantTimeEquals(bearer, expected)) return true
        }

        val xToken = request.headers["x-hub-token"].orEmpty().trim()
        if (xToken.isNotEmpty() && constantTimeEquals(xToken, expected)) return true

        val queryToken = query["token"].orEmpty().trim()
        if (queryToken.isNotEmpty() && constantTimeEquals(queryToken, expected)) return true

        val cookieToken = extractSessionCookie(request.headers["cookie"])
        if (!cookieToken.isNullOrBlank()) {
            val expiresAt = sessions[cookieToken]
            if (expiresAt != null) {
                if (expiresAt > System.currentTimeMillis()) {
                    return true
                } else {
                    sessions.remove(cookieToken)
                }
            }
        }
        return false
    }

    private fun routeAuthenticatedApi(
        method: String,
        path: String,
        query: Map<String, String>,
        request: ParsedHttpRequest,
        client: Socket,
        output: OutputStream
    ) {
        when {
            path == "/api/status" && method == "GET" -> {
                val health = safeHealth()
                val activePort = listeningPort
                val endpoints = discoverNetworkEndpoints(activePort)
                _state.value = _state.value.copy(
                    endpoints = endpoints,
                    batteryPercent = health.batteryPercent,
                    isPluggedIn = health.isPluggedIn,
                    freeStorageMb = health.freeStorageMb
                )
                val cameras = backend.getCameras()
                val endpointsJson = endpoints.joinToString(",", "[", "]") { ep ->
                    """{"interface":${jsonStr(ep.interfaceName)},"ip":${jsonStr(ep.ipAddress)},"kind":${jsonStr(ep.kind.name)},"url":${jsonStr(ep.url)}}"""
                }
                val json = """{"deviceName":${jsonStr(health.deviceName)},"batteryPercent":${health.batteryPercent},"isPluggedIn":${health.isPluggedIn},"freeStorageMb":${health.freeStorageMb},"port":$activePort,"allowRemoteControl":${_state.value.allowRemoteControl},"activeConnections":${activeClientCount.get()},"cameraCount":${cameras.size},"recordingCount":${cameras.count { it.isRecording }},"endpoints":$endpointsJson}"""
                sendTextResponse(output, 200, "OK", json, "application/json; charset=utf-8")
            }

            path == "/api/cameras" && method == "GET" -> {
                val cameras = backend.getCameras()
                val camsJson = cameras.joinToString(",", "[", "]") { cam ->
                    """{"id":${jsonStr(cam.id)},"name":${jsonStr(cam.name)},"location":${jsonStr(cam.location)},"onvifPort":${cam.onvifPort},"rotationDegrees":${cam.rotationDegrees},"isMotionDetectionEnabled":${cam.isMotionDetectionEnabled},"isLive":${cam.isLive},"isReconnecting":${cam.isReconnecting},"fps":${cam.fps},"bitrateKbps":${cam.bitrateKbps},"resolution":${jsonStr(cam.resolution)},"codec":${jsonStr(cam.codec)},"isRecording":${cam.isRecording}}"""
                }
                sendTextResponse(output, 200, "OK", """{"cameras":$camsJson}""", "application/json; charset=utf-8")
            }

            path.startsWith("/api/cameras/") && path.endsWith("/frame.jpg") && method == "GET" -> {
                val cameraId = path.removePrefix("/api/cameras/").removeSuffix("/frame.jpg").trim('/')
                val jpeg = backend.captureCameraJpeg(cameraId)
                if (jpeg == null || jpeg.isEmpty()) {
                    sendTextResponse(output, 404, "Not Found", """{"error":"Camera frame unavailable"}""", "application/json")
                    return
                }
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: image/jpeg\r\n")
                    append("Content-Length: ${jpeg.size}\r\n")
                    append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(headers.toByteArray(StandardCharsets.US_ASCII))
                output.write(jpeg)
                output.flush()
            }

            path.startsWith("/api/cameras/") && path.endsWith("/mjpeg") && method == "GET" -> {
                val cameraId = path.removePrefix("/api/cameras/").removeSuffix("/mjpeg").trim('/')
                streamMjpegLoop(cameraId, client, output)
            }

            path.startsWith("/api/cameras/") && path.endsWith("/ptz") && method == "POST" -> {
                if (!_state.value.allowRemoteControl) {
                    sendTextResponse(output, 403, "Forbidden", """{"error":"Remote control disabled on hub"}""", "application/json")
                    return
                }
                val cameraId = path.removePrefix("/api/cameras/").removeSuffix("/ptz").trim('/')
                val rawBody = request.body.toString(StandardCharsets.UTF_8)
                val action = extractJsonString(rawBody, "action") ?: "stop"
                val pan = extractJsonDouble(rawBody, "pan")?.toFloat() ?: 0f
                val tilt = extractJsonDouble(rawBody, "tilt")?.toFloat() ?: 0f
                val zoom = extractJsonDouble(rawBody, "zoom")?.toFloat() ?: 0f
                val presetToken = extractJsonString(rawBody, "presetToken").orEmpty()
                val (ok, msg) = backend.performPtz(cameraId, action, pan, tilt, zoom, presetToken)
                val body = """{"ok":$ok,"message":${jsonStr(msg)}}"""
                sendTextResponse(output, if (ok) 200 else 400, if (ok) "OK" else "Bad Request", body, "application/json; charset=utf-8")
            }

            path.startsWith("/api/cameras/") && path.endsWith("/record/start") && method == "POST" -> {
                if (!_state.value.allowRemoteControl) {
                    sendTextResponse(output, 403, "Forbidden", """{"error":"Remote control disabled on hub"}""", "application/json")
                    return
                }
                val cameraId = path.removePrefix("/api/cameras/").removeSuffix("/record/start").trim('/')
                val started = backend.startRecording(cameraId)
                val body = """{"ok":$started,"isRecording":$started}"""
                sendTextResponse(output, if (started) 200 else 404, if (started) "OK" else "Not Found", body, "application/json; charset=utf-8")
            }

            path.startsWith("/api/cameras/") && path.endsWith("/record/stop") && method == "POST" -> {
                if (!_state.value.allowRemoteControl) {
                    sendTextResponse(output, 403, "Forbidden", """{"error":"Remote control disabled on hub"}""", "application/json")
                    return
                }
                val cameraId = path.removePrefix("/api/cameras/").removeSuffix("/record/stop").trim('/')
                val stopped = backend.stopRecording(cameraId)
                val body = """{"ok":$stopped,"isRecording":false}"""
                sendTextResponse(output, if (stopped) 200 else 404, if (stopped) "OK" else "Not Found", body, "application/json; charset=utf-8")
            }

            path.startsWith("/api/cameras/") && path.endsWith("/snapshot") && method == "POST" -> {
                if (!_state.value.allowRemoteControl) {
                    sendTextResponse(output, 403, "Forbidden", """{"error":"Remote control disabled on hub"}""", "application/json")
                    return
                }
                val cameraId = path.removePrefix("/api/cameras/").removeSuffix("/snapshot").trim('/')
                val triggered = backend.triggerSnapshot(cameraId)
                val body = """{"ok":$triggered}"""
                sendTextResponse(output, if (triggered) 200 else 404, if (triggered) "OK" else "Not Found", body, "application/json; charset=utf-8")
            }

            path == "/api/clips" && method == "GET" -> {
                val cameraFilter = query["cameraId"]?.takeIf { it.isNotBlank() }
                val clips = backend.getClips(cameraFilter)
                val clipsJson = clips.joinToString(",", "[", "]") { clip ->
                    """{"id":${jsonStr(clip.id)},"cameraId":${jsonStr(clip.cameraId)},"cameraName":${jsonStr(clip.cameraName)},"timestamp":${clip.timestamp},"durationSeconds":${clip.durationSeconds},"fileSizeMb":${clip.fileSizeMb},"triggerType":${jsonStr(clip.triggerType)},"streamUrl":${jsonStr("/api/clips/${clip.id}/stream")}}"""
                }
                sendTextResponse(output, 200, "OK", """{"clips":$clipsJson}""", "application/json; charset=utf-8")
            }

            path.startsWith("/api/clips/") && path.endsWith("/stream") && (method == "GET" || method == "HEAD") -> {
                val clipId = path.removePrefix("/api/clips/").removeSuffix("/stream").trim('/')
                val file = backend.getClipFile(clipId)
                if (file == null || !file.exists() || file.length() <= 0L) {
                    sendTextResponse(output, 404, "Not Found", """{"error":"Clip file not found"}""", "application/json")
                    return
                }
                streamFileWithRangeSupport(
                    file = file,
                    rangeHeader = request.headers["range"],
                    headOnly = method == "HEAD",
                    client = client,
                    output = output
                )
            }

            path == "/api/alerts" && method == "GET" -> {
                val alerts = backend.getAlerts(50)
                val alertsJson = alerts.joinToString(",", "[", "]") { a ->
                    """{"id":${jsonStr(a.id)},"cameraId":${jsonStr(a.cameraId)},"cameraName":${jsonStr(a.cameraName)},"timestamp":${a.timestamp},"severity":${jsonStr(a.severity)},"motionLevel":${a.motionLevel},"description":${jsonStr(a.description)},"isRead":${a.isRead}}"""
                }
                sendTextResponse(output, 200, "OK", """{"alerts":$alertsJson}""", "application/json; charset=utf-8")
            }

            else -> {
                sendTextResponse(output, 404, "Not Found", "{\"error\":\"Route not found\"}", "application/json; charset=utf-8")
            }
        }
    }

    private fun streamMjpegLoop(cameraId: String, client: Socket, output: OutputStream) {
        client.soTimeout = 30_000
        val boundary = "omniviewframe"
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n")
            append("Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n")
            append("Pragma: no-cache\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        while (!client.isClosed && _state.value.isRunning) {
            val jpeg = backend.captureCameraJpeg(cameraId)
            if (jpeg != null && jpeg.isNotEmpty()) {
                val partHeader = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                output.write(partHeader.toByteArray(StandardCharsets.US_ASCII))
                output.write(jpeg)
                output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
            }
            Thread.sleep(150L)
        }
    }

    private fun streamFileWithRangeSupport(
        file: File,
        rangeHeader: String?,
        headOnly: Boolean,
        client: Socket,
        output: OutputStream
    ) {
        client.soTimeout = 60_000
        val totalLen = file.length()
        if (rangeHeader != null && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            val parsedRange = parseHttpRange(rangeHeader, totalLen)
            if (parsedRange == null) {
                val hdr = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalLen\r\nConnection: close\r\n\r\n"
                output.write(hdr.toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                return
            }
            val hdr = buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Type: video/mp4\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Range: bytes ${parsedRange.start}-${parsedRange.end}/$totalLen\r\n")
                append("Content-Length: ${parsedRange.contentLength}\r\n")
                append("Cache-Control: private, max-age=60\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(hdr.toByteArray(StandardCharsets.US_ASCII))
            if (!headOnly) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(parsedRange.start)
                    val buffer = ByteArray(32 * 1024)
                    var remaining = parsedRange.contentLength
                    while (remaining > 0L) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            output.flush()
        } else {
            val hdr = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: video/mp4\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $totalLen\r\n")
                append("Cache-Control: private, max-age=60\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(hdr.toByteArray(StandardCharsets.US_ASCII))
            if (!headOnly) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            output.flush()
        }
    }

    private data class ParsedHttpRequest(
        val method: String,
        val pathOnly: String,
        val rawQuery: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private fun readHttpRequest(input: BufferedInputStream): ParsedHttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        while (headerBytes.size() < 32 * 1024) {
            val b = input.read()
            if (b == -1) return null
            headerBytes.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                matched == 1 && b == '\n'.code -> 2
                matched == 3 && b == '\n'.code -> 4
                b == '\n'.code && headerBytes.size() >= 2 -> {
                    val arr = headerBytes.toByteArray()
                    if (arr[arr.size - 2] == '\n'.code.toByte()) 4 else 0
                }
                else -> 0
            }
            if (matched == 4) break
        }
        val headerBlock = headerBytes.toString(StandardCharsets.US_ASCII.name())
        val lines = headerBlock.split("\r\n", "\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val requestParts = lines[0].split(" ")
        if (requestParts.size < 2) return null
        val method = requestParts[0].trim()
        val rawTarget = requestParts[1].trim()
        val qIdx = rawTarget.indexOf('?')
        val pathOnly = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val rawQuery = if (qIdx >= 0) rawTarget.substring(qIdx + 1) else ""

        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase(Locale.US)
                val value = line.substring(colon + 1).trim()
                headers[key] = value
            }
        }

        val contentLength = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 256 * 1024)
        val body = if (contentLength > 0) readExactBytes(input, contentLength) else ByteArray(0)
        return ParsedHttpRequest(method, pathOnly, rawQuery, headers, body)
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(out, offset, count - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == count) out else out.copyOf(offset)
    }

    private fun extractSessionCookie(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        return cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE_NAME=") }
            ?.substringAfter("$SESSION_COOKIE_NAME=")
            ?.trim()
    }

    private fun sendTextResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        body: String,
        contentType: String,
        extraHeaders: List<String> = emptyList()
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            extraHeaders.forEach { append("$it\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (bytes.isNotEmpty()) {
            output.write(bytes)
        }
        output.flush()
    }

    fun release() {
        stop()
    }

    companion object {
        const val DEFAULT_PORT = 8088
        private const val SESSION_COOKIE_NAME = "omniview_hub_session"
        private const val SESSION_TTL_MS = 12L * 60L * 60L * 1000L
        private const val TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        private val secureRandom = SecureRandom()

        fun normalizePort(port: Int): Int {
            return if (port == 0) 0 else port.coerceIn(1024, 65535)
        }

        fun generateSecureToken(length: Int = 12): String {
            return buildString(length) {
                repeat(length) {
                    append(TOKEN_ALPHABET[secureRandom.nextInt(TOKEN_ALPHABET.length)])
                }
            }
        }

        private fun generateSessionId(): String {
            val bytes = ByteArray(24)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun constantTimeEquals(a: String, b: String): Boolean {
            val bytesA = a.toByteArray(StandardCharsets.UTF_8)
            val bytesB = b.toByteArray(StandardCharsets.UTF_8)
            return MessageDigest.isEqual(bytesA, bytesB)
        }

        fun jsonStr(value: String): String {
            val sb = StringBuilder(value.length + 4)
            sb.append('"')
            for (ch in value) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch.code < 0x20) {
                        sb.append("\\u%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
            sb.append('"')
            return sb.toString()
        }

        fun extractJsonString(json: String, field: String): String? {
            val regex = Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            val raw = regex.find(json)?.groupValues?.getOrNull(1) ?: return null
            return raw
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }

        fun extractJsonDouble(json: String, field: String): Double? {
            val regex = Regex("\"${Regex.escape(field)}\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
            return regex.find(json)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        }

        /**
         * Checks whether an IPv4 address belongs to Tailscale's CGNAT range (100.64.0.0/10 -> 100.64..127.x.x).
         */
        fun isTailscaleIpv4(ipAddress: String): Boolean {
            val parts = ipAddress.trim().split(".")
            if (parts.size != 4) return false
            val b0 = parts[0].toIntOrNull() ?: return false
            val b1 = parts[1].toIntOrNull() ?: return false
            val b2 = parts[2].toIntOrNull() ?: return false
            val b3 = parts[3].toIntOrNull() ?: return false
            if (b2 !in 0..255 || b3 !in 0..255) return false
            return b0 == 100 && b1 in 64..127
        }

        fun classifyNetworkInterface(interfaceName: String, ipAddress: String): EndpointKind {
            val iface = interfaceName.lowercase(Locale.US)
            return when {
                isTailscaleIpv4(ipAddress) || iface.startsWith("tailscale") -> EndpointKind.TAILSCALE
                iface.startsWith("wlan") || iface.startsWith("eth") || iface.startsWith("en") ||
                    ipAddress.startsWith("192.168.") || ipAddress.startsWith("10.") -> EndpointKind.WIFI_LAN
                else -> EndpointKind.OTHER
            }
        }

        fun discoverNetworkEndpoints(port: Int): List<NetworkEndpoint> {
            val discovered = mutableListOf<NetworkEndpoint>()
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
                for (iface in ifaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            val kind = classifyNetworkInterface(iface.name.orEmpty(), ip)
                            discovered.add(
                                NetworkEndpoint(
                                    interfaceName = iface.name.orEmpty(),
                                    ipAddress = ip,
                                    kind = kind,
                                    url = "http://$ip:$port"
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
            return discovered
                .distinctBy { it.ipAddress }
                .sortedWith(compareBy<NetworkEndpoint> { it.kind.ordinal }.thenBy { it.ipAddress })
        }

        fun parseQueryParams(rawQuery: String): Map<String, String> {
            if (rawQuery.isBlank()) return emptyMap()
            val result = LinkedHashMap<String, String>()
            for (pair in rawQuery.split("&")) {
                if (pair.isBlank()) continue
                val eq = pair.indexOf('=')
                val key = if (eq >= 0) pair.substring(0, eq) else pair
                val value = if (eq >= 0) pair.substring(eq + 1) else ""
                try {
                    result[URLDecoder.decode(key, "UTF-8")] = URLDecoder.decode(value, "UTF-8")
                } catch (_: Exception) {
                    result[key] = value
                }
            }
            return result
        }

        fun parseHttpRange(rangeHeader: String, totalLength: Long): HttpByteRange? {
            if (totalLength <= 0L) return null
            val raw = rangeHeader.trim()
            if (!raw.startsWith("bytes=", ignoreCase = true)) return null
            val spec = raw.substring(6).substringBefore(",").trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val startStr = spec.substring(0, dash).trim()
            val endStr = spec.substring(dash + 1).trim()

            return if (startStr.isEmpty()) {
                val suffixLen = endStr.toLongOrNull() ?: return null
                if (suffixLen <= 0L) return null
                val start = (totalLength - suffixLen).coerceAtLeast(0L)
                HttpByteRange(start = start, end = totalLength - 1L, totalLength = totalLength)
            } else {
                val start = startStr.toLongOrNull() ?: return null
                if (start < 0L || start >= totalLength) return null
                val requestedEnd = if (endStr.isEmpty()) {
                    totalLength - 1L
                } else {
                    endStr.toLongOrNull() ?: return null
                }
                val end = requestedEnd.coerceAtMost(totalLength - 1L)
                if (end < start) return null
                HttpByteRange(start = start, end = end, totalLength = totalLength)
            }
        }

        fun buildWebConsoleHtml(isAuthenticated: Boolean): String {
            return if (!isAuthenticated) {
                """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8"/>
                  <meta name="viewport" content="width=device-width,initial-scale=1"/>
                  <title>OmniView Hub • Sign In</title>
                  <style>
                    body{margin:0;background:#0b111a;color:#eef4fa;font-family:system-ui,-apple-system,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;padding:16px}
                    .card{background:#121b28;border:1px solid rgba(0,229,255,.35);border-radius:16px;padding:28px;max-width:380px;width:100%;box-shadow:0 16px 40px rgba(0,0,0,.5)}
                    h1{margin:0 0 6px;color:#00e5ff;font-size:20px}
                    p{margin:0 0 18px;color:#93a4b8;font-size:13px;line-height:1.45}
                    input{width:100%;box-sizing:border-box;padding:12px 14px;border-radius:10px;border:1px solid #28374b;background:#0b111a;color:#fff;font-size:15px;margin-bottom:12px}
                    button{width:100%;padding:12px;border:0;border-radius:10px;background:#00e5ff;color:#041017;font-weight:800;font-size:14px;cursor:pointer}
                    .err{color:#ff6b6b;font-size:12px;margin-top:10px;min-height:16px}
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>OmniView Camera Hub</h1>
                    <p>Enter the Hub Access Token shown on the Android device to watch live cameras, control PTZ, and browse timeline clips.</p>
                    <form id="loginForm">
                      <input id="tok" type="password" placeholder="Access Token / PIN" autocomplete="current-password" required/>
                      <button type="submit">Unlock Hub</button>
                      <div class="err" id="err"></div>
                    </form>
                  </div>
                  <script>
                    document.getElementById('loginForm').addEventListener('submit', async (e) => {
                      e.preventDefault();
                      const tok = document.getElementById('tok').value.trim();
                      const err = document.getElementById('err');
                      err.textContent = '';
                      try {
                        const r = await fetch('/api/login', {
                          method: 'POST',
                          headers: {'Content-Type': 'application/json'},
                          credentials: 'same-origin',
                          body: JSON.stringify({token: tok})
                        });
                        if (r.ok) { location.reload(); }
                        else { const j = await r.json().catch(() => ({})); err.textContent = j.error || 'Access denied'; }
                      } catch (ex) { err.textContent = 'Connection error'; }
                    });
                  </script>
                </body>
                </html>
                """.trimIndent()
            } else {
                """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8"/>
                  <meta name="viewport" content="width=device-width,initial-scale=1"/>
                  <title>OmniView NVR Hub Console</title>
                  <style>
                    :root{--bg:#0b111a;--card:#121b28;--surf:#182334;--cyan:#00e5ff;--green:#00e676;--red:#ff5252;--amber:#ffb300;--txt:#eef4fa;--mut:#8fa2b7}
                    *{box-sizing:border-box}
                    body{margin:0;background:var(--bg);color:var(--txt);font-family:system-ui,-apple-system,sans-serif;padding:14px}
                    header{display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between;background:var(--card);border:1px solid #223045;border-radius:14px;padding:12px 16px;margin-bottom:14px}
                    .brand{font-weight:900;color:var(--cyan);font-size:18px}
                    .meta{font-size:12px;color:var(--mut)}
                    .row{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
                    .pill{background:var(--surf);border:1px solid #2b3b52;border-radius:8px;padding:5px 10px;font-size:12px;font-weight:700}
                    button{background:var(--surf);color:var(--txt);border:1px solid #2b3b52;border-radius:8px;padding:7px 11px;font-size:12px;font-weight:700;cursor:pointer}
                    button.primary{background:var(--cyan);color:#051017;border-color:var(--cyan)}
                    button.danger{background:#3b161b;color:var(--red);border-color:var(--red)}
                    .layout{display:grid;grid-template-columns:2fr 1fr;gap:14px}
                    @media(max-width:900px){.layout{grid-template-columns:1fr}}
                    .panel{background:var(--card);border:1px solid #223045;border-radius:14px;padding:14px}
                    h2{margin:0 0 10px;font-size:15px;color:var(--cyan)}
                    .cams{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px}
                    .cam{background:var(--surf);border-radius:12px;overflow:hidden;border:1px solid #26354a}
                    .cam img{width:100%;aspect-ratio:16/9;object-fit:cover;display:block;background:#000}
                    .cam-body{padding:10px}
                    .cam-top{display:flex;justify-content:space-between;align-items:center;gap:6px;margin-bottom:6px}
                    .badge{font-size:10px;padding:2px 6px;border-radius:5px;font-weight:800}
                    .live{background:rgba(0,230,118,.18);color:var(--green)}
                    .rec{background:rgba(255,82,82,.25);color:var(--red)}
                    .ptz-grid{display:grid;grid-template-columns:repeat(3,52px);gap:6px;justify-content:center;margin:10px 0}
                    .ptz-grid button{height:46px;font-size:15px}
                    video{width:100%;border-radius:10px;background:#000;max-height:300px}
                    .clips{max-height:300px;overflow:auto;display:flex;flex-direction:column;gap:6px;margin-top:8px}
                    .clip{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:8px 10px;background:var(--surf);border-radius:9px;cursor:pointer;border:1px solid transparent}
                    .clip.active{border-color:var(--cyan)}
                    .mut{color:var(--mut);font-size:11px}
                  </style>
                </head>
                <body>
                  <header>
                    <div>
                      <div class="brand">OmniView Remote Hub</div>
                      <div class="meta" id="hubMeta">Connecting to hub…</div>
                    </div>
                    <div class="row">
                      <span class="pill" id="powerPill">--%</span>
                      <span class="pill" id="storagePill">-- MB free</span>
                      <button onclick="refreshAll()">Refresh</button>
                      <button onclick="logout()">Sign Out</button>
                    </div>
                  </header>

                  <div class="layout">
                    <div class="panel">
                      <h2>Live Cameras</h2>
                      <div class="cams" id="camsGrid"></div>
                    </div>

                    <div style="display:flex;flex-direction:column;gap:14px">
                      <div class="panel">
                        <h2>PTZ Control</h2>
                        <div class="row" style="margin-bottom:8px">
                          <select id="ptzCam" style="flex:1;padding:7px;border-radius:8px;background:#0b111a;color:#fff;border:1px solid #2b3b52"></select>
                          <button onclick="ptzCmd('probe')">Query ONVIF</button>
                        </div>
                        <div class="ptz-grid">
                          <div></div><button onmousedown="ptzHold(0,0.6,0)" onmouseup="ptzHoldStop()" ontouchstart="ptzHold(0,0.6,0)" ontouchend="ptzHoldStop()">▲</button><div></div>
                          <button onmousedown="ptzHold(-0.6,0,0)" onmouseup="ptzHoldStop()" ontouchstart="ptzHold(-0.6,0,0)" ontouchend="ptzHoldStop()">◀</button>
                          <button class="primary" onclick="ptzCmd('home')">⌂</button>
                          <button onmousedown="ptzHold(0.6,0,0)" onmouseup="ptzHoldStop()" ontouchstart="ptzHold(0.6,0,0)" ontouchend="ptzHoldStop()">▶</button>
                          <div></div><button onmousedown="ptzHold(0,-0.6,0)" onmouseup="ptzHoldStop()" ontouchstart="ptzHold(0,-0.6,0)" ontouchend="ptzHoldStop()">▼</button><div></div>
                        </div>
                        <div class="row" style="justify-content:center">
                          <button onmousedown="ptzHold(0,0,-0.5)" onmouseup="ptzHoldStop()" onclick="ptzStep(0,0,-0.5)">Zoom -</button>
                          <button onmousedown="ptzHold(0,0,0.5)" onmouseup="ptzHoldStop()" onclick="ptzStep(0,0,0.5)">Zoom +</button>
                          <button class="danger" onclick="ptzCmd('stop')">Stop</button>
                        </div>
                        <div class="mut" id="ptzStatus" style="margin-top:8px;text-align:center">Ready</div>
                      </div>

                      <div class="panel">
                        <h2>Seamless Storage Playback</h2>
                        <video id="clipPlayer" controls playsinline></video>
                        <div class="row" style="margin-top:8px;justify-content:space-between">
                          <label class="mut"><input type="checkbox" id="autoNext" checked/> Seamless auto-advance timeline</label>
                          <select id="clipCamFilter" onchange="renderClips()" style="padding:5px;border-radius:7px;background:#0b111a;color:#fff;border:1px solid #2b3b52"></select>
                        </div>
                        <div class="clips" id="clipsList"></div>
                      </div>
                    </div>
                  </div>

                  <script>
                    let cameras = [];
                    let clips = [];
                    let activeClipIndex = -1;
                    let holdTimer = null;

                    async function api(url, opts = {}) {
                      const res = await fetch(url, { credentials: 'same-origin', ...opts });
                      if (res.status === 401) { location.reload(); throw new Error('Unauthorized'); }
                      return res.json();
                    }

                    async function refreshAll() {
                      const [st, camData, clipData] = await Promise.all([
                        api('/api/status'),
                        api('/api/cameras'),
                        api('/api/clips')
                      ]);
                      document.getElementById('hubMeta').textContent =
                        st.deviceName + ' • Port ' + st.port + ' • ' + st.cameraCount + ' cams • ' + st.activeConnections + ' active';
                      document.getElementById('powerPill').textContent =
                        st.batteryPercent + '% ' + (st.isPluggedIn ? '⚡ Plugged' : '🔋 Battery');
                      document.getElementById('storagePill').textContent = st.freeStorageMb + ' MB free';
                      cameras = camData.cameras || [];
                      clips = (clipData.clips || []).slice().sort((a,b) => a.timestamp - b.timestamp);
                      renderCameras();
                      renderSelectors();
                      renderClips();
                    }

                    function renderCameras() {
                      const grid = document.getElementById('camsGrid');
                      grid.innerHTML = cameras.map(c => {
                        const badge = c.isRecording
                          ? '<span class="badge rec">● REC</span>'
                          : (c.isLive ? '<span class="badge live">● LIVE</span>' : '<span class="badge">STANDBY</span>');
                        const recBtn = c.isRecording
                          ? '<button class="danger" onclick="toggleRec(\''+c.id+'\', true)">Stop Rec</button>'
                          : '<button class="primary" onclick="toggleRec(\''+c.id+'\', false)">Record</button>';
                        return '<div class="cam">' +
                          '<img src="/api/cameras/'+encodeURIComponent(c.id)+'/mjpeg" onerror="this.src=\'/api/cameras/'+encodeURIComponent(c.id)+'/frame.jpg?ts=\'+Date.now()" alt="'+c.name+'"/>' +
                          '<div class="cam-body">' +
                            '<div class="cam-top"><strong>'+c.name+'</strong>'+badge+'</div>' +
                            '<div class="mut">'+(c.location || '')+' • '+c.fps+' fps • '+c.bitrateKbps+' kbps '+(c.resolution || '')+'</div>' +
                            '<div class="row" style="margin-top:8px">' +
                              recBtn +
                              '<button onclick="snapCam(\''+c.id+'\')">Snapshot</button>' +
                              '<button onclick="document.getElementById(\'ptzCam\').value=\''+c.id+'\'">Select PTZ</button>' +
                            '</div>' +
                          '</div></div>';
                      }).join('');
                    }

                    function renderSelectors() {
                      const ptzSel = document.getElementById('ptzCam');
                      const prevPtz = ptzSel.value;
                      ptzSel.innerHTML = cameras.map(c => '<option value="'+c.id+'">'+c.name+'</option>').join('');
                      if (prevPtz) ptzSel.value = prevPtz;

                      const clipSel = document.getElementById('clipCamFilter');
                      const prevClip = clipSel.value;
                      clipSel.innerHTML = '<option value="">All Cameras</option>' +
                        cameras.map(c => '<option value="'+c.id+'">'+c.name+'</option>').join('');
                      clipSel.value = prevClip;
                    }

                    function renderClips() {
                      const filter = document.getElementById('clipCamFilter').value;
                      const filtered = filter ? clips.filter(c => c.cameraId === filter) : clips;
                      const box = document.getElementById('clipsList');
                      if (!filtered.length) { box.innerHTML = '<div class="mut">No clips saved yet.</div>'; return; }
                      const list = filtered.slice().reverse();
                      box.innerHTML = list.map((c, idx) => {
                        const dt = new Date(c.timestamp).toLocaleString();
                        const globalIdx = clips.findIndex(x => x.id === c.id);
                        return '<div class="clip '+(globalIdx === activeClipIndex ? 'active' : '')+'" onclick="playClipById(\''+c.id+'\')">' +
                          '<div><strong>'+c.cameraName+'</strong><div class="mut">'+dt+' • '+c.durationSeconds+'s • '+c.fileSizeMb.toFixed(1)+' MB</div></div>' +
                          '<span class="badge live">▶</span></div>';
                      }).join('');
                    }

                    function playClipById(id) {
                      activeClipIndex = clips.findIndex(c => c.id === id);
                      if (activeClipIndex < 0) return;
                      const clip = clips[activeClipIndex];
                      const v = document.getElementById('clipPlayer');
                      v.src = clip.streamUrl;
                      v.play().catch(() => {});
                      renderClips();
                    }

                    document.getElementById('clipPlayer').addEventListener('ended', () => {
                      if (!document.getElementById('autoNext').checked) return;
                      const filter = document.getElementById('clipCamFilter').value;
                      const timeline = filter ? clips.filter(c => c.cameraId === filter) : clips;
                      const cur = clips[activeClipIndex];
                      if (!cur) return;
                      const pos = timeline.findIndex(c => c.id === cur.id);
                      if (pos >= 0 && pos + 1 < timeline.length) {
                        playClipById(timeline[pos + 1].id);
                      }
                    });

                    async function ptzCmd(action, pan = 0, tilt = 0, zoom = 0) {
                      const camId = document.getElementById('ptzCam').value;
                      if (!camId) return;
                      const res = await api('/api/cameras/' + encodeURIComponent(camId) + '/ptz', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({action, pan, tilt, zoom})
                      }).catch(e => ({ok:false, message:e.message}));
                      document.getElementById('ptzStatus').textContent = res.message || action;
                    }

                    function ptzHold(pan, tilt, zoom) {
                      ptzCmd('move', pan, tilt, zoom);
                      clearTimeout(holdTimer);
                      holdTimer = setTimeout(() => ptzCmd('stop'), 2500);
                    }
                    function ptzHoldStop() { clearTimeout(holdTimer); ptzCmd('stop'); }
                    function ptzStep(pan, tilt, zoom) { ptzCmd('move', pan, tilt, zoom); setTimeout(() => ptzCmd('stop'), 400); }

                    async function toggleRec(id, isRecording) {
                      await api('/api/cameras/' + encodeURIComponent(id) + '/record/' + (isRecording ? 'stop' : 'start'), { method: 'POST' });
                      setTimeout(refreshAll, 350);
                    }
                    async function snapCam(id) {
                      await api('/api/cameras/' + encodeURIComponent(id) + '/snapshot', { method: 'POST' });
                      document.getElementById('ptzStatus').textContent = 'Snapshot triggered';
                    }
                    async function logout() {
                      await fetch('/api/logout', { method: 'POST', credentials: 'same-origin' });
                      location.reload();
                    }

                    refreshAll();
                    setInterval(refreshAll, 15000);
                  </script>
                </body>
                </html>
                """.trimIndent()
            }
        }
    }
}

/**
 * SharedPreferences persistence for Remote Hub settings (avoids destructive Room DB migration).
 */
object RemoteHubPreferences {
    private const val PREFS_NAME = "omniview_hub_prefs"
    private const val KEY_ENABLED = "hub_enabled"
    private const val KEY_PORT = "hub_port"
    private const val KEY_TOKEN = "hub_access_token"
    private const val KEY_ALLOW_CONTROL = "hub_allow_remote_control"

    data class HubConfig(
        val enabled: Boolean,
        val port: Int,
        val accessToken: String,
        val allowRemoteControl: Boolean
    )

    fun load(context: Context): HubConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString(KEY_TOKEN, null).orEmpty()
        if (token.isBlank()) {
            token = RemoteHubServer.generateSecureToken(12)
            prefs.edit().putString(KEY_TOKEN, token).apply()
        }
        return HubConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            port = RemoteHubServer.normalizePort(prefs.getInt(KEY_PORT, RemoteHubServer.DEFAULT_PORT)),
            accessToken = token,
            allowRemoteControl = prefs.getBoolean(KEY_ALLOW_CONTROL, true)
        )
    }

    fun save(context: Context, config: HubConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_PORT, RemoteHubServer.normalizePort(config.port))
            .putString(KEY_TOKEN, config.accessToken)
            .putBoolean(KEY_ALLOW_CONTROL, config.allowRemoteControl)
            .apply()
    }
}

/**
 * Bridges live Android surveillance engines (ExoPlayer frame capture, ONVIF PTZ, Recording, Battery/Storage)
 * to [RemoteHubServer].
 */
class AndroidRemoteHubBackend(
    private val context: Context,
    private val streamManager: RtspStreamManager,
    private val onvifPtzClient: OnvifPtzClient,
    private val recordingSchedulerEngine: RecordingSchedulerEngine,
    private val motionDetectionEngine: MotionDetectionEngine,
    private val camerasProvider: () -> List<CameraEntity>,
    private val clipsProvider: () -> List<RecordedClipEntity>,
    private val alertsProvider: () -> List<MotionAlertEntity>,
    private val ptzActionDispatcher: (CameraEntity, String, Float, Float, Float, String) -> Unit
) : RemoteHubBackend {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastFrameCache = ConcurrentHashMap<String, ByteArray>()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun getSystemHealth(): HubSystemHealth {
        var batteryPct = 100
        var plugged = false
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) {
                    batteryPct = (level * 100) / scale
                }
                val pluggedExtra = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                plugged = pluggedExtra != 0
            }
        } catch (_: Exception) {}

        var freeMb = 0L
        try {
            val stat = StatFs(context.filesDir.absolutePath)
            freeMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024L * 1024L)
        } catch (_: Exception) {}

        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "OmniView Android Hub"
        return HubSystemHealth(
            deviceName = model,
            batteryPercent = batteryPct.coerceIn(0, 100),
            isPluggedIn = plugged,
            freeStorageMb = freeMb
        )
    }

    override fun getCameras(): List<HubCameraSnapshot> {
        val recordingIds = recordingSchedulerEngine.currentlyRecordingCameras.value
        return camerasProvider().map { cam ->
            val t: StreamTelemetry = streamManager.getTelemetryFlow(cam.id).value
            HubCameraSnapshot(
                id = cam.id,
                name = cam.name,
                location = cam.location,
                onvifPort = cam.onvifPort,
                rotationDegrees = cam.rotationDegrees,
                isMotionDetectionEnabled = cam.isMotionDetectionEnabled,
                isLive = t.isLive,
                isReconnecting = t.isReconnecting,
                fps = t.fps,
                bitrateKbps = t.bitrateKbps,
                resolution = t.resolution,
                codec = t.codec,
                isRecording = recordingIds.contains(cam.id) || t.isRecording
            )
        }
    }

    override fun getClips(cameraId: String?): List<HubClipSnapshot> {
        return clipsProvider()
            .filter { cameraId.isNullOrBlank() || it.cameraId == cameraId }
            .map { clip ->
                HubClipSnapshot(
                    id = clip.id,
                    cameraId = clip.cameraId,
                    cameraName = clip.cameraName,
                    timestamp = clip.timestamp,
                    durationSeconds = clip.durationSeconds,
                    fileSizeMb = clip.fileSizeMb,
                    triggerType = clip.triggerType.name,
                    filePath = clip.filePath
                )
            }
    }

    override fun getClipFile(clipId: String): File? {
        val clip = clipsProvider().firstOrNull { it.id == clipId } ?: return null
        val file = File(clip.filePath)
        DeviceStorageExporter.ensureValidClipPayload(context, file)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    override fun getAlerts(limit: Int): List<HubAlertSnapshot> {
        return alertsProvider().take(limit).map { a ->
            HubAlertSnapshot(
                id = a.id,
                cameraId = a.cameraId,
                cameraName = a.cameraName,
                timestamp = a.timestamp,
                severity = a.severity.name,
                motionLevel = a.motionLevel,
                description = a.description,
                isRead = a.isRead
            )
        }
    }

    override fun captureCameraJpeg(cameraId: String): ByteArray? {
        val cam = camerasProvider().firstOrNull { it.id == cameraId } ?: return null
        var liveBmp: Bitmap? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                // Ensure player is alive if remote viewer connects before Live screen visits it
                streamManager.getOrCreatePlayer(cam)
                liveBmp = streamManager.captureFrame(cameraId, 960, 540)
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(450, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}

        liveBmp?.let { bmp ->
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 78, baos)
            bmp.recycle()
            val bytes = baos.toByteArray()
            if (bytes.isNotEmpty()) {
                lastFrameCache[cameraId] = bytes
                return bytes
            }
        }

        // Fallback if screen is turned off (spare hub standby) or no live surface attached
        lastFrameCache[cameraId]?.let { return it }
        return renderStandbyTelemetryJpeg(cam)
    }

    private fun renderStandbyTelemetryJpeg(cam: CameraEntity): ByteArray {
        val t = streamManager.getTelemetryFlow(cam.id).value
        val isRec = recordingSchedulerEngine.currentlyRecordingCameras.value.contains(cam.id)
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(11, 17, 26))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            textSize = 26f
            isFakeBoldText = true
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textSize = 15f
        }

        canvas.drawText(cam.name, 32f, 64f, titlePaint)
        canvas.drawText(
            if (isRec) "● RECORDING ACTIVE (HUB STANDBY)" else "HUB STANDBY (SCREEN OFF / NO LIVE SURFACE)",
            32f,
            110f,
            bodyPaint
        )
        canvas.drawText(
            "Stream: ${if (t.isLive) "LIVE" else "CONNECTING"} • ${t.fps} FPS • ${t.bitrateKbps} kbps • ${t.resolution}",
            32f,
            150f,
            mutedPaint
        )
        canvas.drawText("Updated: ${timeFmt.format(Date())}", 32f, 185f, mutedPaint)

        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        bmp.recycle()
        return baos.toByteArray()
    }

    override fun performPtz(
        cameraId: String,
        action: String,
        pan: Float,
        tilt: Float,
        zoom: Float,
        presetToken: String
    ): Pair<Boolean, String> {
        val cam = camerasProvider().firstOrNull { it.id == cameraId }
            ?: return false to "Camera not found"
        ptzActionDispatcher(cam, action, pan, tilt, zoom, presetToken)
        return true to "PTZ $action queued for ${cam.name}"
    }

    override fun startRecording(cameraId: String): Boolean {
        val cam = camerasProvider().firstOrNull { it.id == cameraId } ?: return false
        recordingSchedulerEngine.startManualRecording(cam)
        return true
    }

    override fun stopRecording(cameraId: String): Boolean {
        if (camerasProvider().none { it.id == cameraId }) return false
        recordingSchedulerEngine.stopRecording(cameraId)
        return true
    }

    override fun triggerSnapshot(cameraId: String): Boolean {
        val cam = camerasProvider().firstOrNull { it.id == cameraId } ?: return false
        motionDetectionEngine.triggerMotionAlert(cam, motionLevel = 80, customDescription = "Remote Hub Snapshot")
        return true
    }
}
