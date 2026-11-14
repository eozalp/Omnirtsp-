package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.CloudConfigEntity
import com.example.data.model.CloudProviderType
import com.example.data.model.LocalSaveDestination
import com.example.engine.EndpointKind
import com.example.i18n.AppLocaleManager
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceDarkBg
import com.example.ui.theme.SurveillanceSurface
import com.example.ui.viewmodel.CameraViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudStorageScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cloudConfig by viewModel.cloudConfig.collectAsState()
    val localExportStatus by viewModel.localExportStatus.collectAsState()
    val retentionStatus by viewModel.retentionStatus.collectAsState()
    val selectedLanguageTag by viewModel.selectedLanguageTag.collectAsState()
    val isGlobalMuted by viewModel.isGlobalMuted.collectAsState()
    val isTesting by viewModel.cloudStorageManager.isTestingConnection.collectAsState()
    val testResult by viewModel.cloudStorageManager.lastConnectionTestResult.collectAsState()
    val hubState by viewModel.hubServerState.collectAsState()

    var hubPortText by remember(hubState.port) { mutableStateOf(hubState.port.toString()) }
    var hubTokenText by remember(hubState.accessToken) { mutableStateOf(hubState.accessToken) }
    var isHubTokenVisible by remember { mutableStateOf(false) }
    var hubCopyMessage by remember { mutableStateOf<String?>(null) }

    val copyToClipboard: (String, String) -> Unit = { label, text ->
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            hubCopyMessage = AppLocaleManager.getString(context, R.string.hub_copied_status, text)
        } catch (_: Exception) {}
    }

    var retentionEnabled by remember(cloudConfig) { mutableStateOf(cloudConfig.retentionEnabled) }
    var retentionDaysText by remember(cloudConfig) { mutableStateOf(cloudConfig.retentionDays.toString()) }
    var retentionHoursText by remember(cloudConfig) { mutableStateOf(cloudConfig.retentionHours.toString()) }
    var retentionMinutesText by remember(cloudConfig) { mutableStateOf(cloudConfig.retentionMinutes.toString()) }

    var chunkHoursText by remember(cloudConfig) { mutableStateOf(cloudConfig.chunkDurationHours.toString()) }
    var chunkMinutesText by remember(cloudConfig) { mutableStateOf(cloudConfig.chunkDurationMinutes.toString()) }
    var keepAliveEnabled by remember(cloudConfig) { mutableStateOf(cloudConfig.keepAppAlive) }
    var isBatteryUnrestricted by remember { mutableStateOf(viewModel.isBatteryOptimizationIgnored()) }

    var pickerErrorMessage by remember { mutableStateOf<String?>(null) }
    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            pickerErrorMessage = null
            viewModel.selectSafFolder(treeUri)
        }
    }
    val launchSystemFolderPicker = {
        try {
            openFolderLauncher.launch(null)
        } catch (_: Exception) {
            pickerErrorMessage = AppLocaleManager.getString(context, R.string.storage_picker_unavailable)
        }
    }

    var selectedProvider by remember(cloudConfig) { mutableStateOf(cloudConfig.provider) }

    // S3 credentials
    var s3Endpoint by remember(cloudConfig) { mutableStateOf(cloudConfig.s3Endpoint) }
    var s3Bucket by remember(cloudConfig) { mutableStateOf(cloudConfig.s3Bucket) }
    var s3Region by remember(cloudConfig) { mutableStateOf(cloudConfig.s3Region) }
    var s3AccessKeyId by remember(cloudConfig) { mutableStateOf(cloudConfig.s3AccessKeyId) }
    var s3SecretAccessKey by remember(cloudConfig) { mutableStateOf(cloudConfig.s3SecretAccessKey) }
    var isS3SecretVisible by remember { mutableStateOf(false) }

    // WebDAV credentials
    var webdavUrl by remember(cloudConfig) { mutableStateOf(cloudConfig.endpointUrl) }
    var webdavUsername by remember(cloudConfig) { mutableStateOf(cloudConfig.webdavUsername) }
    var webdavPassword by remember(cloudConfig) { mutableStateOf(cloudConfig.webdavPassword) }
    var isWebdavPasswordVisible by remember { mutableStateOf(false) }

    // Google Drive credentials
    var gdriveFolder by remember(cloudConfig) { mutableStateOf(cloudConfig.bucketOrFolderName) }
    var gdriveToken by remember(cloudConfig) { mutableStateOf(cloudConfig.apiKeyOrToken) }
    var isGdriveTokenVisible by remember { mutableStateOf(false) }

    // NAS credentials
    var nasUrl by remember(cloudConfig) { mutableStateOf(cloudConfig.endpointUrl) }
    var nasUsername by remember(cloudConfig) { mutableStateOf(cloudConfig.nasUsername) }
    var nasPassword by remember(cloudConfig) { mutableStateOf(cloudConfig.nasPassword) }
    var isNasPasswordVisible by remember { mutableStateOf(false) }

    // Webhook credentials
    var webhookUrl by remember(cloudConfig) { mutableStateOf(cloudConfig.endpointUrl) }
    var webhookHeaderKey by remember(cloudConfig) { mutableStateOf(cloudConfig.customHeaderName) }
    var webhookHeaderValue by remember(cloudConfig) { mutableStateOf(cloudConfig.customHeaderValue) }
    var isWebhookSecretVisible by remember { mutableStateOf(false) }

    // Sync rules
    var autoSyncRecordings by remember(cloudConfig) { mutableStateOf(cloudConfig.autoSyncRecordings) }
    var autoSyncMotion by remember(cloudConfig) { mutableStateOf(cloudConfig.autoSyncMotionSnapshots) }
    var wifiOnly by remember(cloudConfig) { mutableStateOf(cloudConfig.wifiOnly) }

    var saveConfirmationMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurveillanceDarkBg)
            .testTag("cloud_storage_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.storage_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = NeonCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.storage_subtitle),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1E3A2B)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(NeonGreen)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.common_active), color = NeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }

            // App Language Selector Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("language_selector_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.language_title),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.language_subtitle),
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = selectedLanguageTag == AppLocaleManager.LANG_SYSTEM,
                            onClick = { viewModel.setAppLanguage(AppLocaleManager.LANG_SYSTEM) },
                            label = { Text(stringResource(R.string.language_system), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("chip_lang_system")
                        )
                        FilterChip(
                            selected = selectedLanguageTag == AppLocaleManager.LANG_ENGLISH,
                            onClick = { viewModel.setAppLanguage(AppLocaleManager.LANG_ENGLISH) },
                            label = { Text(stringResource(R.string.language_english), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("chip_lang_en")
                        )
                        FilterChip(
                            selected = selectedLanguageTag == AppLocaleManager.LANG_TURKISH,
                            onClick = { viewModel.setAppLanguage(AppLocaleManager.LANG_TURKISH) },
                            label = { Text(stringResource(R.string.language_turkish), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("chip_lang_tr")
                        )
                    }
                }
            }

            // Spare-Device Remote Hub Server Card (Tailscale / LAN IP)
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("remote_hub_server_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Router, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.hub_title),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.hub_subtitle),
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (hubState.isRunning) NeonGreen.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f)
                        ) {
                            Text(
                                text = if (hubState.isRunning) {
                                    stringResource(R.string.hub_status_online, hubState.port, hubState.activeConnections)
                                } else {
                                    stringResource(R.string.hub_status_offline)
                                },
                                color = if (hubState.isRunning) NeonGreen else Color.Gray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(stringResource(R.string.hub_switch_title), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.hub_switch_sub), color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = hubState.isEnabled,
                            onCheckedChange = { viewModel.toggleHubServer(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan),
                            modifier = Modifier.testTag("hub_server_switch")
                        )
                    }

                    // Power & Storage Eco-Hub Telemetry
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SurveillanceSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.BatteryChargingFull,
                                    contentDescription = null,
                                    tint = if (hubState.isPluggedIn) NeonGreen else NeonAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.hub_power_telemetry,
                                        hubState.batteryPercent,
                                        if (hubState.isPluggedIn) {
                                            stringResource(R.string.hub_power_plugged)
                                        } else {
                                            stringResource(R.string.hub_power_battery)
                                        },
                                        hubState.freeStorageMb
                                    ),
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 11.sp
                                )
                            }
                            IconButton(
                                onClick = { viewModel.refreshHubNetworkEndpoints() },
                                modifier = Modifier.size(32.dp).testTag("hub_refresh_ips_btn")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(17.dp))
                            }
                        }
                    }

                    // Detected Tailscale & LAN URLs
                    Text(
                        text = stringResource(R.string.hub_endpoints_title),
                        color = NeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (hubState.endpoints.isEmpty()) {
                        Text(
                            text = stringResource(R.string.hub_no_ip_detected),
                            color = NeonAmber,
                            fontSize = 11.sp
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            hubState.endpoints.forEach { ep ->
                                val badgeColor = when (ep.kind) {
                                    EndpointKind.TAILSCALE -> NeonCyan
                                    EndpointKind.WIFI_LAN -> NeonGreen
                                    EndpointKind.OTHER -> Color.LightGray
                                }
                                val kindLabel = when (ep.kind) {
                                    EndpointKind.TAILSCALE -> stringResource(R.string.hub_kind_tailscale)
                                    EndpointKind.WIFI_LAN -> stringResource(R.string.hub_kind_lan)
                                    EndpointKind.OTHER -> stringResource(R.string.hub_kind_other)
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = SurveillanceSurface,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (ep.kind == EndpointKind.TAILSCALE) Icons.Default.VpnLock else Icons.Default.Wifi,
                                                contentDescription = null,
                                                tint = badgeColor,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = "$kindLabel (${ep.interfaceName})",
                                                    color = badgeColor,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = ep.url,
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = { copyToClipboard("OmniView Hub URL", ep.url) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier.testTag("copy_hub_url_${ep.kind.name}")
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.hub_copy_url), fontSize = 11.sp, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (hubState.tailscaleEndpoint == null) {
                        Text(
                            text = stringResource(R.string.hub_no_tailscale_hint),
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = hubPortText,
                            onValueChange = { hubPortText = it.filter { ch -> ch.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.hub_port_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.9f).testTag("hub_port_input")
                        )

                        OutlinedTextField(
                            value = hubTokenText,
                            onValueChange = { hubTokenText = it.take(48) },
                            label = { Text(stringResource(R.string.hub_token_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { isHubTokenVisible = !isHubTokenVisible }) {
                                    Icon(
                                        imageVector = if (isHubTokenVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = stringResource(R.string.cloud_show_secret),
                                        tint = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            },
                            visualTransformation = if (isHubTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.weight(1.3f).testTag("hub_token_input")
                        )
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                val portVal = hubPortText.toIntOrNull() ?: com.example.engine.RemoteHubServer.DEFAULT_PORT
                                val safePort = com.example.engine.RemoteHubServer.normalizePort(portVal)
                                hubPortText = safePort.toString()
                                viewModel.updateHubServerConfig(safePort, hubTokenText, hubState.allowRemoteControl)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("hub_apply_config_btn")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.hub_apply_settings_btn), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }

                        OutlinedButton(
                            onClick = { copyToClipboard("OmniView Hub Token", hubState.accessToken) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("hub_copy_token_btn")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.hub_copy_token), fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }

                        OutlinedButton(
                            onClick = { viewModel.regenerateHubToken() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("hub_regen_token_btn")
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.hub_regen_token_btn), fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(stringResource(R.string.hub_remote_control_title), color = Color.White, fontSize = 13.sp)
                            Text(stringResource(R.string.hub_remote_control_sub), color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = hubState.allowRemoteControl,
                            onCheckedChange = { viewModel.setRemoteHubControlEnabled(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan),
                            modifier = Modifier.testTag("hub_allow_control_switch")
                        )
                    }

                    if (hubState.lastRequestSummary.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.hub_last_client, hubState.lastRequestSummary),
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    hubCopyMessage?.let { msg ->
                        Text(text = msg, color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }

                    hubState.lastError?.let { err ->
                        Text(
                            text = stringResource(R.string.hub_error_prefix, err),
                            color = NeonRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Local Device & System File Manager Folder Picker Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("local_storage_options_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.storage_local_title),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.storage_local_subtitle),
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = cloudConfig.localSaveDestination == LocalSaveDestination.PUBLIC_MOVIES,
                            onClick = { viewModel.setLocalSaveDestination(LocalSaveDestination.PUBLIC_MOVIES) },
                            label = { Text(stringResource(R.string.storage_chip_movies), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("chip_local_movies")
                        )

                        FilterChip(
                            selected = cloudConfig.localSaveDestination == LocalSaveDestination.CUSTOM_SAF_FOLDER,
                            onClick = {
                                if (cloudConfig.safFolderUri.isBlank()) {
                                    launchSystemFolderPicker()
                                } else {
                                    viewModel.setLocalSaveDestination(LocalSaveDestination.CUSTOM_SAF_FOLDER)
                                }
                            },
                            label = { Text(stringResource(R.string.storage_chip_picked), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("chip_local_custom_saf")
                        )

                        FilterChip(
                            selected = cloudConfig.localSaveDestination == LocalSaveDestination.APP_INTERNAL,
                            onClick = { viewModel.setLocalSaveDestination(LocalSaveDestination.APP_INTERNAL) },
                            label = { Text(stringResource(R.string.storage_chip_internal), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("chip_local_internal")
                        )
                    }

                    val activeDestinationLabel = when (cloudConfig.localSaveDestination) {
                        LocalSaveDestination.CUSTOM_SAF_FOLDER ->
                            if (cloudConfig.safFolderUri.isNotBlank()) {
                                cloudConfig.safFolderDisplayName.ifBlank { stringResource(R.string.recordings_selected_folder) }
                            } else {
                                stringResource(R.string.storage_no_folder_fallback)
                            }
                        LocalSaveDestination.PUBLIC_MOVIES -> stringResource(R.string.storage_gallery_movies)
                        LocalSaveDestination.APP_INTERNAL -> stringResource(R.string.storage_private_internal)
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SurveillanceSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (cloudConfig.localSaveDestination == LocalSaveDestination.CUSTOM_SAF_FOLDER) {
                                    Icons.Default.FolderOpen
                                } else {
                                    Icons.Default.Smartphone
                                },
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.storage_active_location),
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = activeDestinationLabel,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = launchSystemFolderPicker,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier.testTag("choose_folder_btn")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.storage_choose_folder_btn), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }

                        if (cloudConfig.safFolderUri.isNotBlank()) {
                            OutlinedButton(
                                onClick = { viewModel.clearSafFolder() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("reset_folder_btn")
                            ) {
                                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.common_reset), fontSize = 12.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(stringResource(R.string.storage_auto_save_title), color = Color.White, fontSize = 13.sp)
                            Text(stringResource(R.string.storage_auto_save_sub), color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = cloudConfig.autoSaveToDeviceFolder,
                            onCheckedChange = { viewModel.setAutoSaveToDeviceFolder(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan),
                            modifier = Modifier.testTag("auto_save_local_switch")
                        )
                    }

                    val saveFailedPrefix = stringResource(R.string.status_save_failed, "").trimEnd()
                    val folderBanner = pickerErrorMessage ?: localExportStatus
                    folderBanner?.let { statusText ->
                        val isErr = pickerErrorMessage != null ||
                            statusText.startsWith("Save failed") ||
                            statusText.startsWith(saveFailedPrefix)
                        val bannerColor = if (isErr) NeonAmber else NeonGreen
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = bannerColor.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, bannerColor),
                            modifier = Modifier.fillMaxWidth().testTag("local_folder_status_banner")
                        ) {
                            Text(
                                text = statusText,
                                color = bannerColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            // Disaster-Resilient Video Chunk Size Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("chunk_duration_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Timer, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.chunk_title),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.chunk_subtitle),
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    val chunkHourU = stringResource(R.string.retention_unit_hour_short)
                    val chunkMinU = stringResource(R.string.retention_unit_min_short)
                    val activeTotalMins = (cloudConfig.totalChunkMillis() / 60_000L).toInt()
                    val activeH = activeTotalMins / 60
                    val activeM = activeTotalMins % 60
                    val localizedChunkLabel = buildList {
                        if (activeH > 0) add("$activeH$chunkHourU")
                        if (activeM > 0 || activeH == 0) add("$activeM$chunkMinU")
                    }.joinToString(" ")

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SurveillanceSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.chunk_active_window, localizedChunkLabel.uppercase()),
                                color = NeonCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.chunk_resilience_note),
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.chunk_presets_label),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    val chunkPresets = listOf(
                        (0 to 1) to ("1m" to "1$chunkMinU"),
                        (0 to 5) to ("5m" to "5$chunkMinU"),
                        (0 to 15) to ("15m" to "15$chunkMinU"),
                        (0 to 30) to ("30m" to "30$chunkMinU"),
                        (1 to 0) to ("1h" to "1$chunkHourU"),
                        (3 to 0) to ("3h" to "3$chunkHourU")
                    )
                    val curChunkH = chunkHoursText.toIntOrNull() ?: 0
                    val curChunkM = chunkMinutesText.toIntOrNull() ?: 0

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        chunkPresets.forEach { (hm, keyAndLabel) ->
                            val (presetKey, label) = keyAndLabel
                            val isSelected = curChunkH == hm.first && curChunkM == hm.second
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    chunkHoursText = hm.first.toString()
                                    chunkMinutesText = hm.second.toString()
                                    viewModel.updateChunkDuration(hm.first, hm.second)
                                },
                                label = { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("chunk_preset_$presetKey")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = chunkHoursText,
                            onValueChange = { input ->
                                chunkHoursText = input.filter { it.isDigit() }.take(2)
                            },
                            label = { Text(stringResource(R.string.retention_hours_label), maxLines = 1, softWrap = false) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("chunk_hours_input")
                        )
                        OutlinedTextField(
                            value = chunkMinutesText,
                            onValueChange = { input ->
                                chunkMinutesText = input.filter { it.isDigit() }.take(2)
                            },
                            label = { Text(stringResource(R.string.retention_mins_label), maxLines = 1, softWrap = false) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("chunk_minutes_input")
                        )
                    }

                    Button(
                        onClick = {
                            val h = (chunkHoursText.toIntOrNull() ?: 0).coerceIn(0, 24)
                            val m = (chunkMinutesText.toIntOrNull() ?: 0).coerceIn(0, 59)
                            val totalM = (h * 60 + m).coerceIn(1, 24 * 60)
                            val normH = totalM / 60
                            val normM = totalM % 60
                            chunkHoursText = normH.toString()
                            chunkMinutesText = normM.toString()
                            viewModel.updateChunkDuration(normH, normM)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier.testTag("save_chunk_duration_btn")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.chunk_apply_btn), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }

            // Background Anti-Shutdown Protection & Global Audio Sync Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("keep_alive_protection_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.keepalive_title),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.keepalive_subtitle),
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Global Audio Mute Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isGlobalMuted) {
                                    Icons.AutoMirrored.Filled.VolumeOff
                                } else {
                                    Icons.AutoMirrored.Filled.VolumeUp
                                },
                                contentDescription = null,
                                tint = if (isGlobalMuted) NeonRed else NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(stringResource(R.string.sound_global_title), color = Color.White, fontSize = 13.sp)
                                Text(stringResource(R.string.sound_global_sub), color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                        Switch(
                            checked = isGlobalMuted,
                            onCheckedChange = { muted -> viewModel.setGlobalMuted(muted) },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonRed),
                            modifier = Modifier.testTag("global_sound_switch")
                        )
                    }

                    // Foreground Keep-Alive Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(stringResource(R.string.keepalive_switch_title), color = Color.White, fontSize = 13.sp)
                            Text(stringResource(R.string.keepalive_switch_sub), color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = keepAliveEnabled,
                            onCheckedChange = { enabled ->
                                keepAliveEnabled = enabled
                                viewModel.setKeepAppAlive(enabled)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonGreen),
                            modifier = Modifier.testTag("keep_alive_switch")
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SurveillanceSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.BatteryChargingFull,
                                    contentDescription = null,
                                    tint = if (isBatteryUnrestricted) NeonGreen else NeonAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.keepalive_battery_title),
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (isBatteryUnrestricted) {
                                            stringResource(R.string.keepalive_battery_unrestricted)
                                        } else {
                                            stringResource(R.string.keepalive_battery_optimized)
                                        },
                                        color = if (isBatteryUnrestricted) NeonGreen else NeonAmber,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.openBatteryOptimizationSettings()
                                    isBatteryUnrestricted = viewModel.isBatteryOptimizationIgnored()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("battery_optimization_btn")
                            ) {
                                Text(
                                    text = stringResource(R.string.keepalive_battery_btn),
                                    fontSize = 11.sp,
                                    color = NeonCyan,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // Rolling Storage Limit (Continuous Auto-Replace) Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("rolling_retention_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.retention_title),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.retention_subtitle),
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Switch(
                            checked = retentionEnabled,
                            onCheckedChange = { enabled ->
                                retentionEnabled = enabled
                                viewModel.updateRetentionPolicy(
                                    enabled = enabled,
                                    days = retentionDaysText.toIntOrNull() ?: 0,
                                    hours = retentionHoursText.toIntOrNull() ?: 0,
                                    minutes = retentionMinutesText.toIntOrNull() ?: 0
                                )
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan),
                            modifier = Modifier.testTag("auto_replace_switch")
                        )
                    }

                    // Live usage & window badge
                    val dayUnit = stringResource(R.string.retention_unit_day_short)
                    val hourUnit = stringResource(R.string.retention_unit_hour_short)
                    val minUnit = stringResource(R.string.retention_unit_min_short)
                    val localizedWindowLabel = buildList {
                        if (cloudConfig.retentionDays > 0) add("${cloudConfig.retentionDays}$dayUnit")
                        if (cloudConfig.retentionHours > 0) add("${cloudConfig.retentionHours}$hourUnit")
                        if (cloudConfig.retentionMinutes > 0) add("${cloudConfig.retentionMinutes}$minUnit")
                    }.joinToString(" ").ifBlank { stringResource(R.string.retention_unlimited_label) }
                    val effectiveRetentionOn = cloudConfig.retentionEnabled && cloudConfig.totalRetentionMillis() > 0L

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SurveillanceSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (effectiveRetentionOn) {
                                    stringResource(R.string.retention_active_window, localizedWindowLabel.uppercase())
                                } else {
                                    stringResource(R.string.retention_unlimited_window)
                                },
                                color = if (effectiveRetentionOn) NeonCyan else NeonAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val prunedSuffix = if (retentionStatus.totalPrunedSession > 0) {
                                stringResource(R.string.retention_old_replaced_suffix, retentionStatus.totalPrunedSession)
                            } else {
                                ""
                            }
                            Text(
                                text = "${stringResource(R.string.retention_clips_used, retentionStatus.totalClipsKept, retentionStatus.totalUsedMb)}$prunedSuffix",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Quick Presets (Minutes, Hours, Days)
                    Text(
                        text = stringResource(R.string.retention_presets_label),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    val presets = listOf(
                        Triple(0, 0, 15) to ("15m" to "15$minUnit"),
                        Triple(0, 1, 0) to ("1h" to "1$hourUnit"),
                        Triple(0, 6, 0) to ("6h" to "6$hourUnit"),
                        Triple(0, 12, 0) to ("12h" to "12$hourUnit"),
                        Triple(1, 0, 0) to ("1d" to "1$dayUnit"),
                        Triple(3, 0, 0) to ("3d" to "3$dayUnit"),
                        Triple(7, 0, 0) to ("7d" to "7$dayUnit"),
                        Triple(30, 0, 0) to ("30d" to "30$dayUnit")
                    )
                    val currentD = retentionDaysText.toIntOrNull() ?: 0
                    val currentH = retentionHoursText.toIntOrNull() ?: 0
                    val currentM = retentionMinutesText.toIntOrNull() ?: 0

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        presets.forEach { (dhm, tagAndLabel) ->
                            val (presetKey, label) = tagAndLabel
                            val isSelected = retentionEnabled &&
                                currentD == dhm.first &&
                                currentH == dhm.second &&
                                currentM == dhm.third
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    retentionEnabled = true
                                    retentionDaysText = dhm.first.toString()
                                    retentionHoursText = dhm.second.toString()
                                    retentionMinutesText = dhm.third.toString()
                                    viewModel.updateRetentionPolicy(
                                        enabled = true,
                                        days = dhm.first,
                                        hours = dhm.second,
                                        minutes = dhm.third
                                    )
                                },
                                label = {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                modifier = Modifier.testTag("retention_preset_$presetKey")
                            )
                        }
                    }

                    // Custom Days / Hours / Minutes Inputs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = retentionDaysText,
                            onValueChange = { input ->
                                retentionDaysText = input.filter { it.isDigit() }.take(4)
                            },
                            label = { Text(stringResource(R.string.retention_days_label), maxLines = 1, softWrap = false) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("retention_days_input")
                        )
                        OutlinedTextField(
                            value = retentionHoursText,
                            onValueChange = { input ->
                                retentionHoursText = input.filter { it.isDigit() }.take(2)
                            },
                            label = { Text(stringResource(R.string.retention_hours_label), maxLines = 1, softWrap = false) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("retention_hours_input")
                        )
                        OutlinedTextField(
                            value = retentionMinutesText,
                            onValueChange = { input ->
                                retentionMinutesText = input.filter { it.isDigit() }.take(2)
                            },
                            label = { Text(stringResource(R.string.retention_mins_label), maxLines = 1, softWrap = false) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("retention_minutes_input")
                        )
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val d = (retentionDaysText.toIntOrNull() ?: 0).coerceIn(0, 3650)
                                val h = (retentionHoursText.toIntOrNull() ?: 0).coerceIn(0, 23)
                                val m = (retentionMinutesText.toIntOrNull() ?: 0).coerceIn(0, 59)
                                retentionDaysText = d.toString()
                                retentionHoursText = h.toString()
                                retentionMinutesText = m.toString()
                                viewModel.updateRetentionPolicy(
                                    enabled = retentionEnabled,
                                    days = d,
                                    hours = h,
                                    minutes = m
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier.testTag("save_retention_limit_btn")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.retention_apply_btn), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }

                        OutlinedButton(
                            onClick = { viewModel.pruneExpiredStorageNow() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier.testTag("prune_now_btn")
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.retention_replace_now_btn), fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // Provider Selector
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.cloud_protocol_title), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = selectedProvider == CloudProviderType.AWS_S3_COMPATIBLE,
                            onClick = { selectedProvider = CloudProviderType.AWS_S3_COMPATIBLE },
                            label = { Text(stringResource(R.string.cloud_provider_s3), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("chip_provider_s3")
                        )

                        FilterChip(
                            selected = selectedProvider == CloudProviderType.WEBDAV,
                            onClick = { selectedProvider = CloudProviderType.WEBDAV },
                            label = { Text(stringResource(R.string.cloud_provider_webdav), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("chip_provider_webdav")
                        )

                        FilterChip(
                            selected = selectedProvider == CloudProviderType.GOOGLE_DRIVE,
                            onClick = { selectedProvider = CloudProviderType.GOOGLE_DRIVE },
                            label = { Text(stringResource(R.string.cloud_provider_gdrive), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("chip_provider_gdrive")
                        )

                        FilterChip(
                            selected = selectedProvider == CloudProviderType.FTP_NAS_STORAGE,
                            onClick = { selectedProvider = CloudProviderType.FTP_NAS_STORAGE },
                            label = { Text(stringResource(R.string.cloud_provider_nas), fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("chip_provider_nas")
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

            // Real Provider-Specific Credentials Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (selectedProvider) {
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

                        CloudProviderType.FTP_NAS_STORAGE -> {
                            OutlinedTextField(
                                value = nasUrl,
                                onValueChange = { nasUrl = it },
                                label = { Text(stringResource(R.string.cloud_nas_url)) },
                                placeholder = { Text("http://192.168.1.50:5000/api/upload") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("nas_url_input")
                            )

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

                    // Handshake test & Save buttons (FlowRow ensures zero wrapping/overflow on narrow screens)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                saveConfirmationMessage = null
                                val testConfig = cloudConfig.copy(
                                    provider = selectedProvider,
                                    endpointUrl = when (selectedProvider) {
                                        CloudProviderType.AWS_S3_COMPATIBLE -> s3Endpoint
                                        CloudProviderType.WEBDAV -> webdavUrl
                                        CloudProviderType.GOOGLE_DRIVE -> "https://www.googleapis.com/drive/v3"
                                        CloudProviderType.FTP_NAS_STORAGE -> nasUrl
                                        CloudProviderType.WEBHOOK_REST_API -> webhookUrl
                                    },
                                    bucketOrFolderName = when (selectedProvider) {
                                        CloudProviderType.AWS_S3_COMPATIBLE -> s3Bucket
                                        CloudProviderType.GOOGLE_DRIVE -> gdriveFolder
                                        else -> "surveillance"
                                    },
                                    s3Endpoint = s3Endpoint,
                                    s3Bucket = s3Bucket,
                                    s3Region = s3Region,
                                    s3AccessKeyId = s3AccessKeyId,
                                    s3SecretAccessKey = s3SecretAccessKey,
                                    webdavUsername = webdavUsername,
                                    webdavPassword = webdavPassword,
                                    nasUsername = nasUsername,
                                    nasPassword = nasPassword,
                                    customHeaderName = webhookHeaderKey,
                                    customHeaderValue = webhookHeaderValue,
                                    apiKeyOrToken = when (selectedProvider) {
                                        CloudProviderType.GOOGLE_DRIVE -> gdriveToken
                                        CloudProviderType.WEBHOOK_REST_API -> webhookHeaderValue
                                        else -> ""
                                    }
                                )
                                viewModel.testCloudConnection(testConfig)
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
                                val updated = cloudConfig.copy(
                                    provider = selectedProvider,
                                    endpointUrl = when (selectedProvider) {
                                        CloudProviderType.AWS_S3_COMPATIBLE -> s3Endpoint
                                        CloudProviderType.WEBDAV -> webdavUrl
                                        CloudProviderType.GOOGLE_DRIVE -> "https://www.googleapis.com/drive/v3"
                                        CloudProviderType.FTP_NAS_STORAGE -> nasUrl
                                        CloudProviderType.WEBHOOK_REST_API -> webhookUrl
                                    },
                                    bucketOrFolderName = when (selectedProvider) {
                                        CloudProviderType.AWS_S3_COMPATIBLE -> s3Bucket
                                        CloudProviderType.GOOGLE_DRIVE -> gdriveFolder
                                        else -> "surveillance"
                                    },
                                    s3Endpoint = s3Endpoint,
                                    s3Bucket = s3Bucket,
                                    s3Region = s3Region,
                                    s3AccessKeyId = s3AccessKeyId,
                                    s3SecretAccessKey = s3SecretAccessKey,
                                    webdavUsername = webdavUsername,
                                    webdavPassword = webdavPassword,
                                    nasUsername = nasUsername,
                                    nasPassword = nasPassword,
                                    customHeaderName = webhookHeaderKey,
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
                                viewModel.saveCloudConfig(updated)
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

                    // Save confirmation badge
                    saveConfirmationMessage?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = NeonGreen.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen),
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

                    // Handshake result output
                    val handshakePrefix = stringResource(R.string.cloud_handshake_ok, 0, "", 0, "").substringBefore(":")
                    testResult?.let { res ->
                        val isSuccess = res.startsWith("Handshake OK") || res.startsWith(handshakePrefix)
                        val statusColor = if (isSuccess) NeonGreen else NeonRed
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusColor.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, statusColor),
                            modifier = Modifier.fillMaxWidth().testTag("handshake_result_banner")
                        ) {
                            Text(
                                text = res,
                                color = statusColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            // Sync Rules Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.cloud_policies_title), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)

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
                                viewModel.saveCloudConfig(cloudConfig.copy(autoSyncRecordings = it))
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
                                viewModel.saveCloudConfig(cloudConfig.copy(autoSyncMotionSnapshots = it))
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
                                viewModel.saveCloudConfig(cloudConfig.copy(wifiOnly = it))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                        )
                    }
                }
            }
        }
    }
}
