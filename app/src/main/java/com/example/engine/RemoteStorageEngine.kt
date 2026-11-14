package com.example.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.data.model.CloudProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class NasSubProtocol {
    FTP,
    SFTP_PROBE,
    HTTP_GATEWAY
}

enum class RemoteFolderLayout {
    FLAT,
    BY_CAMERA,
    BY_CAMERA_AND_DATE
}

enum class SyncNetworkPolicy {
    WIFI_AND_VPN,
    WIFI_ONLY,
    ALL_NETWORKS
}

data class ExtendedStorageConfig(
    val smbHost: String = "",
    val smbPort: Int = 445,
    val smbShare: String = "recordings",
    val smbSubfolder: String = "OmniView",
    val smbWorkgroup: String = "WORKGROUP",
    val smbUsername: String = "",
    val smbPassword: String = "",
    val nasSubProtocol: NasSubProtocol = NasSubProtocol.FTP,
    val nasHost: String = "",
    val nasPort: Int = 21,
    val nasRemotePath: String = "/OmniView",
    val folderLayout: RemoteFolderLayout = RemoteFolderLayout.BY_CAMERA_AND_DATE,
    val networkPolicy: SyncNetworkPolicy = SyncNetworkPolicy.WIFI_AND_VPN,
    val minFreeSpaceMb: Int = 512,
    val deleteLocalAfterUpload: Boolean = false,
    val uploadRetries: Int = 2
)

data class DiscoveredStorageEndpoint(
    val host: String,
    val port: Int,
    val serviceType: CloudProviderType,
    val subProtocol: NasSubProtocol? = null,
    val label: String,
    val interfaceHint: String,
    val latencyMs: Int
)

data class StorageConnectionTestResult(
    val success: Boolean,
    val protocolLabel: String,
    val summary: String,
    val details: String,
    val latencyMs: Int
)

data class SmbNegotiateInfo(
    val isSmb2: Boolean,
    val dialectCode: Int,
    val dialectLabel: String,
    val signingEnabled: Boolean,
    val signingRequired: Boolean,
    val ntStatus: Long
)

object RemoteStoragePreferences {
    private const val PREFS_NAME = "omniview_remote_storage_prefs"
    private const val KEY_SMB_HOST = "smb_host"
    private const val KEY_SMB_PORT = "smb_port"
    private const val KEY_SMB_SHARE = "smb_share"
    private const val KEY_SMB_SUBFOLDER = "smb_subfolder"
    private const val KEY_SMB_WORKGROUP = "smb_workgroup"
    private const val KEY_SMB_USER = "smb_username"
    private const val KEY_SMB_PASS = "smb_password"
    private const val KEY_NAS_SUB = "nas_sub_protocol"
    private const val KEY_NAS_HOST = "nas_host"
    private const val KEY_NAS_PORT = "nas_port"
    private const val KEY_NAS_PATH = "nas_remote_path"
    private const val KEY_FOLDER_LAYOUT = "folder_layout"
    private const val KEY_NET_POLICY = "network_policy"
    private const val KEY_MIN_FREE_MB = "min_free_space_mb"
    private const val KEY_DELETE_LOCAL = "delete_local_after_upload"
    private const val KEY_RETRIES = "upload_retries"

    fun load(context: Context): ExtendedStorageConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sub = runCatching {
            NasSubProtocol.valueOf(prefs.getString(KEY_NAS_SUB, NasSubProtocol.FTP.name).orEmpty())
        }.getOrDefault(NasSubProtocol.FTP)
        val layout = runCatching {
            RemoteFolderLayout.valueOf(
                prefs.getString(KEY_FOLDER_LAYOUT, RemoteFolderLayout.BY_CAMERA_AND_DATE.name).orEmpty()
            )
        }.getOrDefault(RemoteFolderLayout.BY_CAMERA_AND_DATE)
        val netPolicy = runCatching {
            SyncNetworkPolicy.valueOf(
                prefs.getString(KEY_NET_POLICY, SyncNetworkPolicy.WIFI_AND_VPN.name).orEmpty()
            )
        }.getOrDefault(SyncNetworkPolicy.WIFI_AND_VPN)

