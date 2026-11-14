package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
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
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class EndpointKind {
    TAILSCALE,
    WIREGUARD,
    ZEROTIER,
    VPN_TUNNEL,
    WIFI_LAN,
    HOTSPOT_USB,
    EXTERNAL_TUNNEL,
    OTHER
}

enum class HubAccessScope {
    ALL_INTERFACES,
    VPN_ONLY,
    LAN_AND_VPN
}

enum class HubConnectionProfile {
    AUTO_BEST,
    TAILSCALE,
    WIREGUARD_VPN,
    LOCAL_LAN,
    HOTSPOT_TETHER,
    REVERSE_PROXY_TUNNEL
}

enum class PasswordStrength {
    WEAK,
    FAIR,
    STRONG
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
    val username: String = "admin",
    val passwordSaltHex: String = "",
    val passwordHashHex: String = "",
    val passwordIterations: Int = RemoteHubServer.DEFAULT_PBKDF2_ITERATIONS,
    val apiKeyEnabled: Boolean = false,
    val apiKeySha256Hex: String = "",
    val allowRemoteControl: Boolean = true,
    val accessScope: HubAccessScope = HubAccessScope.ALL_INTERFACES,
    val connectionProfile: HubConnectionProfile = HubConnectionProfile.AUTO_BEST,
    val externalUrl: String = "",
    val endpoints: List<NetworkEndpoint> = emptyList(),
    val activeConnections: Int = 0,
    val activeSessionCount: Int = 0,
    val lastRequestSummary: String = "",
    val lastError: String? = null,
    val batteryPercent: Int = 100,
    val isPluggedIn: Boolean = false,
    val freeStorageMb: Long = 0L
) {
    val tailscaleEndpoint: NetworkEndpoint?
        get() = endpoints.firstOrNull { it.kind == EndpointKind.TAILSCALE }

    val vpnEndpoints: List<NetworkEndpoint>
        get() = endpoints.filter {
            it.kind == EndpointKind.TAILSCALE ||
                it.kind == EndpointKind.WIREGUARD ||
                it.kind == EndpointKind.ZEROTIER ||
                it.kind == EndpointKind.VPN_TUNNEL
        }

    val primaryLanEndpoint: NetworkEndpoint?
        get() = endpoints.firstOrNull { it.kind == EndpointKind.WIFI_LAN }

    val externalEndpoint: NetworkEndpoint?
        get() = endpoints.firstOrNull { it.kind == EndpointKind.EXTERNAL_TUNNEL }

    val bestAccessUrl: String?
        get() = externalEndpoint?.url
            ?: tailscaleEndpoint?.url
            ?: vpnEndpoints.firstOrNull()?.url
            ?: primaryLanEndpoint?.url
            ?: endpoints.firstOrNull()?.url
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
 * Supports multi-transport remote links (Tailscale, WireGuard, ZeroTier, Wi-Fi LAN, Hotspot/USB,
 * Cloudflare/Caddy reverse proxy URLs), access scope filtering, PBKDF2-HMAC-SHA256 username/password auth,
 * adaptive snapshot + dedicated MJPEG theater streaming, and byte-range MP4 timeline playback.
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
        username: String,
        passwordSaltHex: String,
        passwordHashHex: String,
        passwordIterations: Int = DEFAULT_PBKDF2_ITERATIONS,
        apiKeyEnabled: Boolean = false,
        apiKeySha256Hex: String = "",
        allowRemoteControl: Boolean = true,
        accessScope: HubAccessScope = HubAccessScope.ALL_INTERFACES,
        connectionProfile: HubConnectionProfile = HubConnectionProfile.AUTO_BEST,
        externalUrl: String = ""
    ) {
        val safePort = normalizePort(port)
        val safeUser = username.trim().ifBlank { "admin" }
        val health = safeHealth()
        val endpoints = buildAllEndpoints(safePort, externalUrl)
        val prev = _state.value
        val wasRunning = prev.isRunning
        val portChanged = prev.port != safePort
        val authChanged = prev.username != safeUser ||
            prev.passwordSaltHex != passwordSaltHex ||
            prev.passwordHashHex != passwordHashHex ||
            prev.apiKeySha256Hex != apiKeySha256Hex ||
            prev.apiKeyEnabled != apiKeyEnabled

        if (portChanged || authChanged) {
            sessions.clear()
        }

        _state.value = prev.copy(
            isEnabled = enabled,
            port = safePort,
            username = safeUser,
            passwordSaltHex = passwordSaltHex,
            passwordHashHex = passwordHashHex,
            passwordIterations = passwordIterations.coerceAtLeast(1000),
            apiKeyEnabled = apiKeyEnabled,
            apiKeySha256Hex = apiKeySha256Hex,
            allowRemoteControl = allowRemoteControl,
            accessScope = accessScope,
            connectionProfile = connectionProfile,
            externalUrl = externalUrl.trim(),
            endpoints = endpoints,
            activeSessionCount = countActiveSessions(),
            batteryPercent = health.batteryPercent,
            isPluggedIn = health.isPluggedIn,
            freeStorageMb = health.freeStorageMb,
            lastError = null
        )

        if (enabled) {
            if (!wasRunning || portChanged) {
                restartServerSocket(safePort)
            }
        } else {
            stopServerSocket()
        }
    }

    fun startWithPassword(
        port: Int = _state.value.port,
        username: String = "admin",
        password: String,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
        allowRemoteControl: Boolean = _state.value.allowRemoteControl,
        accessScope: HubAccessScope = _state.value.accessScope,
        connectionProfile: HubConnectionProfile = _state.value.connectionProfile,
        externalUrl: String = _state.value.externalUrl
    ) {
        val (saltHex, hashHex) = hashPassword(password, iterations)
        configure(
            enabled = true,
            port = port,
            username = username,
            passwordSaltHex = saltHex,
            passwordHashHex = hashHex,
            passwordIterations = iterations,
            apiKeyEnabled = _state.value.apiKeyEnabled,
            apiKeySha256Hex = _state.value.apiKeySha256Hex,
            allowRemoteControl = allowRemoteControl,
            accessScope = accessScope,
            connectionProfile = connectionProfile,
            externalUrl = externalUrl
        )
    }

    fun stop() {
        _state.value = _state.value.copy(isEnabled = false)
        stopServerSocket()
    }

    fun revokeAllSessions() {
        sessions.clear()
        _state.value = _state.value.copy(activeSessionCount = 0)
    }

    fun refreshNetworkAndHealth() {
        val cur = _state.value
        val activePort = serverSocket?.localPort?.takeIf { it > 0 } ?: cur.port
        val health = safeHealth()
        _state.value = cur.copy(
            endpoints = buildAllEndpoints(activePort, cur.externalUrl),
            activeSessionCount = countActiveSessions(),
            batteryPercent = health.batteryPercent,
            isPluggedIn = health.isPluggedIn,
            freeStorageMb = health.freeStorageMb
        )
    }

    private fun countActiveSessions(): Int {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value <= now }
        return sessions.size
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
                endpoints = buildAllEndpoints(boundPort, _state.value.externalUrl),
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
            activeConnections = 0,
            activeSessionCount = 0
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
            val localIp = client.localAddress?.hostAddress ?: ""

            val request = readHttpRequest(input) ?: return
            val path = request.pathOnly
            val query = parseQueryParams(request.rawQuery)

            // Access Scope check (Tailscale / VPN-only / LAN+VPN / All)
            val currentScope = _state.value.accessScope
            if (!isClientAllowedByScope(remoteIp, localIp, currentScope, _state.value.endpoints)) {
                _state.value = _state.value.copy(
                    lastRequestSummary = "Blocked ($currentScope): $remoteIp"
                )
                sendTextResponse(
                    output = output,
                    statusCode = 403,
                    statusText = "Forbidden",
                    body = """{"error":"Blocked by Hub Access Scope (${currentScope.name})"}""",
                    contentType = "application/json; charset=utf-8"
                )
                return
            }

            if (path != "/health" && !path.endsWith("/mjpeg") && !path.endsWith("/frame.jpg")) {
                _state.value = _state.value.copy(
                    lastRequestSummary = "$remoteIp • ${request.method} $path"
                )
            }

            if (request.method.equals("OPTIONS", ignoreCase = true)) {
                sendTextResponse(output, 204, "No Content", "", "text/plain")
                return
            }

            if (path == "/health") {
                val json = """{"status":"ok","service":"omniview-hub","port":$listeningPort,"scope":${jsonStr(currentScope.name)}}"""
                sendTextResponse(output, 200, "OK", json, "application/json; charset=utf-8")
                return
            }

            if (path == "/api/login" && request.method.equals("POST", ignoreCase = true)) {
                handleLogin(remoteIp, request, output)
                return
            }

            if (path == "/api/logout" && request.method.equals("POST", ignoreCase = true)) {
                extractSessionCookie(request.headers["cookie"])?.let { sessions.remove(it) }
                _state.value = _state.value.copy(activeSessionCount = countActiveSessions())
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
                val html = buildWebConsoleHtml(
                    isAuthenticated = isAuthenticated,
                    usernameHint = _state.value.username
                )
                sendTextResponse(output, 200, "OK", html, "text/html; charset=utf-8")
                return
            }

            if (!isAuthenticated) {
                recordFailedAttempt(remoteIp)
                val err = """{"error":"Unauthorized"}"""
                sendTextResponse(
                    output = output,
                    statusCode = 401,
                    statusText = "Unauthorized",
                    body = err,
                    contentType = "application/json; charset=utf-8",
                    extraHeaders = listOf("""WWW-Authenticate: Basic realm="OmniView Hub"""")
                )
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
        val submittedUser: String
        val submittedPass: String
        if (bodyStr.startsWith("{")) {
            submittedUser = extractJsonString(bodyStr, "username").orEmpty().trim()
            submittedPass = extractJsonString(bodyStr, "password").orEmpty()
        } else {
            val form = parseQueryParams(bodyStr)
            submittedUser = form["username"].orEmpty().trim()
            submittedPass = form["password"].orEmpty()
        }

        val st = _state.value
        val valid = verifyHubCredentials(submittedUser, submittedPass, st)
        if (valid) {
            failedAttemptsByIp.remove(remoteIp)
            val sessionId = generateSessionId()
            sessions[sessionId] = now + SESSION_TTL_MS
            _state.value = _state.value.copy(activeSessionCount = countActiveSessions())
            val resp = """{"ok":true,"username":${jsonStr(st.username)}}"""
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
            val resp = """{"ok":false,"error":"Invalid username or password"}"""
            sendTextResponse(output, 401, "Unauthorized", resp, "application/json; charset=utf-8")
        }
    }

    private fun verifyHubCredentials(
        submittedUser: String,
        submittedPass: String,
        st: RemoteHubState
    ): Boolean {
        if (st.passwordSaltHex.isBlank() || st.passwordHashHex.isBlank()) return false
        if (submittedUser.isBlank() || submittedPass.isEmpty()) return false
        if (!constantTimeEquals(submittedUser, st.username)) return false
        return verifyPassword(
            password = submittedPass,
            saltHex = st.passwordSaltHex,
            hashHex = st.passwordHashHex,
            iterations = st.passwordIterations
        )
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
        val st = _state.value

        // 1. HttpOnly Session Cookie
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

        // 2. HTTP Basic Auth (Username + PBKDF2-verified Password)
        val authHeader = request.headers["authorization"].orEmpty()
        if (authHeader.startsWith("Basic ", ignoreCase = true)) {
            val b64 = authHeader.substring(6).trim()
            val decoded = runCatching {
                String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8)
            }.getOrNull()
            if (decoded != null && decoded.contains(":")) {
                val u = decoded.substringBefore(":")
                val p = decoded.substringAfter(":")
                if (verifyHubCredentials(u, p, st)) return true
            }
        }

        // 3. Optional revocable API Key (stored strictly as SHA-256 hash)
        if (st.apiKeyEnabled && st.apiKeySha256Hex.isNotBlank()) {
            val bearer = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
                authHeader.substring(7).trim()
            } else {
                ""
            }
            if (bearer.isNotEmpty() && verifyApiKey(bearer, st.apiKeySha256Hex)) return true

            val headerKey = (request.headers["x-hub-token"] ?: request.headers["x-hub-api-key"]).orEmpty().trim()
            if (headerKey.isNotEmpty() && verifyApiKey(headerKey, st.apiKeySha256Hex)) return true

            val queryKey = query["apiKey"].orEmpty().trim()
            if (queryKey.isNotEmpty() && verifyApiKey(queryKey, st.apiKeySha256Hex)) return true
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
                val endpoints = buildAllEndpoints(activePort, _state.value.externalUrl)
                _state.value = _state.value.copy(
                    endpoints = endpoints,
                    activeSessionCount = countActiveSessions(),
                    batteryPercent = health.batteryPercent,
                    isPluggedIn = health.isPluggedIn,
                    freeStorageMb = health.freeStorageMb
                )
                val cameras = backend.getCameras()
                val endpointsJson = endpoints.joinToString(",", "[", "]") { ep ->
                    """{"interface":${jsonStr(ep.interfaceName)},"ip":${jsonStr(ep.ipAddress)},"kind":${jsonStr(ep.kind.name)},"url":${jsonStr(ep.url)}}"""
                }
                val json = """{"deviceName":${jsonStr(health.deviceName)},"username":${jsonStr(_state.value.username)},"batteryPercent":${health.batteryPercent},"isPluggedIn":${health.isPluggedIn},"freeStorageMb":${health.freeStorageMb},"port":$activePort,"scope":${jsonStr(_state.value.accessScope.name)},"allowRemoteControl":${_state.value.allowRemoteControl},"activeConnections":${activeClientCount.get()},"activeSessions":${_state.value.activeSessionCount},"cameraCount":${cameras.size},"recordingCount":${cameras.count { it.isRecording }},"endpoints":$endpointsJson}"""
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
                    val fileBytes = runCatching { File(clip.filePath).length() }.getOrDefault(0L)
                    """{"id":${jsonStr(clip.id)},"cameraId":${jsonStr(clip.cameraId)},"cameraName":${jsonStr(clip.cameraName)},"timestamp":${clip.timestamp},"durationSeconds":${clip.durationSeconds},"fileSizeMb":${clip.fileSizeMb},"fileBytes":$fileBytes,"triggerType":${jsonStr(clip.triggerType)},"streamUrl":${jsonStr("/api/clips/${clip.id}/stream")}}"""
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
                val asDownload = query["download"] == "1"
                streamFileWithRangeSupport(
                    file = file,
                    rangeHeader = request.headers["range"],
                    headOnly = method == "HEAD",
                    asDownload = asDownload,
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
            Thread.sleep(160L)
        }
    }

    private fun streamFileWithRangeSupport(
        file: File,
        rangeHeader: String?,
        headOnly: Boolean,
        asDownload: Boolean,
        client: Socket,
        output: OutputStream
    ) {
        client.soTimeout = 60_000
        val totalLen = file.length()
        val dispHeader = if (asDownload) {
            "Content-Disposition: attachment; filename=\"${file.name.replace("\"", "")}\"\r\n"
        } else {
            "Content-Disposition: inline; filename=\"${file.name.replace("\"", "")}\"\r\n"
        }

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
                append(dispHeader)
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
                append(dispHeader)
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
        const val DEFAULT_PBKDF2_ITERATIONS = 100_000
        private const val SESSION_COOKIE_NAME = "omniview_hub_session"
        private const val SESSION_TTL_MS = 12L * 60L * 60L * 1000L
        private const val PASS_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#\$%"
        private const val HEX_CHARS = "0123456789abcdef"
        private val secureRandom = SecureRandom()

        fun normalizePort(port: Int): Int {
            return if (port == 0) 0 else port.coerceIn(1024, 65535)
        }

        fun generateStrongPassword(length: Int = 14): String {
            return buildString(length) {
                repeat(length) {
                    append(PASS_ALPHABET[secureRandom.nextInt(PASS_ALPHABET.length)])
                }
            }
        }

        fun generateApiKey(length: Int = 28): String {
            val alpha = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
            return "ovhub_" + buildString(length) {
                repeat(length) { append(alpha[secureRandom.nextInt(alpha.length)]) }
            }
        }

        fun evaluatePasswordStrength(password: String): PasswordStrength {
            if (password.length < 8) return PasswordStrength.WEAK
            var score = 0
            if (password.length >= 12) score += 2 else score += 1
            if (password.any { it.isLowerCase() }) score += 1
            if (password.any { it.isUpperCase() }) score += 1
            if (password.any { it.isDigit() }) score += 1
            if (password.any { !it.isLetterOrDigit() }) score += 1
            return when {
                score >= 5 -> PasswordStrength.STRONG
                score >= 3 -> PasswordStrength.FAIR
                else -> PasswordStrength.WEAK
            }
        }

        /**
         * RFC 2898 PBKDF2-HMAC-SHA256 implemented cleanly over Mac("HmacSHA256")
         * for identical behavior on Android API 24–36 and bare JVM tests.
         */
        fun pbkdf2HmacSha256(
            password: CharArray,
            salt: ByteArray,
            iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
            keyLengthBytes: Int = 32
        ): ByteArray {
            val passBytes = String(password).toByteArray(StandardCharsets.UTF_8)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passBytes, "HmacSHA256"))
            val hLen = 32
            val blocks = (keyLengthBytes + hLen - 1) / hLen
            val output = ByteArray(blocks * hLen)
            val blockInput = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, blockInput, 0, salt.size)

            for (blockIndex in 1..blocks) {
                blockInput[salt.size] = ((blockIndex ushr 24) and 0xFF).toByte()
                blockInput[salt.size + 1] = ((blockIndex ushr 16) and 0xFF).toByte()
                blockInput[salt.size + 2] = ((blockIndex ushr 8) and 0xFF).toByte()
                blockInput[salt.size + 3] = (blockIndex and 0xFF).toByte()

                var u = mac.doFinal(blockInput)
                val t = u.copyOf()
                for (i in 2..iterations) {
                    u = mac.doFinal(u)
                    for (j in t.indices) {
                        t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                    }
                }
                System.arraycopy(t, 0, output, (blockIndex - 1) * hLen, hLen)
            }
            return output.copyOf(keyLengthBytes)
        }

        fun hashPassword(
            password: String,
            iterations: Int = DEFAULT_PBKDF2_ITERATIONS
        ): Pair<String, String> {
            val salt = ByteArray(16)
            secureRandom.nextBytes(salt)
            val derived = pbkdf2HmacSha256(password.toCharArray(), salt, iterations.coerceAtLeast(1000))
            return bytesToHex(salt) to bytesToHex(derived)
        }

        fun verifyPassword(
            password: String,
            saltHex: String,
            hashHex: String,
            iterations: Int = DEFAULT_PBKDF2_ITERATIONS
        ): Boolean {
            if (saltHex.isBlank() || hashHex.isBlank()) return false
            val salt = hexToBytes(saltHex) ?: return false
            val expectedHash = hexToBytes(hashHex) ?: return false
            val computed = pbkdf2HmacSha256(password.toCharArray(), salt, iterations.coerceAtLeast(1000))
            return MessageDigest.isEqual(computed, expectedHash)
        }

        fun sha256Hex(raw: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return bytesToHex(md.digest(raw.toByteArray(StandardCharsets.UTF_8)))
        }

        fun verifyApiKey(candidate: String, expectedSha256Hex: String): Boolean {
            if (candidate.isBlank() || expectedSha256Hex.isBlank()) return false
            val candidateHash = sha256Hex(candidate.trim())
            return constantTimeEquals(candidateHash, expectedSha256Hex.lowercase(Locale.US))
        }

        private fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val i = b.toInt() and 0xFF
                sb.append(HEX_CHARS[i ushr 4])
                sb.append(HEX_CHARS[i and 0x0F])
            }
            return sb.toString()
        }

        private fun hexToBytes(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        private fun generateSessionId(): String {
            val bytes = ByteArray(24)
            secureRandom.nextBytes(bytes)
            return bytesToHex(bytes)
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

        fun isPrivateLanIpv4(ipAddress: String): Boolean {
            val parts = ipAddress.trim().split(".")
            if (parts.size != 4) return false
            val b0 = parts[0].toIntOrNull() ?: return false
            val b1 = parts[1].toIntOrNull() ?: return false
            val b2 = parts[2].toIntOrNull() ?: return false
            val b3 = parts[3].toIntOrNull() ?: return false
            if (b2 !in 0..255 || b3 !in 0..255) return false
            return b0 == 10 ||
                (b0 == 192 && b1 == 168) ||
                (b0 == 172 && b1 in 16..31) ||
                (b0 == 169 && b1 == 254)
        }

        fun isLoopbackAddress(ip: String): Boolean {
            val clean = ip.trim().substringBefore("%")
            return clean == "127.0.0.1" ||
                clean == "::1" ||
                clean == "0:0:0:0:0:0:0:1" ||
                clean.startsWith("127.")
        }

        fun isClientAllowedByScope(
            remoteIp: String,
            localIp: String,
            scope: HubAccessScope,
            endpoints: List<NetworkEndpoint>
        ): Boolean {
            if (scope == HubAccessScope.ALL_INTERFACES) return true
            if (isLoopbackAddress(remoteIp)) return true

            val localEpKind = endpoints.firstOrNull { it.ipAddress == localIp }?.kind
            val isRemoteVpn = isTailscaleIpv4(remoteIp) ||
                localEpKind == EndpointKind.TAILSCALE ||
                localEpKind == EndpointKind.WIREGUARD ||
                localEpKind == EndpointKind.ZEROTIER ||
                localEpKind == EndpointKind.VPN_TUNNEL

            return when (scope) {
                HubAccessScope.ALL_INTERFACES -> true
                HubAccessScope.VPN_ONLY -> isRemoteVpn
                HubAccessScope.LAN_AND_VPN -> isRemoteVpn ||
                    isPrivateLanIpv4(remoteIp) ||
                    localEpKind == EndpointKind.WIFI_LAN ||
                    localEpKind == EndpointKind.HOTSPOT_USB
            }
        }

        fun classifyNetworkInterface(interfaceName: String, ipAddress: String): EndpointKind {
            val iface = interfaceName.lowercase(Locale.US)
            return when {
                isTailscaleIpv4(ipAddress) || iface.startsWith("tailscale") -> EndpointKind.TAILSCALE
                iface.startsWith("wg") -> EndpointKind.WIREGUARD
                iface.startsWith("zt") -> EndpointKind.ZEROTIER
                iface.startsWith("tun") || iface.startsWith("tap") || iface.startsWith("utun") ||
                    iface.startsWith("ppp") || iface.startsWith("ipsec") -> EndpointKind.VPN_TUNNEL
                iface.startsWith("ap") || iface.startsWith("swlan") || iface.startsWith("rndis") ||
                    iface.startsWith("usb") -> EndpointKind.HOTSPOT_USB
                iface.startsWith("rmnet") || iface.startsWith("ccmni") || iface.startsWith("wwan") ||
                    iface.startsWith("pdp") -> EndpointKind.OTHER
                iface.startsWith("wlan") || iface.startsWith("eth") || iface.startsWith("en") ||
                    isPrivateLanIpv4(ipAddress) -> EndpointKind.WIFI_LAN
                else -> EndpointKind.OTHER
            }
        }

        fun buildAllEndpoints(port: Int, externalUrl: String): List<NetworkEndpoint> {
            val list = mutableListOf<NetworkEndpoint>()
            val cleanExt = externalUrl.trim().trimEnd('/')
            if (cleanExt.startsWith("http://") || cleanExt.startsWith("https://")) {
                list.add(
                    NetworkEndpoint(
                        interfaceName = "tunnel",
                        ipAddress = cleanExt.substringAfter("://").substringBefore("/").substringBefore(":"),
                        kind = EndpointKind.EXTERNAL_TUNNEL,
                        url = cleanExt
                    )
                )
            }
            list.addAll(discoverNetworkEndpoints(port))
            return list
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

        private fun escapeHtml(text: String): String {
            return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
        }

        fun buildWebConsoleHtml(isAuthenticated: Boolean, usernameHint: String = "admin"): String {
            return if (!isAuthenticated) {
                val safeHint = escapeHtml(usernameHint.ifBlank { "admin" })
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
                    label{display:block;font-size:11px;font-weight:700;color:#93a4b8;margin-bottom:5px;text-transform:uppercase;letter-spacing:.04em}
                    .pw-wrap{position:relative}
                    input{width:100%;box-sizing:border-box;padding:12px 14px;border-radius:10px;border:1px solid #28374b;background:#0b111a;color:#fff;font-size:15px;margin-bottom:14px}
                    .eye{position:absolute;right:10px;top:10px;background:transparent;color:#93a4b8;border:none;font-size:12px;cursor:pointer;padding:4px 8px;width:auto}
                    button.submit{width:100%;padding:12px;border:none;border-radius:10px;background:#00e5ff;color:#051017;font-weight:800;font-size:15px;cursor:pointer}
                    .err{color:#ff5252;font-size:13px;margin-top:12px;min-height:18px}
                  </style>
                </head>
                <body>
                  <form class="card" onsubmit="return doLogin(event)">
                    <h1>OmniView Camera Hub</h1>
                    <p>Sign in with your hub username and password to access live camera streams, PTZ controls, and timeline recordings.</p>
                    <label for="usr">Username</label>
                    <input id="usr" type="text" value="$safeHint" autocomplete="username" required/>
                    <label for="pwd">Password</label>
                    <div class="pw-wrap">
                      <input id="pwd" type="password" placeholder="Enter hub password" autocomplete="current-password" required autofocus/>
                      <button type="button" class="eye" onclick="toggleEye()">Show</button>
                    </div>
                    <button type="submit" class="submit">Unlock Hub</button>
                    <div id="err" class="err"></div>
                  </form>
                  <script>
                    function toggleEye(){
                      const p=document.getElementById('pwd');
                      p.type=p.type==='password'?'text':'password';
                    }
                    async function doLogin(e){
                      e.preventDefault();
                      const err=document.getElementById('err');
                      err.textContent='';
                      try{
                        const r=await fetch('/api/login',{
                          method:'POST',
                          credentials:'same-origin',
                          headers:{'Content-Type':'application/json'},
                          body:JSON.stringify({
                            username:document.getElementById('usr').value.trim(),
                            password:document.getElementById('pwd').value
                          })
                        });
                        const data=await r.json().catch(()=>({}));
                        if(r.ok && data.ok){ location.reload(); }
                        else{ err.textContent=data.error||'Invalid username or password'; }
                      }catch(ex){ err.textContent='Network error'; }
                      return false;
                    }
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
                  <title>OmniView Hub Dashboard</title>
                  <style>
                    :root{--bg:#0b111a;--card:#121b28;--surf:#182334;--cyan:#00e5ff;--green:#00e676;--amber:#ffab00;--red:#ff5252;--txt:#eef4fa;--mut:#8fa2b7}
                    *{box-sizing:border-box}
                    body{margin:0;background:var(--bg);color:var(--txt);font-family:system-ui,-apple-system,sans-serif;padding:14px}
                    header{display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between;background:var(--card);border:1px solid #223045;border-radius:14px;padding:12px 16px;margin-bottom:14px}
                    .brand{font-weight:900;color:var(--cyan);font-size:18px}
                    .meta{font-size:12px;color:var(--mut)}
                    .row{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
                    .pill{background:var(--surf);border:1px solid #2b3b52;border-radius:8px;padding:5px 10px;font-size:12px;font-weight:700}
                    button,a.btn{background:var(--surf);color:var(--txt);border:1px solid #2b3b52;border-radius:8px;padding:7px 11px;font-size:12px;font-weight:700;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;gap:4px}
                    button.primary{background:var(--cyan);color:#051017;border-color:var(--cyan)}
                    button.danger{background:#3b161b;color:var(--red);border-color:var(--red)}
                    .layout{display:grid;grid-template-columns:1.8fr 1.1fr;gap:14px}
                    @media(max-width:960px){.layout{grid-template-columns:1fr}}
                    .panel{background:var(--card);border:1px solid #223045;border-radius:14px;padding:14px}
                    h2{margin:0 0 10px;font-size:15px;color:var(--cyan)}
                    .cams{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px}
                    .cam{background:var(--surf);border-radius:12px;overflow:hidden;border:1px solid #26354a}
                    .cam img{width:100%;aspect-ratio:16/9;object-fit:cover;display:block;background:#05080f;cursor:pointer}
                    .cam-body{padding:10px}
                    .cam-top{display:flex;justify-content:space-between;align-items:center;gap:6px;margin-bottom:6px}
                    .badge{font-size:10px;padding:2px 6px;border-radius:5px;font-weight:800;background:rgba(255,255,255,.08)}
                    .live{background:rgba(0,230,118,.18);color:var(--green)}
                    .rec{background:rgba(255,82,82,.25);color:var(--red)}
                    .ptz-grid{display:grid;grid-template-columns:repeat(3,52px);gap:6px;justify-content:center;margin:10px 0}
                    .ptz-grid button{height:46px;font-size:15px;justify-content:center}
                    video{width:100%;border-radius:10px;background:#000;max-height:320px;display:block}
                    .clips{max-height:280px;overflow:auto;display:flex;flex-direction:column;gap:6px;margin-top:8px}
                    .clip{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:8px 10px;background:var(--surf);border-radius:9px;cursor:pointer;border:1px solid transparent}
                    .clip.active{border-color:var(--cyan);background:#1b2c42}
                    .mut{color:var(--mut);font-size:11px}
                    .diag{font-size:11px;padding:6px 10px;border-radius:8px;margin-top:6px;background:var(--surf);color:var(--cyan)}
                    .diag.warn{background:#34250b;color:var(--amber)}
                    .modal{position:fixed;inset:0;background:rgba(4,7,13,.88);display:none;align-items:center;justify-content:center;padding:16px;z-index:50}
                    .modal.open{display:flex}
                    .modal-box{background:var(--card);border:1px solid rgba(0,229,255,.45);border-radius:16px;padding:16px;max-width:860px;width:100%;max-height:94vh;overflow:auto}
                    .modal-box img{width:100%;max-height:62vh;object-fit:contain;background:#000;border-radius:10px;display:block}
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
                      <button id="gridModeBtn" onclick="toggleGridStreamMode()">Grid: Adaptive</button>
                      <button onclick="refreshAll()">Refresh</button>
                      <button onclick="logout()">Sign Out</button>
                    </div>
                  </header>

                  <div class="layout">
                    <div class="panel">
                      <div class="cam-top">
                        <h2 style="margin:0">Live Cameras</h2>
                        <span class="mut">Tap any camera image for full MJPEG theater</span>
                      </div>
                      <div class="cams" id="camsGrid" style="margin-top:10px"></div>
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
                        <video id="clipPlayer" controls playsinline preload="metadata"></video>
                        <div class="row" style="margin-top:8px;justify-content:space-between">
                          <div class="row">
                            <button onclick="stepClip(-1)">⏮ Prev</button>
                            <button onclick="stepClip(1)">Next ⏭</button>
                            <button id="speedBtn" onclick="cycleSpeed()">1x</button>
                            <a id="downloadClipLink" class="btn" href="#" download>⬇ MP4</a>
                          </div>
                          <select id="clipCamFilter" onchange="renderClips()" style="padding:5px;border-radius:7px;background:#0b111a;color:#fff;border:1px solid #2b3b52"></select>
                        </div>
                        <label class="mut" style="display:block;margin-top:6px"><input type="checkbox" id="autoNext" checked/> Seamless auto-advance timeline</label>
                        <div id="clipDiag" class="diag">Select or press play on a saved clip.</div>
                        <div class="clips" id="clipsList"></div>
                      </div>
                    </div>
                  </div>

                  <div id="theaterModal" class="modal" onclick="if(event.target===this)closeTheater()">
                    <div class="modal-box">
                      <div class="cam-top" style="margin-bottom:10px">
                        <strong id="theaterTitle" style="color:var(--cyan);font-size:16px">Camera Live</strong>
                        <div class="row">
                          <button onclick="toggleTheaterStreamMode()" id="theaterModeBtn">Mode: MJPEG</button>
                          <button class="danger" onclick="closeTheater()">Close ✕</button>
                        </div>
                      </div>
                      <img id="theaterImg" alt="Live camera theater"/>
                    </div>
                  </div>

                  <script>
                    let cameras = [];
                    let clips = [];
                    let activeClipId = null;
                    let holdTimer = null;
                    let gridUseMjpeg = false;
                    let playbackSpeed = 1;
                    let theaterCamId = null;
                    let theaterUseMjpeg = true;
                    let camLayoutSignature = '';
                    const snapshotTimers = {};

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
                        st.deviceName + ' • ' + (st.username || 'admin') + ' • Port ' + st.port + ' • ' + st.cameraCount + ' cams • ' + st.activeConnections + ' active';
                      document.getElementById('powerPill').textContent =
                        st.batteryPercent + '% ' + (st.isPluggedIn ? '⚡ Plugged' : '🔋 Battery');
                      document.getElementById('storagePill').textContent = st.freeStorageMb + ' MB free';
                      cameras = camData.cameras || [];
                      clips = (clipData.clips || []).slice().sort((a,b) => a.timestamp - b.timestamp);
                      renderCameras();
                      renderSelectors();
                      renderClips();
                      if (!activeClipId && clips.length > 0) {
                        selectClipById(clips[clips.length - 1].id, false);
                      }
                    }

                    function toggleGridStreamMode() {
                      gridUseMjpeg = !gridUseMjpeg;
                      document.getElementById('gridModeBtn').textContent = gridUseMjpeg ? 'Grid: MJPEG' : 'Grid: Adaptive';
                      camLayoutSignature = '';
                      renderCameras();
                    }

                    function scheduleSnapshotLoop(camId) {
                      clearTimeout(snapshotTimers[camId]);
                      if (gridUseMjpeg || theaterCamId === camId) return;
                      const img = document.getElementById('cam-img-' + camId);
                      if (!img) return;
                      img.src = '/api/cameras/' + encodeURIComponent(camId) + '/frame.jpg?ts=' + Date.now();
                    }

                    function onGridImgDone(camId) {
                      clearTimeout(snapshotTimers[camId]);
                      if (!gridUseMjpeg) {
                        snapshotTimers[camId] = setTimeout(() => scheduleSnapshotLoop(camId), 550);
                      }
                    }

                    function renderCameras() {
                      const grid = document.getElementById('camsGrid');
                      const sig = cameras.map(c => c.id).join(',') + '|' + gridUseMjpeg;
                      if (sig !== camLayoutSignature) {
                        camLayoutSignature = sig;
                        Object.keys(snapshotTimers).forEach(k => clearTimeout(snapshotTimers[k]));
                        grid.innerHTML = cameras.map(c => {
                          const cid = encodeURIComponent(c.id);
                          const srcAttr = gridUseMjpeg
                            ? 'src="/api/cameras/' + cid + '/mjpeg"'
                            : 'src="/api/cameras/' + cid + '/frame.jpg?ts=' + Date.now() + '" onload="onGridImgDone(\'' + c.id + '\')" onerror="onGridImgDone(\'' + c.id + '\')"';
                          return '<div class="cam" id="cam-card-' + c.id + '">' +
                            '<img id="cam-img-' + c.id + '" ' + srcAttr + ' onclick="openTheater(\'' + c.id + '\')" alt="' + c.name + '"/>' +
                            '<div class="cam-body">' +
                              '<div class="cam-top"><strong>' + c.name + '</strong><span id="cam-badge-' + c.id + '" class="badge"></span></div>' +
                              '<div class="mut" id="cam-meta-' + c.id + '"></div>' +
                              '<div class="row" style="margin-top:8px">' +
                                '<button id="cam-recbtn-' + c.id + '" onclick="toggleRecById(\'' + c.id + '\')"></button>' +
                                '<button onclick="openTheater(\'' + c.id + '\')">Live Stream</button>' +
                                '<button onclick="snapCam(\'' + c.id + '\')">Snapshot</button>' +
                                '<button onclick="document.getElementById(\'ptzCam\').value=\'' + c.id + '\'">PTZ</button>' +
                              '</div>' +
                            '</div></div>';
                        }).join('');
                      }

                      // Update status badges & metrics in-place so active video frames stay smooth
                      cameras.forEach(c => {
                        const badge = document.getElementById('cam-badge-' + c.id);
                        const meta = document.getElementById('cam-meta-' + c.id);
                        const recBtn = document.getElementById('cam-recbtn-' + c.id);
                        if (badge) {
                          badge.className = c.isRecording ? 'badge rec' : (c.isLive ? 'badge live' : 'badge');
                          badge.textContent = c.isRecording ? '● REC' : (c.isLive ? '● LIVE' : 'STANDBY');
                        }
                        if (meta) {
                          meta.textContent = (c.location || '') + ' • ' + c.fps + ' fps • ' + c.bitrateKbps + ' kbps ' + (c.resolution || '');
                        }
                        if (recBtn) {
                          recBtn.className = c.isRecording ? 'danger' : 'primary';
                          recBtn.textContent = c.isRecording ? 'Stop Rec' : 'Record';
                        }
                      });
                    }

                    function openTheater(camId) {
                      const cam = cameras.find(c => c.id === camId);
                      theaterCamId = camId;
                      theaterUseMjpeg = true;
                      document.getElementById('theaterTitle').textContent = (cam ? cam.name : camId) + ' • Live Stream';
                      document.getElementById('theaterModeBtn').textContent = 'Mode: MJPEG';
                      document.getElementById('ptzCam').value = camId;
                      document.getElementById('theaterModal').classList.add('open');
                      updateTheaterSource();
                    }

                    function updateTheaterSource() {
                      if (!theaterCamId) return;
                      const img = document.getElementById('theaterImg');
                      const cid = encodeURIComponent(theaterCamId);
                      clearTimeout(snapshotTimers['__theater']);
                      if (theaterUseMjpeg) {
                        img.onload = null;
                        img.onerror = () => {
                          theaterUseMjpeg = false;
                          document.getElementById('theaterModeBtn').textContent = 'Mode: Snapshot';
                          updateTheaterSource();
                        };
                        img.src = '/api/cameras/' + cid + '/mjpeg?ts=' + Date.now();
                      } else {
                        img.onload = () => {
                          if (theaterCamId && !theaterUseMjpeg) {
                            snapshotTimers['__theater'] = setTimeout(updateTheaterSource, 280);
                          }
                        };
                        img.onerror = () => {
                          if (theaterCamId && !theaterUseMjpeg) {
                            snapshotTimers['__theater'] = setTimeout(updateTheaterSource, 900);
                          }
                        };
                        img.src = '/api/cameras/' + cid + '/frame.jpg?ts=' + Date.now();
                      }
                    }

                    function toggleTheaterStreamMode() {
                      theaterUseMjpeg = !theaterUseMjpeg;
                      document.getElementById('theaterModeBtn').textContent = theaterUseMjpeg ? 'Mode: MJPEG' : 'Mode: Snapshot';
                      updateTheaterSource();
                    }

                    function closeTheater() {
                      const prevId = theaterCamId;
                      theaterCamId = null;
                      clearTimeout(snapshotTimers['__theater']);
                      document.getElementById('theaterImg').src = '';
                      document.getElementById('theaterModal').classList.remove('open');
                      if (prevId && !gridUseMjpeg) scheduleSnapshotLoop(prevId);
                    }

                    function renderSelectors() {
                      const ptzSel = document.getElementById('ptzCam');
                      const prevPtz = ptzSel.value;
                      ptzSel.innerHTML = cameras.map(c => '<option value="' + c.id + '">' + c.name + '</option>').join('');
                      if (prevPtz) ptzSel.value = prevPtz;

                      const clipSel = document.getElementById('clipCamFilter');
                      const prevClip = clipSel.value;
                      clipSel.innerHTML = '<option value="">All Cameras</option>' +
                        cameras.map(c => '<option value="' + c.id + '">' + c.name + '</option>').join('');
                      clipSel.value = prevClip;
                    }

                    function renderClips() {
                      const filter = document.getElementById('clipCamFilter').value;
                      const filtered = filter ? clips.filter(c => c.cameraId === filter) : clips;
                      const box = document.getElementById('clipsList');
                      if (!filtered.length) { box.innerHTML = '<div class="mut">No clips saved yet.</div>'; return; }
                      const list = filtered.slice().reverse();
                      box.innerHTML = list.map(c => {
                        const dt = new Date(c.timestamp).toLocaleString();
                        const sz = c.fileBytes ? (c.fileBytes < 1024 ? (c.fileBytes + ' B') : (c.fileSizeMb.toFixed(2) + ' MB')) : (c.fileSizeMb.toFixed(1) + ' MB');
                        return '<div class="clip ' + (c.id === activeClipId ? 'active' : '') + '" onclick="selectClipById(\'' + c.id + '\', true)">' +
                          '<div><strong>' + c.cameraName + '</strong><div class="mut">' + dt + ' • ' + c.durationSeconds + 's • ' + sz + '</div></div>' +
                          '<span class="badge live">▶</span></div>';
                      }).join('');
                    }

                    function selectClipById(id, autoPlay) {
                      const clip = clips.find(c => c.id === id);
                      if (!clip) return;
                      activeClipId = clip.id;
                      const v = document.getElementById('clipPlayer');
                      const dl = document.getElementById('downloadClipLink');
                      const diag = document.getElementById('clipDiag');
                      dl.href = clip.streamUrl + '?download=1';
                      diag.className = 'diag';
                      if (clip.fileBytes && clip.fileBytes <= 64) {
                        diag.className = 'diag warn';
                        diag.textContent = 'Clip has only ' + clip.fileBytes + ' bytes (header-only stub). Record a fresh clip to stream video frames.';
                      } else {
                        diag.textContent = 'Loaded: ' + clip.cameraName + ' • ' + new Date(clip.timestamp).toLocaleTimeString();
                      }
                      v.src = clip.streamUrl;
                      v.playbackRate = playbackSpeed;
                      if (autoPlay) {
                        v.play().catch(err => {
                          diag.className = 'diag warn';
                          diag.textContent = 'Tap video play or download MP4 (' + (err.message || 'autoplay blocked') + ')';
                        });
                      }
                      renderClips();
                    }

                    function stepClip(delta) {
                      const filter = document.getElementById('clipCamFilter').value;
                      const timeline = filter ? clips.filter(c => c.cameraId === filter) : clips;
                      if (!timeline.length) return;
                      const idx = timeline.findIndex(c => c.id === activeClipId);
                      const nextIdx = idx < 0 ? 0 : Math.max(0, Math.min(timeline.length - 1, idx + delta));
                      selectClipById(timeline[nextIdx].id, true);
                    }

                    function cycleSpeed() {
                      const speeds = [0.5, 1, 2, 4];
                      const next = speeds[(speeds.indexOf(playbackSpeed) + 1) % speeds.length];
                      playbackSpeed = next;
                      document.getElementById('speedBtn').textContent = next + 'x';
                      document.getElementById('clipPlayer').playbackRate = next;
                    }

                    const clipPlayerEl = document.getElementById('clipPlayer');
                    clipPlayerEl.addEventListener('error', () => {
                      const diag = document.getElementById('clipDiag');
                      diag.className = 'diag warn';
                      const code = clipPlayerEl.error ? clipPlayerEl.error.code : 0;
                      diag.textContent = 'Browser could not decode this clip (code ' + code + '). Use ⬇ MP4 download or record a new clip.';
                    });
                    clipPlayerEl.addEventListener('ended', () => {
                      if (!document.getElementById('autoNext').checked) return;
                      stepClip(1);
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

                    async function toggleRecById(id) {
                      const cam = cameras.find(c => c.id === id);
                      const isRecording = !!(cam && cam.isRecording);
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
 * SharedPreferences persistence for Remote Hub settings.
 * Stores ONLY PBKDF2-HMAC-SHA256 salt+hash and SHA-256 hashed API key — never plaintext passwords.
 */
object RemoteHubPreferences {
    private const val PREFS_NAME = "omniview_hub_prefs"
    private const val KEY_ENABLED = "hub_enabled"
    private const val KEY_PORT = "hub_port"
    private const val KEY_USERNAME = "hub_username"
    private const val KEY_SALT = "hub_password_salt_hex"
    private const val KEY_HASH = "hub_password_hash_hex"
    private const val KEY_ITERS = "hub_password_iters"
    private const val KEY_API_KEY_ENABLED = "hub_api_key_enabled"
    private const val KEY_API_KEY_HASH = "hub_api_key_sha256"
    private const val KEY_ALLOW_CONTROL = "hub_allow_remote_control"
    private const val KEY_SCOPE = "hub_access_scope"
    private const val KEY_PROFILE = "hub_connection_profile"
    private const val KEY_EXTERNAL_URL = "hub_external_url"
    private const val KEY_LEGACY_TOKEN = "hub_access_token"

    data class HubConfig(
        val enabled: Boolean,
        val port: Int,
        val username: String,
        val passwordSaltHex: String,
        val passwordHashHex: String,
        val passwordIterations: Int,
        val apiKeyEnabled: Boolean,
        val apiKeySha256Hex: String,
        val allowRemoteControl: Boolean,
        val accessScope: HubAccessScope,
        val connectionProfile: HubConnectionProfile,
        val externalUrl: String,
        val firstBootGeneratedPassword: String? = null
    )

    fun load(context: Context): HubConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val username = prefs.getString(KEY_USERNAME, null)?.trim()?.ifBlank { "admin" } ?: "admin"
        var saltHex = prefs.getString(KEY_SALT, null).orEmpty()
        var hashHex = prefs.getString(KEY_HASH, null).orEmpty()
        var iters = prefs.getInt(KEY_ITERS, RemoteHubServer.DEFAULT_PBKDF2_ITERATIONS)
            .coerceAtLeast(10_000)
        var generatedOnFirstBoot: String? = null

        if (saltHex.isBlank() || hashHex.isBlank()) {
            // Migrate legacy token if present, otherwise generate a fresh strong setup password once
            val legacy = prefs.getString(KEY_LEGACY_TOKEN, null).orEmpty().trim()
            val initialPass = legacy.ifBlank { RemoteHubServer.generateStrongPassword(12) }
            val (s, h) = RemoteHubServer.hashPassword(initialPass, RemoteHubServer.DEFAULT_PBKDF2_ITERATIONS)
            saltHex = s
            hashHex = h
            iters = RemoteHubServer.DEFAULT_PBKDF2_ITERATIONS
            generatedOnFirstBoot = initialPass
            prefs.edit()
                .putString(KEY_USERNAME, username)
                .putString(KEY_SALT, saltHex)
                .putString(KEY_HASH, hashHex)
                .putInt(KEY_ITERS, iters)
                .remove(KEY_LEGACY_TOKEN)
                .apply()
        }

        val scope = runCatching {
            HubAccessScope.valueOf(prefs.getString(KEY_SCOPE, HubAccessScope.ALL_INTERFACES.name).orEmpty())
        }.getOrDefault(HubAccessScope.ALL_INTERFACES)

        val profile = runCatching {
            HubConnectionProfile.valueOf(
                prefs.getString(KEY_PROFILE, HubConnectionProfile.AUTO_BEST.name).orEmpty()
            )
        }.getOrDefault(HubConnectionProfile.AUTO_BEST)

        return HubConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            port = RemoteHubServer.normalizePort(prefs.getInt(KEY_PORT, RemoteHubServer.DEFAULT_PORT)),
            username = username,
            passwordSaltHex = saltHex,
            passwordHashHex = hashHex,
            passwordIterations = iters,
            apiKeyEnabled = prefs.getBoolean(KEY_API_KEY_ENABLED, false),
            apiKeySha256Hex = prefs.getString(KEY_API_KEY_HASH, "").orEmpty(),
            allowRemoteControl = prefs.getBoolean(KEY_ALLOW_CONTROL, true),
            accessScope = scope,
            connectionProfile = profile,
            externalUrl = prefs.getString(KEY_EXTERNAL_URL, "").orEmpty(),
            firstBootGeneratedPassword = generatedOnFirstBoot
        )
    }

    fun save(context: Context, config: HubConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_PORT, RemoteHubServer.normalizePort(config.port))
            .putString(KEY_USERNAME, config.username.trim().ifBlank { "admin" })
            .putString(KEY_SALT, config.passwordSaltHex)
            .putString(KEY_HASH, config.passwordHashHex)
            .putInt(KEY_ITERS, config.passwordIterations.coerceAtLeast(10_000))
            .putBoolean(KEY_API_KEY_ENABLED, config.apiKeyEnabled)
            .putString(KEY_API_KEY_HASH, config.apiKeySha256Hex)
            .putBoolean(KEY_ALLOW_CONTROL, config.allowRemoteControl)
            .putString(KEY_SCOPE, config.accessScope.name)
            .putString(KEY_PROFILE, config.connectionProfile.name)
            .putString(KEY_EXTERNAL_URL, config.externalUrl.trim())
            .remove(KEY_LEGACY_TOKEN)
            .apply()
    }
}

/**
 * Bridges live Android surveillance engines (ExoPlayer frame capture, headless ImageReader / MP4 retriever fallback,
 * ONVIF PTZ, Recording, Battery/Storage telemetry) to [RemoteHubServer].
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
                streamManager.getOrCreatePlayer(cam)
                liveBmp = streamManager.captureFrame(cameraId, 960, 540)
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(500, TimeUnit.MILLISECONDS)
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

        // Secondary fallback: extract frame from newest recorded MP4 clip for this camera
        extractLatestClipFrameJpeg(cameraId)?.let { clipJpeg ->
            lastFrameCache[cameraId] = clipJpeg
            return clipJpeg
        }

        lastFrameCache[cameraId]?.let { return it }
        return renderStandbyTelemetryJpeg(cam)
    }

    private fun extractLatestClipFrameJpeg(cameraId: String): ByteArray? {
        val latest = clipsProvider()
            .filter { it.cameraId == cameraId }
            .maxByOrNull { it.timestamp } ?: return null
        val file = File(latest.filePath)
        if (!file.exists() || file.length() < 1024L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val baos = ByteArrayOutputStream()
            frame.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            frame.recycle()
            baos.toByteArray().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
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
            if (isRec) "● RECORDING ACTIVE (HEADLESS HUB)" else "HUB STANDBY (OPEN LIVE STREAM OR PRESS RECORD)",
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
