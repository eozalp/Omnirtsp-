package com.example.engine

import android.content.Context
import android.net.wifi.WifiManager
import com.example.R
import com.example.data.model.CameraEntity
import com.example.i18n.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BrandHint(
    val brand: String,
    val defaultPath: String,
    val defaultOnvifPort: Int
)

data class DiscoveredCamera(
    val id: String,
    val ipAddress: String,
    val rtspPort: Int = 554,
    val onvifPort: Int = 80,
    val name: String,
    val manufacturer: String,
    val suggestedRtspUrl: String,
    val discoveryMethod: String,
    val onvifDeviceServiceUrl: String = "",
    val exactStreamUriFound: Boolean = false,
    val alreadyAdded: Boolean = false
)

data class DiscoveryScanState(
    val isScanning: Boolean = false,
    val progressText: String = "",
    val subnetLabel: String = "",
    val results: List<DiscoveredCamera> = emptyList(),
    val errorMessage: String? = null,
    val hasScannedAtLeastOnce: Boolean = false
)

/**
 * Discovers real IP/ONVIF/RTSP cameras on the device's local Wi-Fi / LAN network:
 * 1. Sends ONVIF WS-Discovery UDP multicast probes (239.255.255.250:3702).
 * 2. Scans the active local IPv4 /24 subnet for RTSP (554, 8554) and ONVIF HTTP ports.
 * 3. Reads real RTSP OPTIONS banners and queries ONVIF GetStreamUri when available.
 */