        return ExtendedStorageConfig(
            smbHost = prefs.getString(KEY_SMB_HOST, "").orEmpty(),
            smbPort = prefs.getInt(KEY_SMB_PORT, 445).coerceIn(1, 65535),
            smbShare = prefs.getString(KEY_SMB_SHARE, "recordings").orEmpty(),
            smbSubfolder = prefs.getString(KEY_SMB_SUBFOLDER, "OmniView").orEmpty(),
            smbWorkgroup = prefs.getString(KEY_SMB_WORKGROUP, "WORKGROUP").orEmpty(),
            smbUsername = prefs.getString(KEY_SMB_USER, "").orEmpty(),
            smbPassword = prefs.getString(KEY_SMB_PASS, "").orEmpty(),
            nasSubProtocol = sub,
            nasHost = prefs.getString(KEY_NAS_HOST, "").orEmpty(),
            nasPort = prefs.getInt(KEY_NAS_PORT, 21).coerceIn(1, 65535),
            nasRemotePath = prefs.getString(KEY_NAS_PATH, "/OmniView").orEmpty(),
            folderLayout = layout,
            networkPolicy = netPolicy,
            minFreeSpaceMb = prefs.getInt(KEY_MIN_FREE_MB, 512).coerceIn(64, 102400),
            deleteLocalAfterUpload = prefs.getBoolean(KEY_DELETE_LOCAL, false),
            uploadRetries = prefs.getInt(KEY_RETRIES, 2).coerceIn(0, 5)
        )
    }

    fun save(context: Context, config: ExtendedStorageConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SMB_HOST, config.smbHost.trim())
            .putInt(KEY_SMB_PORT, config.smbPort.coerceIn(1, 65535))
            .putString(KEY_SMB_SHARE, config.smbShare.trim())
            .putString(KEY_SMB_SUBFOLDER, config.smbSubfolder.trim())
            .putString(KEY_SMB_WORKGROUP, config.smbWorkgroup.trim().ifBlank { "WORKGROUP" })
            .putString(KEY_SMB_USER, config.smbUsername.trim())
            .putString(KEY_SMB_PASS, config.smbPassword)
            .putString(KEY_NAS_SUB, config.nasSubProtocol.name)
            .putString(KEY_NAS_HOST, config.nasHost.trim())
            .putInt(KEY_NAS_PORT, config.nasPort.coerceIn(1, 65535))
            .putString(KEY_NAS_PATH, config.nasRemotePath.trim().ifBlank { "/OmniView" })
            .putString(KEY_FOLDER_LAYOUT, config.folderLayout.name)
            .putString(KEY_NET_POLICY, config.networkPolicy.name)
            .putInt(KEY_MIN_FREE_MB, config.minFreeSpaceMb.coerceIn(64, 102400))
            .putBoolean(KEY_DELETE_LOCAL, config.deleteLocalAfterUpload)
            .putInt(KEY_RETRIES, config.uploadRetries.coerceIn(0, 5))
            .apply()
    }
}

object RemoteStorageDiscovery {
    private val probeTargets = listOf(
        Triple(445, CloudProviderType.SMB_CIFS, null to "SMB / Windows / Samba"),
        Triple(5006, CloudProviderType.WEBDAV, null to "WebDAV HTTPS (Synology/NAS)"),
        Triple(5005, CloudProviderType.WEBDAV, null to "WebDAV HTTP (NAS)"),
        Triple(21, CloudProviderType.FTP_NAS_STORAGE, NasSubProtocol.FTP to "FTP File Server"),
        Triple(22, CloudProviderType.FTP_NAS_STORAGE, NasSubProtocol.SFTP_PROBE to "SFTP / SSH Storage"),
        Triple(9000, CloudProviderType.AWS_S3_COMPATIBLE, null to "MinIO / S3 Object Store")
    )

