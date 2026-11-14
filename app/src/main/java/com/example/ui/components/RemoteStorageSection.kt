package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.CloudConfigEntity
import com.example.data.model.CloudProviderType
import com.example.engine.DiscoveredStorageEndpoint
import com.example.engine.ExtendedStorageConfig
import com.example.engine.NasSubProtocol
import com.example.engine.RemoteFolderLayout
import com.example.engine.StorageConnectionTestResult
import com.example.engine.SyncNetworkPolicy
import com.example.i18n.AppLocaleManager
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceSurface

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteStorageSection(
    cloudConfig: CloudConfigEntity,
    extendedConfig: ExtendedStorageConfig,
    discoveredEndpoints: List<DiscoveredStorageEndpoint>,
    isScanningStorage: Boolean,
    isTesting: Boolean,
    testResult: String?,
    detailedTestResult: StorageConnectionTestResult?,
    onScanNetwork: () -> Unit,
    onLaunchSafFolderPicker: () -> Unit,
    onTestConnection: (CloudConfigEntity, ExtendedStorageConfig) -> Unit,
    onSaveCredentials: (CloudConfigEntity, ExtendedStorageConfig) -> Unit,
    onSaveExtendedOptions: (ExtendedStorageConfig) -> Unit,
    onSaveSyncPolicies: (CloudConfigEntity) -> Unit
) {
    val context = LocalContext.current
    var selectedProvider by remember(cloudConfig.provider) { mutableStateOf(cloudConfig.provider) }

    // SMB / CIFS fields
    var smbHost by remember(extendedConfig.smbHost) { mutableStateOf(extendedConfig.smbHost) }
    var smbPortText by remember(extendedConfig.smbPort) { mutableStateOf(extendedConfig.smbPort.toString()) }
    var smbShare by remember(extendedConfig.smbShare) { mutableStateOf(extendedConfig.smbShare) }
    var smbSubfolder by remember(extendedConfig.smbSubfolder) { mutableStateOf(extendedConfig.smbSubfolder) }
    var smbWorkgroup by remember(extendedConfig.smbWorkgroup) { mutableStateOf(extendedConfig.smbWorkgroup) }
    var smbUsername by remember(extendedConfig.smbUsername) { mutableStateOf(extendedConfig.smbUsername) }
    var smbPassword by remember(extendedConfig.smbPassword) { mutableStateOf(extendedConfig.smbPassword) }
    var isSmbPassVisible by remember { mutableStateOf(false) }

    // S3 fields
    var s3Endpoint by remember(cloudConfig.s3Endpoint) { mutableStateOf(cloudConfig.s3Endpoint) }
    var s3Bucket by remember(cloudConfig.s3Bucket) { mutableStateOf(cloudConfig.s3Bucket) }
    var s3Region by remember(cloudConfig.s3Region) { mutableStateOf(cloudConfig.s3Region) }
    var s3AccessKeyId by remember(cloudConfig.s3AccessKeyId) { mutableStateOf(cloudConfig.s3AccessKeyId) }
    var s3SecretAccessKey by remember(cloudConfig.s3SecretAccessKey) { mutableStateOf(cloudConfig.s3SecretAccessKey) }
    var isS3SecretVisible by remember { mutableStateOf(false) }

    // WebDAV fields
    var webdavUrl by remember(cloudConfig.endpointUrl) { mutableStateOf(cloudConfig.endpointUrl) }
    var webdavUsername by remember(cloudConfig.webdavUsername) { mutableStateOf(cloudConfig.webdavUsername) }
    var webdavPassword by remember(cloudConfig.webdavPassword) { mutableStateOf(cloudConfig.webdavPassword) }
    var isWebdavPasswordVisible by remember { mutableStateOf(false) }

    // Google Drive fields
    var gdriveFolder by remember(cloudConfig.bucketOrFolderName) { mutableStateOf(cloudConfig.bucketOrFolderName) }
    var gdriveToken by remember(cloudConfig.apiKeyOrToken) { mutableStateOf(cloudConfig.apiKeyOrToken) }
    var isGdriveTokenVisible by remember { mutableStateOf(false) }

    // NAS (FTP / SFTP / HTTP Gateway) fields
    var nasSubProtocol by remember(extendedConfig.nasSubProtocol) { mutableStateOf(extendedConfig.nasSubProtocol) }
    var nasHost by remember(extendedConfig.nasHost) { mutableStateOf(extendedConfig.nasHost) }
    var nasPortText by remember(extendedConfig.nasPort) { mutableStateOf(extendedConfig.nasPort.toString()) }
    var nasRemotePath by remember(extendedConfig.nasRemotePath) { mutableStateOf(extendedConfig.nasRemotePath) }
    var nasUrl by remember(cloudConfig.endpointUrl) { mutableStateOf(cloudConfig.endpointUrl) }
    var nasUsername by remember(cloudConfig.nasUsername) { mutableStateOf(cloudConfig.nasUsername) }
    var nasPassword by remember(cloudConfig.nasPassword) { mutableStateOf(cloudConfig.nasPassword) }
    var isNasPasswordVisible by remember { mutableStateOf(false) }

    // Webhook fields
    var webhookUrl by remember(cloudConfig.endpointUrl) { mutableStateOf(cloudConfig.endpointUrl) }
    var webhookHeaderKey by remember(cloudConfig.customHeaderName) { mutableStateOf(cloudConfig.customHeaderName) }
    var webhookHeaderValue by remember(cloudConfig.customHeaderValue) { mutableStateOf(cloudConfig.customHeaderValue) }
    var isWebhookSecretVisible by remember { mutableStateOf(false) }

    // Extended Storage Choices & Policies
    var folderLayout by remember(extendedConfig.folderLayout) { mutableStateOf(extendedConfig.folderLayout) }
    var networkPolicy by remember(extendedConfig.networkPolicy) { mutableStateOf(extendedConfig.networkPolicy) }
    var minFreeSpaceText by remember(extendedConfig.minFreeSpaceMb) { mutableStateOf(extendedConfig.minFreeSpaceMb.toString()) }
    var retriesText by remember(extendedConfig.uploadRetries) { mutableStateOf(extendedConfig.uploadRetries.toString()) }
    var deleteAfterUpload by remember(extendedConfig.deleteLocalAfterUpload) { mutableStateOf(extendedConfig.deleteLocalAfterUpload) }

    var autoSyncRecordings by remember(cloudConfig.autoSyncRecordings) { mutableStateOf(cloudConfig.autoSyncRecordings) }
    var autoSyncMotion by remember(cloudConfig.autoSyncMotionSnapshots) { mutableStateOf(cloudConfig.autoSyncMotionSnapshots) }
    var wifiOnly by remember(cloudConfig.wifiOnly) { mutableStateOf(cloudConfig.wifiOnly) }

    var saveConfirmationMessage by remember { mutableStateOf<String?>(null) }
    var optionsSavedMessage by remember { mutableStateOf<String?>(null) }

    val buildDraftCloudAndExtended: () -> Pair<CloudConfigEntity, ExtendedStorageConfig> = {
        val safeSmbPort = (smbPortText.toIntOrNull() ?: 445).coerceIn(1, 65535)
        val safeNasPort = (nasPortText.toIntOrNull() ?: if (nasSubProtocol == NasSubProtocol.SFTP_PROBE) 22 else 21)
            .coerceIn(1, 65535)
        val ext = ExtendedStorageConfig(
            smbHost = smbHost.trim(),
            smbPort = safeSmbPort,
            smbShare = smbShare.trim().ifBlank { "recordings" },
            smbSubfolder = smbSubfolder.trim().ifBlank { "OmniView" },
            smbWorkgroup = smbWorkgroup.trim().ifBlank { "WORKGROUP" },
            smbUsername = smbUsername.trim(),
            smbPassword = smbPassword,
            nasSubProtocol = nasSubProtocol,
            nasHost = nasHost.trim(),
            nasPort = safeNasPort,
            nasRemotePath = nasRemotePath.trim().ifBlank { "/OmniView" },
            folderLayout = folderLayout,
            networkPolicy = networkPolicy,
            minFreeSpaceMb = (minFreeSpaceText.toIntOrNull() ?: 512).coerceIn(64, 102400),
            deleteLocalAfterUpload = deleteAfterUpload,
            uploadRetries = (retriesText.toIntOrNull() ?: 2).coerceIn(0, 5)
        )
        val cloud = cloudConfig.copy(
            provider = selectedProvider,
            endpointUrl = when (selectedProvider) {
                CloudProviderType.SMB_CIFS -> "smb://${smbHost.trim()}:$safeSmbPort/${smbShare.trim()}"
                CloudProviderType.AWS_S3_COMPATIBLE -> s3Endpoint.trim()
                CloudProviderType.WEBDAV -> webdavUrl.trim()
                CloudProviderType.GOOGLE_DRIVE -> "https://www.googleapis.com/drive/v3"
                CloudProviderType.FTP_NAS_STORAGE -> if (nasSubProtocol == NasSubProtocol.HTTP_GATEWAY) {
                    nasUrl.trim()
                } else {
                    "ftp://${nasHost.trim()}:$safeNasPort/${nasRemotePath.trim('/')}"
                }
                CloudProviderType.WEBHOOK_REST_API -> webhookUrl.trim()
            },
            bucketOrFolderName = when (selectedProvider) {
                CloudProviderType.SMB_CIFS -> smbShare.trim().ifBlank { "recordings" }
                CloudProviderType.AWS_S3_COMPATIBLE -> s3Bucket.trim()
                CloudProviderType.GOOGLE_DRIVE -> gdriveFolder.trim()
                else -> "surveillance"
            },
            s3Endpoint = s3Endpoint.trim(),
            s3Bucket = s3Bucket.trim(),
            s3Region = s3Region.trim(),
            s3AccessKeyId = s3AccessKeyId.trim(),
            s3SecretAccessKey = s3SecretAccessKey,
            webdavUsername = webdavUsername.trim(),
            webdavPassword = webdavPassword,
            nasUsername = nasUsername.trim(),
            nasPassword = nasPassword,
            customHeaderName = webhookHeaderKey.trim(),
            customHeaderValue = webhookHeaderValue,
            apiKeyOrToken = when (selectedProvider) {
                CloudProviderType.GOOGLE_DRIVE -> gdriveToken
                CloudProviderType.WEBHOOK_REST_API -> webhookHeaderValue
                else -> ""
            },
            autoSyncRecordings = autoSyncRecordings,
            autoSyncMotionSnapshots = autoSyncMotion,
            wifiOnly = wifiOnly
        )
        cloud to ext
    }

    // 1. Storage Protocol Selector
    Card(
        colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.cloud_protocol_title), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = selectedProvider == CloudProviderType.SMB_CIFS,
                    onClick = { selectedProvider = CloudProviderType.SMB_CIFS },
                    label = { Text(stringResource(R.string.cloud_provider_smb), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                    modifier = Modifier.testTag("chip_provider_smb")
                )
                FilterChip(
                    selected = selectedProvider == CloudProviderType.FTP_NAS_STORAGE,
                    onClick = { selectedProvider = CloudProviderType.FTP_NAS_STORAGE },
                    label = { Text(stringResource(R.string.cloud_provider_nas), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                    modifier = Modifier.testTag("chip_provider_nas")
                )
                FilterChip(
                    selected = selectedProvider == CloudProviderType.WEBDAV,
                    onClick = { selectedProvider = CloudProviderType.WEBDAV },
                    label = { Text(stringResource(R.string.cloud_provider_webdav), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                    modifier = Modifier.testTag("chip_provider_webdav")
                )
                FilterChip(
                    selected = selectedProvider == CloudProviderType.AWS_S3_COMPATIBLE,
                    onClick = { selectedProvider = CloudProviderType.AWS_S3_COMPATIBLE },
                    label = { Text(stringResource(R.string.cloud_provider_s3), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                    modifier = Modifier.testTag("chip_provider_s3")
                )
                FilterChip(
                    selected = selectedProvider == CloudProviderType.GOOGLE_DRIVE,
                    onClick = { selectedProvider = CloudProviderType.GOOGLE_DRIVE },
                    label = { Text(stringResource(R.string.cloud_provider_gdrive), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                    modifier = Modifier.testTag("chip_provider_gdrive")
                )
                FilterChip(
                    selected = selectedProvider == CloudProviderType.WEBHOOK_REST_API,
                    onClick = { selectedProvider = CloudProviderType.WEBHOOK_REST_API },
                    label = { Text(stringResource(R.string.cloud_provider_webhook), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                    modifier = Modifier.testTag("chip_provider_webhook")
                )
            }
        }
    }

    // 2. Auto-Find Storage Shares & Servers Card (beside Manual Setup)
    Card(
        colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("remote_storage_discovery_card")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Radar, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.storage_autodiscover_title), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.storage_autodiscover_sub), color = Color.Gray, fontSize = 11.sp)
                    }
                }
                OutlinedButton(
                    onClick = onScanNetwork,
                    enabled = !isScanningStorage,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("scan_network_storage_btn")
                ) {
                    if (isScanningStorage) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = NeonCyan, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.storage_scanning_btn), fontSize = 11.sp, maxLines = 1, softWrap = false)
                    } else {
                        Text(stringResource(R.string.storage_scan_btn), fontSize = 11.sp, color = NeonCyan, maxLines = 1, softWrap = false)
                    }
                }
            }

            if (discoveredEndpoints.isEmpty()) {
                Text(
                    text = stringResource(R.string.storage_discovered_empty),
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 11.sp
                )
            } else {
                Text(
                    text = stringResource(R.string.storage_discovered_tap_hint),
                    color = NeonGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    discoveredEndpoints.forEachIndexed { idx, ep ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SurveillanceSurface,
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedProvider = ep.serviceType
                                    when (ep.serviceType) {
                                        CloudProviderType.SMB_CIFS -> {
                                            smbHost = ep.host
                                            smbPortText = ep.port.toString()
                                        }
                                        CloudProviderType.FTP_NAS_STORAGE -> {
                                            nasSubProtocol = ep.subProtocol ?: NasSubProtocol.FTP
                                            nasHost = ep.host
                                            nasPortText = ep.port.toString()
                                        }
                                        CloudProviderType.WEBDAV -> {
                                            val scheme = if (ep.port == 5006 || ep.port == 443) "https" else "http"
                                            webdavUrl = "$scheme://${ep.host}:${ep.port}/"
                                        }
                                        CloudProviderType.AWS_S3_COMPATIBLE -> {
                                            s3Endpoint = "http://${ep.host}:${ep.port}"
                                        }
                                        else -> Unit
                                    }
                                    saveConfirmationMessage = null
                                }
                                .testTag("discovered_storage_item_$idx")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${ep.host}:${ep.port} • ${ep.label}",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${ep.interfaceHint} • ${ep.latencyMs}ms",
                                        color = NeonCyan,
                                        fontSize = 10.sp
                                    )
                                }
                                Icon(Icons.Default.Storage, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // 3. Manual Provider Credentials & Connection Test Card
    Card(
        colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (selectedProvider) {
                        CloudProviderType.SMB_CIFS -> stringResource(R.string.cloud_creds_smb)
                        CloudProviderType.AWS_S3_COMPATIBLE -> stringResource(R.string.cloud_creds_s3)
                        CloudProviderType.WEBDAV -> stringResource(R.string.cloud_creds_webdav)
                        CloudProviderType.GOOGLE_DRIVE -> stringResource(R.string.cloud_creds_gdrive)
                        CloudProviderType.FTP_NAS_STORAGE -> stringResource(R.string.cloud_creds_nas)
                        CloudProviderType.WEBHOOK_REST_API -> stringResource(R.string.cloud_creds_webhook)
                    },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            when (selectedProvider) {
                CloudProviderType.SMB_CIFS -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = smbHost,
                            onValueChange = { smbHost = it },
                            label = { Text(stringResource(R.string.storage_smb_host_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            placeholder = { Text("192.168.1.50") },
                            singleLine = true,
                            modifier = Modifier.weight(1.6f).testTag("smb_host_input")
                        )
                        OutlinedTextField(
                            value = smbPortText,
                            onValueChange = { smbPortText = it.filter { ch -> ch.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.storage_smb_port_label), maxLines = 1) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.75f).testTag("smb_port_input")
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = smbShare,
                            onValueChange = { smbShare = it },
                            label = { Text(stringResource(R.string.storage_smb_share_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("smb_share_input")
                        )
                        OutlinedTextField(
                            value = smbSubfolder,
                            onValueChange = { smbSubfolder = it },
                            label = { Text(stringResource(R.string.storage_smb_subfolder_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("smb_subfolder_input")
                        )
                    }

                    OutlinedTextField(
                        value = smbWorkgroup,
                        onValueChange = { smbWorkgroup = it },
                        label = { Text(stringResource(R.string.storage_smb_workgroup_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("smb_workgroup_input")
                    )

                    OutlinedTextField(
                        value = smbUsername,
                        onValueChange = { smbUsername = it },
                        label = { Text(stringResource(R.string.storage_smb_user_label)) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = NeonCyan) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("smb_username_input")
                    )

                    OutlinedTextField(
                        value = smbPassword,
                        onValueChange = { smbPassword = it },
                        label = { Text(stringResource(R.string.storage_smb_pass_label)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = NeonCyan) },
                        trailingIcon = {
                            IconButton(onClick = { isSmbPassVisible = !isSmbPassVisible }) {
                                Icon(
                                    imageVector = if (isSmbPassVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (isSmbPassVisible) {
                                        stringResource(R.string.cloud_hide_secret)
                                    } else {
                                        stringResource(R.string.cloud_show_secret)
                                    },
                                    tint = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        },
                        visualTransformation = if (isSmbPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("smb_password_input")
                    )

                    OutlinedButton(
                        onClick = onLaunchSafFolderPicker,
                        modifier = Modifier.fillMaxWidth().testTag("smb_attach_saf_btn")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.storage_smb_attach_saf_btn), fontSize = 11.sp, color = NeonCyan)
                    }

                    if (cloudConfig.safFolderDisplayName.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.storage_smb_saf_attached, cloudConfig.safFolderDisplayName),
                            color = NeonGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                CloudProviderType.FTP_NAS_STORAGE -> {
                    Text(stringResource(R.string.storage_nas_proto_label), color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = nasSubProtocol == NasSubProtocol.FTP,
                            onClick = {
                                nasSubProtocol = NasSubProtocol.FTP
                                if (nasPortText == "22" || nasPortText == "5000") nasPortText = "21"
                            },
                            label = { Text(stringResource(R.string.storage_nas_proto_ftp), fontSize = 11.sp) },
                            modifier = Modifier.testTag("chip_nas_ftp")
                        )
                        FilterChip(
                            selected = nasSubProtocol == NasSubProtocol.SFTP_PROBE,
                            onClick = {
                                nasSubProtocol = NasSubProtocol.SFTP_PROBE
                                if (nasPortText == "21" || nasPortText == "5000") nasPortText = "22"
                            },
                            label = { Text(stringResource(R.string.storage_nas_proto_sftp), fontSize = 11.sp) },
                            modifier = Modifier.testTag("chip_nas_sftp")
                        )
                        FilterChip(
                            selected = nasSubProtocol == NasSubProtocol.HTTP_GATEWAY,
                            onClick = { nasSubProtocol = NasSubProtocol.HTTP_GATEWAY },
                            label = { Text(stringResource(R.string.storage_nas_proto_http), fontSize = 11.sp) },
                            modifier = Modifier.testTag("chip_nas_http")
                        )
                    }

                    if (nasSubProtocol == NasSubProtocol.HTTP_GATEWAY) {
                        OutlinedTextField(
                            value = nasUrl,
                            onValueChange = { nasUrl = it },
                            label = { Text(stringResource(R.string.cloud_nas_url)) },
                            placeholder = { Text("http://192.168.1.50:5000/api/upload") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("nas_url_input")
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = nasHost,
                                onValueChange = { nasHost = it },
                                label = { Text(stringResource(R.string.storage_nas_host_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                placeholder = { Text("192.168.1.50") },
                                singleLine = true,
                                modifier = Modifier.weight(1.6f).testTag("nas_host_input")
                            )
                            OutlinedTextField(
                                value = nasPortText,
                                onValueChange = { nasPortText = it.filter { ch -> ch.isDigit() }.take(5) },
                                label = { Text(stringResource(R.string.storage_nas_port_label), maxLines = 1) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(0.75f).testTag("nas_port_input")
                            )
                        }
                        OutlinedTextField(
                            value = nasRemotePath,
                            onValueChange = { nasRemotePath = it },
                            label = { Text(stringResource(R.string.storage_nas_path_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("nas_remote_path_input")
                        )
                    }

                    OutlinedTextField(
                        value = nasUsername,
                        onValueChange = { nasUsername = it },
                        label = { Text(stringResource(R.string.cloud_nas_user)) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = NeonCyan) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("nas_username_input")
                    )

                    OutlinedTextField(
                        value = nasPassword,
                        onValueChange = { nasPassword = it },
                        label = { Text(stringResource(R.string.cloud_nas_pass)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = NeonCyan) },
                        trailingIcon = {
                            IconButton(onClick = { isNasPasswordVisible = !isNasPasswordVisible }) {
                                Icon(
                                    imageVector = if (isNasPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (isNasPasswordVisible) {
                                        stringResource(R.string.cloud_hide_secret)
                                    } else {
                                        stringResource(R.string.cloud_show_secret)
                                    },
                                    tint = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        },
                        visualTransformation = if (isNasPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("nas_password_input")
                    )
                }

                CloudProviderType.AWS_S3_COMPATIBLE -> {
                    OutlinedTextField(
                        value = s3Endpoint,
                        onValueChange = { s3Endpoint = it },
                        label = { Text(stringResource(R.string.cloud_s3_endpoint)) },
                        placeholder = { Text("https://s3.us-east-1.amazonaws.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("s3_endpoint_input")
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = s3Bucket,
                            onValueChange = { s3Bucket = it },
                            label = { Text(stringResource(R.string.cloud_s3_bucket)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("s3_bucket_input")
                        )
                        OutlinedTextField(
                            value = s3Region,
                            onValueChange = { s3Region = it },
                            label = { Text(stringResource(R.string.cloud_s3_region)) },
                            placeholder = { Text("us-east-1") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("s3_region_input")
                        )
                    }

                    OutlinedTextField(
                        value = s3AccessKeyId,
                        onValueChange = { s3AccessKeyId = it },
                        label = { Text(stringResource(R.string.cloud_s3_access_key)) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = NeonCyan) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("s3_access_key_input")
                    )

                    OutlinedTextField(
                        value = s3SecretAccessKey,
                        onValueChange = { s3SecretAccessKey = it },
                        label = { Text(stringResource(R.string.cloud_s3_secret_key)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = NeonCyan) },
                        trailingIcon = {
                            IconButton(onClick = { isS3SecretVisible = !isS3SecretVisible }) {
                                Icon(
                                    imageVector = if (isS3SecretVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (isS3SecretVisible) {
                                        stringResource(R.string.cloud_hide_secret)
                                    } else {
                                        stringResource(R.string.cloud_show_secret)
                                    },
                                    tint = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        },
                        visualTransformation = if (isS3SecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("s3_secret_key_input")
                    )
                }

                CloudProviderType.WEBDAV -> {
                    OutlinedTextField(
                        value = webdavUrl,
                        onValueChange = { webdavUrl = it },
                        label = { Text(stringResource(R.string.cloud_webdav_url)) },
                        placeholder = { Text("https://nextcloud.example.com/remote.php/dav/files/user/cctv") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("webdav_url_input")
                    )

                    OutlinedTextField(
                        value = webdavUsername,
                        onValueChange = { webdavUsername = it },
                        label = { Text(stringResource(R.string.cloud_webdav_user)) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = NeonCyan) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("webdav_username_input")
                    )

                    OutlinedTextField(
                        value = webdavPassword,
                        onValueChange = { webdavPassword = it },
                        label = { Text(stringResource(R.string.cloud_webdav_pass)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = NeonCyan) },
                        trailingIcon = {
                            IconButton(onClick = { isWebdavPasswordVisible = !isWebdavPasswordVisible }) {
                                Icon(
                                    imageVector = if (isWebdavPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (isWebdavPasswordVisible) {
                                        stringResource(R.string.cloud_hide_secret)
                                    } else {
                                        stringResource(R.string.cloud_show_secret)
                                    },
                                    tint = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        },
                        visualTransformation = if (isWebdavPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("webdav_password_input")
                    )
                }

                CloudProviderType.GOOGLE_DRIVE -> {
                    OutlinedTextField(
                        value = gdriveFolder,
                        onValueChange = { gdriveFolder = it },
                        label = { Text(stringResource(R.string.cloud_gdrive_folder)) },
                        placeholder = { Text("OmniView-Surveillance-Backup") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("gdrive_folder_input")
                    )

                    OutlinedTextField(
                        value = gdriveToken,
                        onValueChange = { gdriveToken = it },
                        label = { Text(stringResource(R.string.cloud_gdrive_token)) },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = NeonCyan) },
                        trailingIcon = {
                            IconButton(onClick = { isGdriveTokenVisible = !isGdriveTokenVisible }) {
                                Icon(
                                    imageVector = if (isGdriveTokenVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (isGdriveTokenVisible) {
                                        stringResource(R.string.cloud_hide_secret)
                                    } else {
                                        stringResource(R.string.cloud_show_secret)
                                    },
                                    tint = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        },
                        visualTransformation = if (isGdriveTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("gdrive_token_input")
                    )
                }

                CloudProviderType.WEBHOOK_REST_API -> {
                    OutlinedTextField(
                        value = webhookUrl,
                        onValueChange = { webhookUrl = it },
                        label = { Text(stringResource(R.string.cloud_webhook_url)) },
                        placeholder = { Text("https://api.mycloud.com/surveillance/webhook") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("webhook_url_input")
                    )

                    OutlinedTextField(
                        value = webhookHeaderKey,
                        onValueChange = { webhookHeaderKey = it },
                        label = { Text(stringResource(R.string.cloud_webhook_header_key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("webhook_header_key_input")
                    )

                    OutlinedTextField(
                        value = webhookHeaderValue,
                        onValueChange = { webhookHeaderValue = it },
                        label = { Text(stringResource(R.string.cloud_webhook_header_val)) },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = NeonCyan) },
                        trailingIcon = {
                            IconButton(onClick = { isWebhookSecretVisible = !isWebhookSecretVisible }) {
                                Icon(
                                    imageVector = if (isWebhookSecretVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (isWebhookSecretVisible) {
                                        stringResource(R.string.cloud_hide_secret)
                                    } else {
                                        stringResource(R.string.cloud_show_secret)
                                    },
                                    tint = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        },
                        visualTransformation = if (isWebhookSecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("webhook_secret_input")
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        saveConfirmationMessage = null
                        val (draftCloud, draftExt) = buildDraftCloudAndExtended()
                        onTestConnection(draftCloud, draftExt)
                    },
                    enabled = !isTesting,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("test_cloud_connection_btn")
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NeonCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.cloud_testing_btn), fontSize = 12.sp, maxLines = 1, softWrap = false)
                    } else {
                        Icon(Icons.Default.ElectricalServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.cloud_test_btn), fontSize = 12.sp, color = NeonCyan, maxLines = 1, softWrap = false)
                    }
                }

                Button(
                    onClick = {
                        val (draftCloud, draftExt) = buildDraftCloudAndExtended()
                        onSaveCredentials(draftCloud, draftExt)
                        saveConfirmationMessage = AppLocaleManager.getString(context, R.string.cloud_saved_msg, selectedProvider.name)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("save_cloud_config_btn")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.cloud_save_creds_btn), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }

            saveConfirmationMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = NeonGreen.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, NeonGreen),
                    modifier = Modifier.fillMaxWidth().testTag("save_confirmation_badge")
                ) {
                    Text(
                        text = "✓ $msg",
                        color = NeonGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Detailed or Legacy Connection Test Banner
            val handshakePrefix = stringResource(R.string.cloud_handshake_ok, 0, "", 0, "").substringBefore(":")
            when {
                detailedTestResult != null -> {
                    val statusColor = if (detailedTestResult.success) NeonGreen else NeonRed
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, statusColor),
                        modifier = Modifier.fillMaxWidth().testTag("handshake_result_banner")
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "[${detailedTestResult.protocolLabel}] ${detailedTestResult.summary}",
                                color = statusColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = detailedTestResult.details,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                testResult != null -> {
                    val isSuccess = testResult.startsWith("Handshake OK") || testResult.startsWith(handshakePrefix)
                    val statusColor = if (isSuccess) NeonGreen else NeonRed
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, statusColor),
                        modifier = Modifier.fillMaxWidth().testTag("handshake_result_banner")
                    ) {
                        Text(
                            text = testResult,
                            color = statusColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }

    // 4. Extended Storage Choices & Sync Policies Card
    Card(
        colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.storage_options_title), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            // Remote Folder Organization
            Text(stringResource(R.string.storage_layout_label), color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    RemoteFolderLayout.FLAT to stringResource(R.string.storage_layout_flat),
                    RemoteFolderLayout.BY_CAMERA to stringResource(R.string.storage_layout_by_cam),
                    RemoteFolderLayout.BY_CAMERA_AND_DATE to stringResource(R.string.storage_layout_by_cam_date)
                ).forEach { (layout, label) ->
                    FilterChip(
                        selected = folderLayout == layout,
                        onClick = { folderLayout = layout },
                        label = { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("chip_layout_${layout.name}")
                    )
                }
            }

            // Allowed Sync Network Policy
            Text(stringResource(R.string.storage_net_policy_label), color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    SyncNetworkPolicy.WIFI_AND_VPN to stringResource(R.string.storage_net_wifi_vpn),
                    SyncNetworkPolicy.WIFI_ONLY to stringResource(R.string.storage_net_wifi_only),
                    SyncNetworkPolicy.ALL_NETWORKS to stringResource(R.string.storage_net_all)
                ).forEach { (policy, label) ->
                    FilterChip(
                        selected = networkPolicy == policy,
                        onClick = { networkPolicy = policy },
                        label = { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("chip_net_${policy.name}")
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minFreeSpaceText,
                    onValueChange = { minFreeSpaceText = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text(stringResource(R.string.storage_min_free_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("storage_min_free_input")
                )
                OutlinedTextField(
                    value = retriesText,
                    onValueChange = { retriesText = it.filter { ch -> ch.isDigit() }.take(1) },
                    label = { Text(stringResource(R.string.storage_retries_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("storage_retries_input")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.storage_delete_after_upload), color = Color.White, fontSize = 13.sp)
                    Text(stringResource(R.string.storage_delete_after_upload_sub), color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = deleteAfterUpload,
                    onCheckedChange = { deleteAfterUpload = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan),
                    modifier = Modifier.testTag("storage_delete_after_upload_switch")
                )
            }

            Button(
                onClick = {
                    val (_, draftExt) = buildDraftCloudAndExtended()
                    onSaveExtendedOptions(draftExt)
                    optionsSavedMessage = AppLocaleManager.getString(context, R.string.storage_options_saved_msg)
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier.testTag("storage_save_options_btn")
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.storage_save_options_btn), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            optionsSavedMessage?.let { msg ->
                Text(text = "✓ $msg", color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            Text(stringResource(R.string.cloud_policies_title), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.cloud_auto_upload_clips), color = Color.White, fontSize = 13.sp)
                    Text(stringResource(R.string.cloud_auto_upload_clips_sub), color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = autoSyncRecordings,
                    onCheckedChange = {
                        autoSyncRecordings = it
                        onSaveSyncPolicies(cloudConfig.copy(autoSyncRecordings = it))
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.cloud_auto_upload_motion), color = Color.White, fontSize = 13.sp)
                    Text(stringResource(R.string.cloud_auto_upload_motion_sub), color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = autoSyncMotion,
                    onCheckedChange = {
                        autoSyncMotion = it
                        onSaveSyncPolicies(cloudConfig.copy(autoSyncMotionSnapshots = it))
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.cloud_wifi_only), color = Color.White, fontSize = 13.sp)
                    Text(stringResource(R.string.cloud_wifi_only_sub), color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = {
                        wifiOnly = it
                        onSaveSyncPolicies(cloudConfig.copy(wifiOnly = it))
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                )
            }
        }
    }
}