class CameraDiscoveryEngine(
    private val context: Context,
    private val onvifPtzClient: OnvifPtzClient
) {
    private val _state = MutableStateFlow(DiscoveryScanState())
    val state: StateFlow<DiscoveryScanState> = _state.asStateFlow()

    suspend fun scanLocalNetwork(existingCameras: List<CameraEntity>): List<DiscoveredCamera> =
        withContext(Dispatchers.IO) {
            if (_state.value.isScanning) {
                return@withContext _state.value.results
            }

            val localIpv4s = getLocalIpv4Addresses()
            if (localIpv4s.isEmpty()) {
                _state.value = DiscoveryScanState(
                    isScanning = false,
                    progressText = AppLocaleManager.getString(context, R.string.discovery_no_network_progress),
                    subnetLabel = AppLocaleManager.getString(context, R.string.discovery_offline_label),
                    results = emptyList(),
                    errorMessage = AppLocaleManager.getString(context, R.string.discovery_no_network_error),
                    hasScannedAtLeastOnce = true
                )
                return@withContext emptyList()
            }

            val subnetSummary = localIpv4s.joinToString(", ") { ip ->
                val parts = ip.split(".")
                if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.*" else ip
            }

            _state.value = _state.value.copy(
                isScanning = true,
                progressText = AppLocaleManager.getString(context, R.string.discovery_scanning_progress, subnetSummary),
                subnetLabel = subnetSummary,
                errorMessage = null
            )

            val discoveredByIp = ConcurrentHashMap<String, DiscoveredCamera>()
            var multicastLock: WifiManager.MulticastLock? = null

            try {
                try {
                    val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    multicastLock = wifiMgr?.createMulticastLock("omniview_onvif_discovery")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                } catch (_: Exception) {
                    // MulticastLock optional on Ethernet / restricted ROMs
                }

                // Step 1: Parallel ONVIF WS-Discovery (UDP 3702) + RTSP Subnet Port Probe (554 / 8554)
                coroutineScope {
                    val wsJob = async {
                        runWsDiscovery(localIpv4s).forEach { cam ->
                            discoveredByIp.merge(cam.ipAddress, cam) { existing, incoming ->
                                mergeDiscovered(existing, incoming)
                            }
                        }
                    }

                    val rtspJob = async {
                        scanSubnetsForRtspAndOnvif(localIpv4s).forEach { cam ->
                            discoveredByIp.merge(cam.ipAddress, cam) { existing, incoming ->
                                mergeDiscovered(existing, incoming)
                            }
                        }
                    }

                    wsJob.await()
                    rtspJob.await()
                }

                // Step 2: For any ONVIF camera discovered, try unauthenticated GetStreamUri to obtain exact RTSP path
                val resolvedList = coroutineScope {
                    discoveredByIp.values.map { cam ->
                        async {
                            if (cam.onvifDeviceServiceUrl.isNotBlank() && !cam.exactStreamUriFound) {
                                val fetched = onvifPtzClient.fetchRtspStreamUri(
                                    host = cam.ipAddress,
                                    onvifPort = cam.onvifPort,
                                    username = "",
                                    password = ""
                                ).getOrNull()
                                if (!fetched.isNullOrBlank() && fetched.startsWith("rtsp://", ignoreCase = true)) {
                                    cam.copy(
                                        suggestedRtspUrl = fetched,
                                        exactStreamUriFound = true
                                    )
                                } else {
                                    cam
                                }
                            } else {
                                cam
                            }
                        }
                    }.awaitAll()
                }

                val existingHosts = existingCameras.mapNotNull { OnvifPtzClient.extractHost(it.rtspUrl)?.lowercase() }.toSet()
                val finalResults = resolvedList
                    .map { cam ->
                        cam.copy(alreadyAdded = cam.ipAddress.lowercase() in existingHosts)
                    }
                    .sortedWith(
                        compareBy<DiscoveredCamera> { it.alreadyAdded }
                            .thenByDescending { it.exactStreamUriFound }
                            .thenBy { it.ipAddress }
                    )

                _state.value = DiscoveryScanState(
                    isScanning = false,
                    progressText = if (finalResults.isEmpty()) {
                        AppLocaleManager.getString(context, R.string.discovery_none_found_progress, subnetSummary)
                    } else {
                        AppLocaleManager.getString(context, R.string.discovery_found_progress, finalResults.size, subnetSummary)
                    },
                    subnetLabel = subnetSummary,
                    results = finalResults,
                    errorMessage = null,
                    hasScannedAtLeastOnce = true
                )
                finalResults
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isScanning = false,
                    progressText = AppLocaleManager.getString(context, R.string.discovery_interrupted),
                    errorMessage = e.localizedMessage ?: AppLocaleManager.getString(context, R.string.discovery_scan_failed),
                    hasScannedAtLeastOnce = true
                )
                emptyList()
            } finally {
                try {
                    if (multicastLock?.isHeld == true) {
                        multicastLock.release()
                    }
                } catch (_: Exception) {}
            }
        }

    fun markExistingCameras(existingCameras: List<CameraEntity>) {
        val current = _state.value
        if (current.results.isEmpty()) return
        val existingHosts = existingCameras.mapNotNull { OnvifPtzClient.extractHost(it.rtspUrl)?.lowercase() }.toSet()
        _state.value = current.copy(
            results = current.results.map { it.copy(alreadyAdded = it.ipAddress.lowercase() in existingHosts) }
        )
    }

    private fun runWsDiscovery(localIpv4s: List<String>): List<DiscoveredCamera> {
        val found = LinkedHashMap<String, DiscoveredCamera>()
        val probeXml = buildWsDiscoveryProbeXml(UUID.randomUUID().toString())
        val payload = probeXml.toByteArray(Charsets.UTF_8)
        val multicastAddr = InetAddress.getByName("239.255.255.250")

        for (localIp in localIpv4s) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(0, InetAddress.getByName(localIp)).apply {
                    soTimeout = 450
                    broadcast = true
                }
                val packet = DatagramPacket(payload, payload.size, multicastAddr, 3702)
                socket.send(packet)

                // Send also subnet broadcast on 3702 as some cameras answer broadcast instead of multicast
                val parts = localIp.split(".")
                if (parts.size == 4) {
                    runCatching {
                        val bcast = InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255")
                        socket.send(DatagramPacket(payload, payload.size, bcast, 3702))
                    }
                }

                val deadline = System.currentTimeMillis() + 1800L
                val buf = ByteArray(16384)
                while (System.currentTimeMillis() < deadline) {
                    val recv = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(recv)
                        val senderIp = recv.address?.hostAddress ?: continue
                        val xml = String(recv.data, 0, recv.length, Charsets.UTF_8)
                        parseWsDiscoveryResponse(xml, senderIp)?.let { cam ->
                            found[cam.ipAddress] = cam
                        }
                    } catch (_: SocketTimeoutException) {
                        // Continue until overall deadline expires
                    }
                }
            } catch (_: Exception) {
                // Interface may disallow multicast UDP
            } finally {
                runCatching { socket?.close() }
            }
        }
        return found.values.toList()
    }

    private suspend fun scanSubnetsForRtspAndOnvif(localIpv4s: List<String>): List<DiscoveredCamera> = coroutineScope {
        val hostsToScan = localIpv4s
            .flatMap { enumerateSubnetHosts(it) }
            .distinct()
            .take(512)

        val semaphore = Semaphore(48)
        hostsToScan.map { ip ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    probeHostTcp(ip)
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun probeHostTcp(ip: String): DiscoveredCamera? {
        val openRtspPort = when {
            isTcpPortOpen(ip, 554, 240) -> 554
            isTcpPortOpen(ip, 8554, 240) -> 8554
            else -> null
        }

        // Check ONVIF HTTP ports only if RTSP port is open OR check standard ONVIF port 8000/80 quickly if RTSP was closed
        var discoveredOnvifPort: Int? = null
        var bannerHint: BrandHint? = null

        if (openRtspPort != null) {
            val banner = readRtspOptionsBanner(ip, openRtspPort)
            bannerHint = detectBrandHint(banner)
            for (port in listOf(80, 8000, 8080, 2020, 8899)) {
                if (isTcpPortOpen(ip, port, 180)) {
                    discoveredOnvifPort = port
                    break
                }
            }
        } else {
            // Some cameras expose ONVIF on 8000 or 2020 even if RTSP uses non-standard port
            for (port in listOf(8000, 2020, 8899)) {
                if (isTcpPortOpen(ip, port, 180)) {
                    if (looksLikeOnvifEndpoint(ip, port)) {
                        discoveredOnvifPort = port
                        break
                    }
                }
            }
        }

        if (openRtspPort == null && discoveredOnvifPort == null) return null

        val finalRtspPort = openRtspPort ?: 554
        val finalOnvifPort = discoveredOnvifPort ?: (bannerHint?.defaultOnvifPort ?: 80)
        val brand = bannerHint?.brand ?: "IP Camera"
        val path = bannerHint?.defaultPath ?: "/"
        val method = buildString {
            if (openRtspPort != null) append("RTSP:$openRtspPort")
            if (discoveredOnvifPort != null) {
                if (isNotEmpty()) append(" • ")
                append("ONVIF:$discoveredOnvifPort")
            }
        }

        return DiscoveredCamera(
            id = "disc_$ip",
            ipAddress = ip,
            rtspPort = finalRtspPort,
            onvifPort = finalOnvifPort,
            name = if (brand != "IP Camera") "$brand (${ip.substringAfterLast('.')})" else "Camera $ip",
            manufacturer = brand,
            suggestedRtspUrl = buildSuggestedRtspUrl(ip, finalRtspPort, path),
            discoveryMethod = method,
            onvifDeviceServiceUrl = if (discoveredOnvifPort != null) {
                "http://$ip:$finalOnvifPort/onvif/device_service"
            } else {
                ""
            }
        )
    }

    private fun isTcpPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readRtspOptionsBanner(ip: String, port: Int): String {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 300)
                socket.soTimeout = 350
                val out = socket.getOutputStream()
                val req = "OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: OmniView-RTSP/1.0\r\n\r\n"
                out.write(req.toByteArray(Charsets.US_ASCII))
                out.flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                buildString {
                    var count = 0
                    while (count < 12) {
                        val line = reader.readLine() ?: break
                        append(line).append('\n')
                        if (line.isBlank()) break
                        count++
                    }
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun looksLikeOnvifEndpoint(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 240)
                socket.soTimeout = 350
                val out = socket.getOutputStream()
                val body = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Body><tds:GetSystemDateAndTime xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/></s:Body></s:Envelope>"
                val req = "POST /onvif/device_service HTTP/1.1\r\nHost: $ip:$port\r\nContent-Type: application/soap+xml\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                out.write(req.toByteArray(Charsets.UTF_8))
                out.flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val text = buildString {
                    var i = 0
                    while (i < 10) {
                        val line = reader.readLine() ?: break
                        append(line).append('\n')
                        i++
                    }
                }
                text.contains("Envelope", ignoreCase = true) ||
                    text.contains("onvif", ignoreCase = true) ||
                    text.contains("GetSystemDateAndTime", ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getLocalIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                        addr.hostAddress?.let { result.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        return result.distinct()
    }

    private fun mergeDiscovered(a: DiscoveredCamera, b: DiscoveredCamera): DiscoveredCamera {
        val preferredMethod = listOf(a.discoveryMethod, b.discoveryMethod)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" • ")
        return DiscoveredCamera(
            id = a.id,
            ipAddress = a.ipAddress,
            rtspPort = if (a.rtspPort != 554) a.rtspPort else b.rtspPort,
            onvifPort = if (b.onvifDeviceServiceUrl.isNotBlank()) b.onvifPort else a.onvifPort,
            name = if (a.manufacturer != "IP Camera") a.name else b.name,
            manufacturer = if (a.manufacturer != "IP Camera") a.manufacturer else b.manufacturer,
            suggestedRtspUrl = when {
                a.exactStreamUriFound -> a.suggestedRtspUrl
                b.exactStreamUriFound -> b.suggestedRtspUrl
                a.manufacturer != "IP Camera" -> a.suggestedRtspUrl
                else -> b.suggestedRtspUrl
            },
            discoveryMethod = preferredMethod,
            onvifDeviceServiceUrl = a.onvifDeviceServiceUrl.ifBlank { b.onvifDeviceServiceUrl },
            exactStreamUriFound = a.exactStreamUriFound || b.exactStreamUriFound
        )
    }

    companion object {
        fun buildWsDiscoveryProbeXml(messageUuid: String): String = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                        xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                        xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
                <s:Header>
                    <a:Action s:mustUnderstand="1">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>
                    <a:MessageID>uuid:$messageUuid</a:MessageID>
                    <a:ReplyTo><a:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>
                    <a:To s:mustUnderstand="1">urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>
                </s:Header>
                <s:Body>
                    <d:Probe>
                        <d:Types>dn:NetworkVideoTransmitter</d:Types>
                    </d:Probe>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        fun parseWsDiscoveryResponse(xml: String, senderIp: String): DiscoveredCamera? {
            if (!xml.contains("ProbeMatch", ignoreCase = true) && !xml.contains("XAddrs", ignoreCase = true)) {
                return null
            }
            val xaddrsRaw = Regex("<(?:[a-zA-Z0-9]+:)?XAddrs>([^<]+)</(?:[a-zA-Z0-9]+:)?XAddrs>")
                .find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val xaddrUrl = xaddrsRaw.split(Regex("\\s+"))
                .firstOrNull { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                ?: "http://$senderIp:80/onvif/device_service"

            val parsedUri = runCatching { URI.create(xaddrUrl) }.getOrNull()
            val host = parsedUri?.host?.takeIf { it.isNotBlank() } ?: senderIp
            val onvifPort = if (parsedUri != null && parsedUri.port in 1..65535) parsedUri.port else 80

            val scopes = Regex("<(?:[a-zA-Z0-9]+:)?Scopes>([^<]+)</(?:[a-zA-Z0-9]+:)?Scopes>")
                .find(xml)?.groupValues?.getOrNull(1).orEmpty()

            val scopeName = Regex("onvif://www\\.onvif\\.org/name/([^\\s]+)", RegexOption.IGNORE_CASE)
                .find(scopes)?.groupValues?.getOrNull(1)
                ?.replace("%20", " ")
                ?.replace("_", " ")
                ?.trim()

            val hint = detectBrandHint("$scopes $xaddrUrl")
            val brand = hint?.brand ?: "ONVIF Camera"
            val path = hint?.defaultPath ?: "/stream1"

            return DiscoveredCamera(
                id = "disc_$host",
                ipAddress = host,
                rtspPort = 554,
                onvifPort = onvifPort,
                name = scopeName?.takeIf { it.isNotBlank() } ?: "$brand (${host.substringAfterLast('.')})",
                manufacturer = brand,
                suggestedRtspUrl = buildSuggestedRtspUrl(host, 554, path),
                discoveryMethod = "ONVIF:$onvifPort",
                onvifDeviceServiceUrl = xaddrUrl
            )
        }

        fun detectBrandHint(text: String): BrandHint? {
            val lower = text.lowercase()
            return when {
                lower.contains("hikvision") || lower.contains("ds-2cd") ->
                    BrandHint("Hikvision", "/Streaming/Channels/101", 80)
                lower.contains("dahua") || lower.contains("amcrest") || lower.contains("lorex") ->
                    BrandHint("Dahua / Amcrest", "/cam/realmonitor?channel=1&subtype=0", 80)
                lower.contains("reolink") || lower.contains("rlc-") ->
                    BrandHint("Reolink", "/h264Preview_01_main", 8000)
                lower.contains("tapo") || lower.contains("tp-link") ->
                    BrandHint("TP-Link Tapo", "/stream1", 2020)
                lower.contains("axis") ->
                    BrandHint("Axis", "/axis-media/media.amp", 80)
                lower.contains("uniview") || lower.contains("unv") ->
                    BrandHint("Uniview", "/unicast/c1/s0/live", 80)
                lower.contains("foscam") ->
                    BrandHint("Foscam", "/videoMain", 88)
                lower.contains("ezviz") ->
                    BrandHint("EZVIZ", "/h264_stream", 80)
                else -> null
            }
        }

        fun buildSuggestedRtspUrl(ip: String, rtspPort: Int, path: String): String {
            val cleanPath = if (path.startsWith("/")) path else "/$path"
            return "rtsp://$ip:$rtspPort$cleanPath"
        }

        fun enumerateSubnetHosts(localIp: String): List<String> {
            val parts = localIp.trim().split(".")
            if (parts.size != 4) return emptyList()
            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
            val selfHost = parts[3].toIntOrNull()
            return (1..254)
                .filter { it != selfHost }
                .map { "$prefix.$it" }
        }
    }
}