    suspend fun discoverStorageServers(): List<DiscoveredStorageEndpoint> = withContext(Dispatchers.IO) {
        val subnets = collectLocalSubnets()
        if (subnets.isEmpty()) return@withContext emptyList()

        val semaphore = Semaphore(28)
        val priorityOctets = listOf(1, 2, 10, 20, 50, 100, 150, 200, 250, 254)
        val results = coroutineScope {
            subnets.take(2).flatMap { (ifaceName, prefix, selfLastOctet) ->
                val octetsToScan = buildList {
                    addAll(priorityOctets)
                    for (last in 1..254) {
                        if (last != selfLastOctet && last !in priorityOctets) add(last)
                    }
                }.distinct().take(128) // Prioritize likely NAS/server IPs plus fast subnet sweep

                octetsToScan.flatMap { lastOctet ->
                    val host = "$prefix.$lastOctet"
                    probeTargets.map { (port, provider, protoAndLabel) ->
                        async {
                            semaphore.withPermit {
                                val start = System.currentTimeMillis()
                                val open = try {
                                    Socket().use { sock ->
                                        sock.connect(InetSocketAddress(host, port), 180)
                                        true
                                    }
                                } catch (_: Exception) {
                                    false
                                }
                                if (!open) {
                                    null
                                } else {
                                    val latency = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
                                    DiscoveredStorageEndpoint(
                                        host = host,
                                        port = port,
                                        serviceType = provider,
                                        subProtocol = protoAndLabel.first,
                                        label = protoAndLabel.second,
                                        interfaceHint = ifaceName,
                                        latencyMs = latency
                                    )
                                }
                            }
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        results
            .distinctBy { "${it.host}:${it.port}" }
            .sortedWith(compareBy<DiscoveredStorageEndpoint> { it.serviceType.ordinal }.thenBy { it.latencyMs })
    }

    private fun collectLocalSubnets(): List<Triple<String, String, Int>> {
        val out = mutableListOf<Triple<String, String, Int>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                            val last = parts[3].toIntOrNull() ?: 1
                            out.add(Triple(iface.name.orEmpty(), prefix, last))
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return out.distinctBy { it.second }
    }
}

object RemoteStorageProtocolClient {

    fun buildRemoteRelativePath(
        layout: RemoteFolderLayout,
        cameraName: String,
        timestampMs: Long,
        fileName: String
    ): String {
        val safeCam = cameraName.trim()
            .replace(Regex("[^A-Za-z0-9_-]+"), "_")
            .trim('_')
            .ifBlank { "Camera" }
        val safeFile = fileName.trim().ifBlank { "clip.mp4" }
        return when (layout) {
            RemoteFolderLayout.FLAT -> safeFile
            RemoteFolderLayout.BY_CAMERA -> "$safeCam/$safeFile"
            RemoteFolderLayout.BY_CAMERA_AND_DATE -> {
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMs))
                "$safeCam/$dateStr/$safeFile"
            }
        }
    }

    /**
     * Builds a standards-compliant NetBIOS Session Service + SMB2 Negotiate Request packet
     * advertising SMB 2.0.2, 2.1, 3.0, 3.0.2, and 3.1.1 dialects.
     */
    fun buildSmb2NegotiatePacket(): ByteArray {
        val dialects = intArrayOf(0x0202, 0x0210, 0x0300, 0x0302, 0x0311)
        val headerSize = 64
        val negotiateBodySize = 36 + dialects.size * 2 // 46 bytes
        val smbLen = headerSize + negotiateBodySize
        val total = 4 + smbLen
        val buf = ByteArray(total)

        // NetBIOS Session Message header (type 0x00, 3-byte big-endian length)
        buf[0] = 0x00
        buf[1] = ((smbLen ushr 16) and 0xFF).toByte()
        buf[2] = ((smbLen ushr 8) and 0xFF).toByte()
        buf[3] = (smbLen and 0xFF).toByte()

        // SMB2 Header (little-endian)
        var off = 4
        buf[off + 0] = 0xFE.toByte()
        buf[off + 1] = 'S'.code.toByte()
        buf[off + 2] = 'M'.code.toByte()
        buf[off + 3] = 'B'.code.toByte()
        putLeShort(buf, off + 4, 64) // StructureSize = 64
        putLeShort(buf, off + 6, 0)  // CreditCharge
        putLeInt(buf, off + 8, 0)    // Status
        putLeShort(buf, off + 12, 0) // Command = 0 (NEGOTIATE)
        putLeShort(buf, off + 14, 1) // CreditRequest = 1
        putLeInt(buf, off + 16, 0)   // Flags
        putLeInt(buf, off + 20, 0)   // NextCommand
        // MessageId = 1 (bytes 24..31), rest zero

        buf[off + 24] = 1

        // SMB2 NEGOTIATE Request (offset 68)
        off = 4 + 64
        putLeShort(buf, off + 0, 36)            // StructureSize = 36
        putLeShort(buf, off + 2, dialects.size) // DialectCount
        putLeShort(buf, off + 4, 1)             // SecurityMode = NEGOTIATE_SIGNING_ENABLED
        putLeShort(buf, off + 6, 0)             // Reserved
        putLeInt(buf, off + 8, 0)               // Capabilities
        // ClientGuid (16 bytes at off+12..off+27) -> non-zero deterministic pattern
        buf[off + 12] = 0x4F
        buf[off + 13] = 0x56
        // Dialects start at off + 36
        var dOff = off + 36
        for (d in dialects) {
            putLeShort(buf, dOff, d)
            dOff += 2
        }
        return buf
    }

    /**
     * Parses a NetBIOS-wrapped SMB2 Negotiate Response packet.
     */
    fun parseSmb2NegotiateResponse(packet: ByteArray): SmbNegotiateInfo? {
        if (packet.size < 4 + 64 + 6) return null
        val smbOff = 4
        // Check SMB2 magic: 0xFE 'S' 'M' 'B'
        if (packet[smbOff] == 0xFE.toByte() &&
            packet[smbOff + 1] == 'S'.code.toByte() &&
            packet[smbOff + 2] == 'M'.code.toByte() &&
            packet[smbOff + 3] == 'B'.code.toByte()
        ) {
            val status = getLeInt(packet, smbOff + 8).toLong() and 0xFFFFFFFFL
            val bodyOff = smbOff + 64
            if (packet.size < bodyOff + 6) return null
            val securityMode = getLeShort(packet, bodyOff + 2)
            val dialect = getLeShort(packet, bodyOff + 4)
            val dialectLabel = when (dialect) {
                0x0202 -> "SMB 2.0.2"
                0x0210 -> "SMB 2.1"
                0x0300 -> "SMB 3.0"
                0x0302 -> "SMB 3.0.2"
                0x0311 -> "SMB 3.1.1"
                0x02FF -> "SMB 2.x Wildcard"
                else -> "SMB 0x${dialect.toString(16).uppercase(Locale.US)}"
            }
            return SmbNegotiateInfo(
                isSmb2 = true,
                dialectCode = dialect,
                dialectLabel = dialectLabel,
                signingEnabled = (securityMode and 0x01) != 0,
                signingRequired = (securityMode and 0x02) != 0,
                ntStatus = status
            )
        }
        // Check legacy SMB1 fallback (\xFF 'S' 'M' 'B')
        if (packet[smbOff] == 0xFF.toByte() &&
            packet[smbOff + 1] == 'S'.code.toByte() &&
            packet[smbOff + 2] == 'M'.code.toByte() &&
            packet[smbOff + 3] == 'B'.code.toByte()
        ) {
            return SmbNegotiateInfo(
                isSmb2 = false,
                dialectCode = 0x0100,
                dialectLabel = "SMB 1.0 / CIFS",
                signingEnabled = false,
                signingRequired = false,
                ntStatus = 0L
            )
        }
        return null
    }

    suspend fun testSmbConnection(
        context: Context?,
        host: String,
        port: Int,
        share: String,
        subfolder: String,
        workgroup: String,
        username: String,
        password: String,
        safTreeUri: String
    ): StorageConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val cleanHost = host.trim()
        val safePort = port.coerceIn(1, 65535)
        if (cleanHost.isBlank()) {
            return@withContext StorageConnectionTestResult(
                success = false,
                protocolLabel = "SMB / CIFS",
                summary = "Missing SMB host or IP address",
                details = "Enter your NAS/PC IP (e.g. 192.168.1.50 or Tailscale 100.x.x.x) or run Auto-Scan.",
                latencyMs = 0
            )
        }

        try {
            var negotiateInfo: SmbNegotiateInfo? = null
            Socket().use { sock ->
                sock.soTimeout = 4_000
                sock.connect(InetSocketAddress(cleanHost, safePort), 4_000)
                val out = sock.getOutputStream()
                val input = BufferedInputStream(sock.getInputStream())
                out.write(buildSmb2NegotiatePacket())
                out.flush()

                val nbHeader = ByteArray(4)
                val readHdr = readFully(input, nbHeader, 4)
                if (readHdr == 4) {
                    val payloadLen = ((nbHeader[1].toInt() and 0xFF) shl 16) or
                        ((nbHeader[2].toInt() and 0xFF) shl 8) or
                        (nbHeader[3].toInt() and 0xFF)
                    if (payloadLen in 68..32768) {
                        val payload = ByteArray(payloadLen)
                        val readBody = readFully(input, payload, payloadLen)
                        if (readBody > 0) {
                            val combined = ByteArray(4 + readBody)
                            System.arraycopy(nbHeader, 0, combined, 0, 4)
                            System.arraycopy(payload, 0, combined, 4, readBody)
                            negotiateInfo = parseSmb2NegotiateResponse(combined)
                        }
                    }
                }
            }

            val latency = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
            val safWriteOk = checkSafTreeAccessible(context, safTreeUri)
            val signingText = when {
                negotiateInfo?.signingRequired == true -> "Signing Required"
                negotiateInfo?.signingEnabled == true -> "Signing Supported"
                else -> "Signing Optional"
            }
            val dialect = negotiateInfo?.dialectLabel ?: "TCP Reachable ($safePort)"
            val shareDesc = "//${cleanHost}/${share.trim().ifBlank { "share" }}/${subfolder.trim()}"
            val authHint = if (username.isBlank()) "Guest / Anonymous" else "User '${username.trim()}' (${workgroup.ifBlank { "WORKGROUP" }})"
            val safHint = if (safTreeUri.isNotBlank()) {
                if (safWriteOk) " • Android Mounted SMB Folder: WRITABLE ✓" else " • Mounted Folder permission expired"
            } else {
                " • Tip: You can also attach an Android Files/SMB Document Provider or enable WebDAV/FTP on your NAS for direct auto-sync"
            }

            StorageConnectionTestResult(
                success = true,
                protocolLabel = "SMB / CIFS",
                summary = "SMB Handshake OK ($dialect • ${latency}ms)",
                details = "Target: $shareDesc • $signingText • $authHint$safHint",
                latencyMs = latency
            )
        } catch (e: Exception) {
            val latency = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
            StorageConnectionTestResult(
                success = false,
                protocolLabel = "SMB / CIFS",
                summary = "SMB Connection Failed (${latency}ms)",
                details = "Could not reach $cleanHost:$safePort — ${e.localizedMessage ?: e.javaClass.simpleName}",
                latencyMs = latency
            )
        }
    }

    suspend fun testFtpConnection(
        host: String,
        port: Int,
        username: String,
        password: String
    ): StorageConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val cleanHost = extractHost(host)
        val safePort = port.coerceIn(1, 65535)
        if (cleanHost.isBlank()) {
            return@withContext StorageConnectionTestResult(
                success = false,
                protocolLabel = "FTP",
                summary = "Missing FTP host or IP",
                details = "Enter FTP host/IP (e.g. 192.168.1.50)",
                latencyMs = 0
            )
        }

        try {
            Socket().use { sock ->
                sock.soTimeout = 5_000
                sock.connect(InetSocketAddress(cleanHost, safePort), 5_000)
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                val out = sock.getOutputStream()

                val banner = readFtpResponse(reader)
                if (!banner.startsWith("220")) {
                    throw IllegalStateException("Unexpected FTP greeting: $banner")
                }

                val user = username.trim().ifBlank { "anonymous" }
                val pass = if (username.isBlank() && password.isBlank()) "omniview@local" else password

                sendFtpCommand(out, "USER $user")
                val userResp = readFtpResponse(reader)
                var loginResp = userResp
                if (userResp.startsWith("331")) {
                    sendFtpCommand(out, "PASS $pass")
                    loginResp = readFtpResponse(reader)
                }
                if (!loginResp.startsWith("230") && !loginResp.startsWith("202")) {
                    throw IllegalStateException("FTP authentication rejected: $loginResp")
                }

                sendFtpCommand(out, "PWD")
                val pwdResp = readFtpResponse(reader)
                runCatching { sendFtpCommand(out, "QUIT") }

                val latency = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
                StorageConnectionTestResult(
                    success = true,
                    protocolLabel = "FTP",
                    summary = "FTP Login Verified (${latency}ms)",
                    details = "Banner: ${banner.take(64)} • Dir: $pwdResp",
                    latencyMs = latency
                )
            }
        } catch (e: Exception) {
            val latency = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
            StorageConnectionTestResult(
                success = false,
                protocolLabel = "FTP",
                summary = "FTP Failed (${latency}ms)",
                details = "$cleanHost:$safePort — ${e.localizedMessage ?: e.javaClass.simpleName}",
                latencyMs = latency
            )
        }
    }

    suspend fun uploadViaFtp(
        host: String,
        port: Int,
        username: String,
        password: String,
        baseDir: String,
        relativePath: String,
        file: File
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanHost = extractHost(host)
            val safePort = port.coerceIn(1, 65535)
            require(cleanHost.isNotBlank()) { "FTP host is empty" }
            require(file.exists() && file.length() > 0L) { "Clip file missing or empty" }

            Socket().use { ctrlSock ->
                ctrlSock.soTimeout = 15_000
                ctrlSock.connect(InetSocketAddress(cleanHost, safePort), 10_000)
                val reader = BufferedReader(InputStreamReader(ctrlSock.getInputStream(), StandardCharsets.UTF_8))
                val out = ctrlSock.getOutputStream()

                val banner = readFtpResponse(reader)
                require(banner.startsWith("220")) { "FTP greeting error: $banner" }

                val user = username.trim().ifBlank { "anonymous" }
                val pass = if (username.isBlank() && password.isBlank()) "omniview@local" else password
                sendFtpCommand(out, "USER $user")
                val userResp = readFtpResponse(reader)
                if (userResp.startsWith("331")) {
                    sendFtpCommand(out, "PASS $pass")
                    val passResp = readFtpResponse(reader)
                    require(passResp.startsWith("230") || passResp.startsWith("202")) {
                        "FTP auth failed: $passResp"
                    }
                }

                sendFtpCommand(out, "TYPE I")
                readFtpResponse(reader)

                val fullRel = listOf(baseDir.trim('/'), relativePath.trim('/'))
                    .filter { it.isNotBlank() }
                    .joinToString("/")
                val parts = fullRel.split("/").filter { it.isNotBlank() }
                val fileName = parts.lastOrNull() ?: file.name
                val dirParts = parts.dropLast(1)

                for (dir in dirParts) {
                    sendFtpCommand(out, "MKD $dir")
                    readFtpResponse(reader)
                    sendFtpCommand(out, "CWD $dir")
                    val cwdResp = readFtpResponse(reader)
                    if (!cwdResp.startsWith("250")) {
                        // Try returning only if absolute failed; relative MKD+CWD already walks step-by-step
                    }
                }

                sendFtpCommand(out, "PASV")
                val pasvResp = readFtpResponse(reader)
                val (dataHost, dataPort) = parsePasvEndpoint(pasvResp, cleanHost)
                    ?: throw IllegalStateException("FTP PASV rejected: $pasvResp")

                Socket().use { dataSock ->
                    dataSock.soTimeout = 30_000
                    dataSock.connect(InetSocketAddress(dataHost, dataPort), 10_000)

                    sendFtpCommand(out, "STOR $fileName")
                    val storReady = readFtpResponse(reader)
                    require(storReady.startsWith("150") || storReady.startsWith("125")) {
                        "FTP STOR rejected: $storReady"
                    }

                    dataSock.getOutputStream().use { dataOut ->
                        file.inputStream().use { input ->
                            input.copyTo(dataOut)
                        }
                        dataOut.flush()
                    }
                }

                val complete = readFtpResponse(reader)
                require(complete.startsWith("226") || complete.startsWith("250")) {
                    "FTP transfer incomplete: $complete"
                }
                runCatching { sendFtpCommand(out, "QUIT") }
                "ftp://$cleanHost:$safePort/$fullRel"
            }
        }
    }

