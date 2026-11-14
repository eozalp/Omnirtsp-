package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.model.ClipTriggerType
import com.example.data.model.CloudSyncStatus
import com.example.data.model.LocalSaveDestination
import com.example.data.model.RecordedClipEntity
import com.example.i18n.AppLocaleManager
import com.example.ui.components.StorageTimelinePlayer
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceDarkBg
import com.example.ui.theme.SurveillanceSurface
import com.example.ui.viewmodel.CameraViewModel
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingsScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clips by viewModel.clips.collectAsState()
    val syncProgress by viewModel.cloudStorageManager.syncProgress.collectAsState()
    val cloudConfig by viewModel.cloudConfig.collectAsState()
    val localExportStatus by viewModel.localExportStatus.collectAsState()
    val retentionStatus by viewModel.retentionStatus.collectAsState()
    val selectedLanguageTag by viewModel.selectedLanguageTag.collectAsState()

    var clipToPlay by remember { mutableStateOf<RecordedClipEntity?>(null) }
    var isTimelineMode by remember { mutableStateOf(false) }
    var timelineInitialClipId by remember { mutableStateOf<String?>(null) }
    var pendingSaveAsClip by remember { mutableStateOf<RecordedClipEntity?>(null) }
    var fileManagerUnavailableMsg by remember { mutableStateOf<String?>(null) }
    var storageControlsExpanded by remember { mutableStateOf(false) }
    var selectedTriggerFilter by remember { mutableStateOf<ClipTriggerType?>(null) }
    val dateFormatter = remember(selectedLanguageTag) {
        SimpleDateFormat("MMM dd • HH:mm:ss", AppLocaleManager.getEffectiveLocale(context))
    }
    val filteredClips = remember(clips, selectedTriggerFilter) {
        if (selectedTriggerFilter == null) clips else clips.filter { it.triggerType == selectedTriggerFilter }
    }

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            fileManagerUnavailableMsg = null
            viewModel.selectSafFolder(treeUri)
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { targetUri ->
        val clip = pendingSaveAsClip
        if (targetUri != null && clip != null) {
            fileManagerUnavailableMsg = null
            viewModel.exportClipToCustomUri(clip, targetUri)
        }
        pendingSaveAsClip = null
    }

    val launchFolderPicker = {
        try {
            pickFolderLauncher.launch(null)
        } catch (_: Exception) {
            fileManagerUnavailableMsg = AppLocaleManager.getString(context, R.string.recordings_picker_unavailable)
        }
    }

    val launchSaveAsForClip: (RecordedClipEntity) -> Unit = { clip ->
        pendingSaveAsClip = clip
        val safeName = clip.cameraName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val suggested = "${safeName}_${clip.id.takeLast(6)}.mp4"
        try {
            saveAsLauncher.launch(suggested)
        } catch (_: Exception) {
            pendingSaveAsClip = null
            fileManagerUnavailableMsg = AppLocaleManager.getString(context, R.string.recordings_save_as_unavailable)
            viewModel.exportClipToConfiguredFolder(clip)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurveillanceDarkBg)
            .testTag("recordings_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.recordings_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = NeonCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.recordings_saved_count, clips.size),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (clips.any { it.cloudSyncStatus != CloudSyncStatus.SYNCED }) {
                    Button(
                        onClick = {
                            clips.filter { it.cloudSyncStatus != CloudSyncStatus.SYNCED }
                                .forEach { viewModel.uploadClipToCloud(it) }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("sync_all_clips_btn")
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.recordings_sync_all), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Mode Switcher: Seamless Timeline Watching vs Clips List
            if (clips.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isTimelineMode) NeonCyan else SurveillanceCardBg,
                        border = if (!isTimelineMode) androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)) else null,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { isTimelineMode = true }
                            .testTag("recordings_mode_timeline_btn")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Timeline,
                                contentDescription = null,
                                tint = if (isTimelineMode) Color.Black else NeonCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.recordings_tab_timeline),
                                color = if (isTimelineMode) Color.Black else NeonCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (!isTimelineMode) NeonCyan else SurveillanceCardBg,
                        border = if (isTimelineMode) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)) else null,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { isTimelineMode = false }
                            .testTag("recordings_mode_clips_btn")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.VideoLibrary,
                                contentDescription = null,
                                tint = if (!isTimelineMode) Color.Black else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.recordings_tab_clips),
                                color = if (!isTimelineMode) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (!isTimelineMode) {
                val activeFolderText = when (cloudConfig.localSaveDestination) {
                    LocalSaveDestination.CUSTOM_SAF_FOLDER ->
                        cloudConfig.safFolderDisplayName.ifBlank { stringResource(R.string.recordings_selected_folder) }
                    LocalSaveDestination.PUBLIC_MOVIES -> stringResource(R.string.recordings_public_movies)
                    LocalSaveDestination.APP_INTERNAL -> stringResource(R.string.recordings_app_private)
                }
                val dayUnit = stringResource(R.string.retention_unit_day_short)
                val hourUnit = stringResource(R.string.retention_unit_hour_short)
                val minUnit = stringResource(R.string.retention_unit_min_short)
                val localizedWindowLabel = buildList {
                    if (cloudConfig.retentionDays > 0) add("${cloudConfig.retentionDays}$dayUnit")
                    if (cloudConfig.retentionHours > 0) add("${cloudConfig.retentionHours}$hourUnit")
                    if (cloudConfig.retentionMinutes > 0) add("${cloudConfig.retentionMinutes}$minUnit")
                }.joinToString(" ").ifBlank { stringResource(R.string.retention_unlimited_label) }
                val effectiveRetentionOn = cloudConfig.retentionEnabled && cloudConfig.totalRetentionMillis() > 0L

                // Single compact collapsible storage/retention bar so the clips list owns the screen
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recordings_save_destination_card")
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { storageControlsExpanded = !storageControlsExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.recordings_storage_compact,
                                        activeFolderText,
                                        if (effectiveRetentionOn) localizedWindowLabel else stringResource(R.string.retention_unlimited_label)
                                    ),
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.recordings_manage_storage),
                                    color = NeonCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = if (storageControlsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = stringResource(R.string.recordings_manage_storage),
                                    tint = NeonCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        AnimatedVisibility(visible = storageControlsExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                val prunedInfo = if (retentionStatus.totalPrunedSession > 0) {
                                    stringResource(R.string.recordings_replaced_suffix, retentionStatus.totalPrunedSession)
                                } else {
                                    ""
                                }
                                Text(
                                    text = "${stringResource(R.string.recordings_usage_summary, retentionStatus.totalClipsKept, retentionStatus.totalUsedMb)}$prunedInfo",
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 11.sp
                                )
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = launchFolderPicker,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier.testTag("recordings_choose_folder_btn")
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.recordings_choose_folder), fontSize = 11.sp, maxLines = 1, softWrap = false)
                                    }

                                    OutlinedButton(
                                        onClick = { viewModel.pruneExpiredStorageNow() },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier.testTag("recordings_prune_now_btn")
                                    ) {
                                        Text(stringResource(R.string.recordings_clean_old), fontSize = 11.sp, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }

                // Slim Trigger Filter Chips
                if (clips.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val chipOptions = listOf(
                            null to R.string.recordings_filter_all_types,
                            ClipTriggerType.MOTION_ALERT to R.string.recordings_filter_motion,
                            ClipTriggerType.SCHEDULED to R.string.recordings_filter_scheduled,
                            ClipTriggerType.MANUAL to R.string.recordings_filter_manual
                        )
                        items(chipOptions) { (trigger, labelRes) ->
                            val selected = selectedTriggerFilter == trigger
                            FilterChip(
                                selected = selected,
                                onClick = { selectedTriggerFilter = trigger },
                                label = {
                                    Text(
                                        text = stringResource(labelRes),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = NeonCyan
                                ),
                                modifier = Modifier.height(30.dp)
                            )
                        }
                    }
                }

                // Local Export / File Manager Status Banner
                val saveFailedPrefix = stringResource(R.string.status_save_failed, "").trimEnd()
                val activeExportBanner = fileManagerUnavailableMsg ?: localExportStatus
                activeExportBanner?.let { exportMsg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    val isErr = fileManagerUnavailableMsg != null ||
                        exportMsg.startsWith("Save failed") ||
                        exportMsg.startsWith(saveFailedPrefix)
                    val bannerColor = if (isErr) NeonAmber else NeonGreen
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = bannerColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, bannerColor),
                        modifier = Modifier.fillMaxWidth().testTag("local_export_status_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, tint = bannerColor, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(exportMsg, color = bannerColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                            IconButton(
                                onClick = {
                                    fileManagerUnavailableMsg = null
                                    viewModel.clearLocalExportStatus()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = bannerColor, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // Cloud Sync Banner if active
                syncProgress?.let { progressMsg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = NeonCyan.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, NeonCyan),
                        modifier = Modifier.fillMaxWidth().testTag("sync_progress_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(progressMsg, color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            if (clips.isNotEmpty() && isTimelineMode) {
                StorageTimelinePlayer(
                    clips = clips,
                    selectedLanguageTag = selectedLanguageTag,
                    initialClipId = timelineInitialClipId,
                    onSaveAsClip = launchSaveAsForClip,
                    onUploadClip = { viewModel.uploadClipToCloud(it) },
                    modifier = Modifier.fillMaxSize().weight(1f)
                )
            } else if (clips.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.recordings_empty_title),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                        Text(
                            text = stringResource(R.string.recordings_empty_subtitle),
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }
            } else if (filteredClips.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.recordings_empty_title),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                        Text(
                            text = stringResource(R.string.recordings_empty_subtitle),
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredClips, key = { it.id }) { clip ->
                        val triggerColor = when (clip.triggerType) {
                            ClipTriggerType.MOTION_ALERT -> NeonAmber
                            ClipTriggerType.SCHEDULED -> NeonCyan
                            ClipTriggerType.MANUAL -> NeonRed
                        }
                        var menuExpanded by remember(clip.id) { mutableStateOf(false) }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { clipToPlay = clip }
                                .testTag("clip_item_${clip.id}"),
                            colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Clean Play Thumbnail Box with subtle trigger accent border
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = SurveillanceSurface,
                                    border = BorderStroke(1.dp, triggerColor.copy(alpha = 0.55f)),
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = stringResource(R.string.recordings_playback_title, clip.cameraName),
                                            tint = NeonCyan,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                // Spacious Title + Concise Metadata + Compact Status Icons
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = clip.cameraName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = triggerColor.copy(alpha = 0.16f)
                                        ) {
                                            Text(
                                                text = when (clip.triggerType) {
                                                    ClipTriggerType.MOTION_ALERT -> stringResource(R.string.recordings_trigger_motion)
                                                    ClipTriggerType.SCHEDULED -> stringResource(R.string.recordings_trigger_scheduled)
                                                    ClipTriggerType.MANUAL -> stringResource(R.string.recordings_trigger_manual)
                                                },
                                                color = triggerColor,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(3.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "${clip.durationSeconds}s • ${clip.fileSizeMb} MB • ${dateFormatter.format(Date(clip.timestamp))}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.Gray,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (clip.localExportUri != null) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = stringResource(R.string.recordings_saved_btn),
                                                tint = NeonGreen,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                        when (clip.cloudSyncStatus) {
                                            CloudSyncStatus.SYNCED -> Icon(
                                                imageVector = Icons.Default.CloudDone,
                                                contentDescription = stringResource(R.string.recordings_cloud_synced),
                                                tint = NeonGreen,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            CloudSyncStatus.UPLOADING, CloudSyncStatus.PENDING_UPLOAD -> Icon(
                                                imageVector = Icons.Default.CloudSync,
                                                contentDescription = stringResource(R.string.recordings_uploading),
                                                tint = NeonAmber,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            CloudSyncStatus.FAILED -> Icon(
                                                imageVector = Icons.Default.CloudUpload,
                                                contentDescription = stringResource(R.string.recordings_retry_cloud),
                                                tint = NeonRed,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            CloudSyncStatus.LOCAL_ONLY -> Unit
                                        }
                                    }
                                }

                                // Single Overflow Menu replacing 4 noisy per-row buttons
                                Box {
                                    IconButton(
                                        onClick = { menuExpanded = true },
                                        modifier = Modifier.testTag("clip_more_btn_${clip.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = stringResource(R.string.recordings_more_actions),
                                            tint = Color.White.copy(alpha = 0.8f)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                        modifier = Modifier.background(SurveillanceSurface)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.recordings_open_timeline_item), color = NeonCyan) },
                                            leadingIcon = { Icon(Icons.Default.Timeline, contentDescription = null, tint = NeonCyan) },
                                            onClick = {
                                                menuExpanded = false
                                                timelineInitialClipId = clip.id
                                                isTimelineMode = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.recordings_play_clip_item), color = Color.White) },
                                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White) },
                                            onClick = {
                                                menuExpanded = false
                                                clipToPlay = clip
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = if (clip.localExportUri != null) {
                                                        stringResource(R.string.recordings_saved_btn)
                                                    } else {
                                                        stringResource(R.string.recordings_save_btn)
                                                    },
                                                    color = if (clip.localExportUri != null) NeonGreen else Color.White
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    if (clip.localExportUri != null) Icons.Default.Check else Icons.Default.Save,
                                                    contentDescription = null,
                                                    tint = if (clip.localExportUri != null) NeonGreen else NeonCyan
                                                )
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                viewModel.exportClipToConfiguredFolder(clip)
                                            },
                                            modifier = Modifier.testTag("save_clip_device_${clip.id}")
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.recordings_save_as_btn), color = Color.White) },
                                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White) },
                                            onClick = {
                                                menuExpanded = false
                                                launchSaveAsForClip(clip)
                                            },
                                            modifier = Modifier.testTag("save_as_clip_${clip.id}")
                                        )
                                        if (clip.cloudSyncStatus != CloudSyncStatus.SYNCED) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = if (clip.cloudSyncStatus == CloudSyncStatus.FAILED) {
                                                            stringResource(R.string.recordings_retry_cloud)
                                                        } else {
                                                            stringResource(R.string.recordings_sync_to_cloud)
                                                        },
                                                        color = NeonCyan
                                                    )
                                                },
                                                leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = NeonCyan) },
                                                onClick = {
                                                    menuExpanded = false
                                                    viewModel.uploadClipToCloud(clip)
                                                }
                                            )
                                        }
                                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.recordings_delete_clip_desc), color = NeonRed) },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = NeonRed) },
                                            onClick = {
                                                menuExpanded = false
                                                viewModel.deleteClip(clip.id)
                                            },
                                            modifier = Modifier.testTag("delete_clip_${clip.id}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Preview Video Dialog
        clipToPlay?.let { clip ->
            Dialog(onDismissRequest = { clipToPlay = null }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SurveillanceCardBg,
                    modifier = Modifier.fillMaxWidth().testTag("clip_playback_dialog")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.recordings_playback_title, clip.cameraName),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        val ctx = LocalContext.current
                        val previewPlayer = remember(clip.id) {
                            androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
                                setVideoChangeFrameRateStrategy(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                                val file = java.io.File(clip.filePath)
                                com.example.engine.DeviceStorageExporter.ensureValidClipPayload(ctx, file)
                                if (file.exists() && file.length() > 0) {
                                    setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
                                    prepare()
                                    playWhenReady = true
                                }
                            }
                        }
                        androidx.compose.runtime.DisposableEffect(clip.id) {
                            onDispose {
                                previewPlayer.release()
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black)
                        ) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { viewCtx ->
                                    (android.view.LayoutInflater.from(viewCtx).inflate(com.example.R.layout.player_view_texture, null) as androidx.media3.ui.PlayerView).apply {
                                        this.player = previewPlayer
                                        useController = true
                                    }
                                },
                                onRelease = { playerView ->
                                    playerView.player = null
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    timelineInitialClipId = clip.id
                                    isTimelineMode = true
                                    clipToPlay = null
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(stringResource(R.string.recordings_watch_timeline), color = NeonAmber, maxLines = 1, softWrap = false)
                            }
                            TextButton(
                                onClick = {
                                    launchSaveAsForClip(clip)
                                    clipToPlay = null
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(stringResource(R.string.recordings_save_as_btn), color = NeonGreen, maxLines = 1, softWrap = false)
                            }
                            TextButton(
                                onClick = {
                                    viewModel.uploadClipToCloud(clip)
                                    clipToPlay = null
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(stringResource(R.string.recordings_sync_to_cloud), color = NeonCyan, maxLines = 1, softWrap = false)
                            }
                            TextButton(
                                onClick = { clipToPlay = null },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(stringResource(R.string.common_close), color = Color.White, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}
