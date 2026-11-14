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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.LocalSaveDestination
import com.example.i18n.AppLocaleManager
import com.example.ui.components.RemoteHubCard
import com.example.ui.components.RemoteStorageSection
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
    val detailedTestResult by viewModel.detailedStorageTestResult.collectAsState()
    val extendedStorageConfig by viewModel.extendedStorageConfig.collectAsState()
    val discoveredEndpoints by viewModel.discoveredStorageEndpoints.collectAsState()
    val isScanningStorage by viewModel.isScanningStorage.collectAsState()

    val hubState by viewModel.hubServerState.collectAsState()
    val hubOneTimePassword by viewModel.hubOneTimeGeneratedPassword.collectAsState()
    val hubOneTimeApiKey by viewModel.hubOneTimeApiKey.collectAsState()
    val hubSelfTestResult by viewModel.hubSelfTestResult.collectAsState()
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

            // Spare-Device Remote Hub Server Card (Multi-Transport & PBKDF2 Security)
            RemoteHubCard(
                hubState = hubState,
                oneTimeGeneratedPassword = hubOneTimePassword,
                oneTimeApiKey = hubOneTimeApiKey,
                selfTestResult = hubSelfTestResult,
                copyNotice = hubCopyMessage,
                onToggleHub = { viewModel.toggleHubServer(it) },
                onRefreshEndpoints = { viewModel.refreshHubNetworkEndpoints() },
                onRunSelfTest = { viewModel.testHubLocalReachability() },
                onCopyToClipboard = copyToClipboard,
                onApplySecurityAndConnection = { port, user, newPass, allowCtrl, scope, profile, extUrl ->
                    viewModel.updateHubSecurityAndConnection(
                        port = port,
                        username = user,
                        newPassword = newPass,
                        allowRemoteControl = allowCtrl,
                        accessScope = scope,
                        connectionProfile = profile,
                        externalUrl = extUrl
                    )
                },
                onToggleRemoteControl = { viewModel.setRemoteHubControlEnabled(it) },
                onToggleApiKeyEnabled = { viewModel.setHubApiKeyEnabled(it) },
                onRegenerateApiKey = { viewModel.regenerateHubApiKey() },
                onRevokeAllSessions = { viewModel.revokeAllHubSessions() },
                onDismissOneTimeSecrets = { viewModel.dismissOneTimeHubSecrets() }
            )

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

            RemoteStorageSection(
                cloudConfig = cloudConfig,
                extendedConfig = extendedStorageConfig,
                discoveredEndpoints = discoveredEndpoints,
                isScanningStorage = isScanningStorage,
                isTesting = isTesting,
                testResult = testResult,
                detailedTestResult = detailedTestResult,
                onScanNetwork = { viewModel.scanNetworkStorage() },
                onLaunchSafFolderPicker = launchSystemFolderPicker,
                onTestConnection = { draftCloud, draftExt ->
                    viewModel.testCloudConnection(draftCloud, draftExt)
                },
                onSaveCredentials = { draftCloud, draftExt ->
                    viewModel.saveCloudConfig(draftCloud)
                    viewModel.saveExtendedStorageConfig(draftExt)
                },
                onSaveExtendedOptions = { draftExt ->
                    viewModel.saveExtendedStorageConfig(draftExt)
                },
                onSaveSyncPolicies = { updatedCloud ->
                    viewModel.saveCloudConfig(updatedCloud)
                }
            )
        }
    }
}