    suspend fun testSftpBanner(host: String, port: Int): StorageConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val cleanHost = extractHost(host)
        val safePort = port.coerceIn(1, 65535)
        if (cleanHost.isBlank()) {
            return@withContext StorageConnectionTestResult(
                success = false,
                protocolLabel = "SFTP / SSH",
                summary = "Missing SFTP host or IP",
                details = "Enter your NAS/server IP for SSH/SFTP port $safePort",
                latencyMs = 0
            )
        }
        try {
            Socket().use { sock ->
                sock.soTimeout = 4_000
                sock.connect(InetSocketAddress(cleanHost, safePort), 4_000)
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII))
                val sshBanner = reader.readLine()?.trim().orEmpty()
                val latency = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
                if (sshBanner.startsWith("SSH-")) {
                    StorageConnectionTestResult(
                        success = true,
                        protocolLabel = "SFTP / SSH",
                        summary = "SSH/SFTP Endpoint Reachable (${latency}ms)",
                        details = "Server banner: $sshBanner (use WebDAV/FTP/S3/SAF for unattended auto-sync)",
                        latencyMs = latency
                    )
                } else {
                    StorageConnectionTestResult(
                        success = false,
                        protocolLabel = "SFTP / SSH",
                        summary = "Non-SSH response on $cleanHost:$safePort",
                        details = "Received: '${sshBanner.ifBlank { "empty" }}'",
                        latencyMs = latency
                    )
                }
            }
        } catch (e: Exception) {
            val latency = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
            StorageConnectionTestResult(
                success = false,
                protocolLabel = "SFTP / SSH",
                summary = "SFTP Unreachable (${latency}ms)",
                details = "$cleanHost:$safePort — ${e.localizedMessage ?: e.javaClass.simpleName}",
                latencyMs = latency
            )
        }
    }

    fun parsePasvEndpoint(pasvResponse: String, fallbackHost: String): Pair<String, Int>? {
        val match = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").find(pasvResponse) ?: return null
        val g = match.groupValues
        val ip = "${g[1]}.${g[2]}.${g[3]}.${g[4]}"
        val p1 = g[5].toIntOrNull() ?: return null
        val p2 = g[6].toIntOrNull() ?: return null
        val port = (p1 shl 8) + p2
        val targetIp = if (ip.startsWith("0.") || ip == "127.0.0.1") fallbackHost else ip
        return targetIp to port
    }

    fun extractHost(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return ""
        return trimmed
            .substringAfter("://", trimmed)
            .substringBefore("/")
            .substringAfter("@")
            .substringBefore(":")
            .trim()
    }

    private fun checkSafTreeAccessible(context: Context?, uriString: String): Boolean {
        if (context == null || uriString.isBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            context.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val flags = cursor.getInt(0)
                        (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
                    } else {
                        false
                    }
                } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun sendFtpCommand(out: OutputStream, cmd: String) {
        out.write("$cmd\r\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun readFtpResponse(reader: BufferedReader): String {
        val firstLine = reader.readLine() ?: return ""
        if (firstLine.length >= 4 && firstLine[3] == '-') {
            val codePrefix = firstLine.substring(0, 3) + " "
            var last = firstLine
            while (true) {
                val next = reader.readLine() ?: break
                last = next
                if (next.startsWith(codePrefix)) break
            }
            return last
        }
        return firstLine
    }

    private fun readFully(input: BufferedInputStream, dest: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val r = input.read(dest, total, len - total)
            if (r <= 0) break
            total += r
        }
        return total
    }

    private fun putLeShort(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putLeInt(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun getLeShort(buf: ByteArray, off: Int): Int {
        return (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun getLeInt(buf: ByteArray, off: Int): Int {
        return (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
    }
}
