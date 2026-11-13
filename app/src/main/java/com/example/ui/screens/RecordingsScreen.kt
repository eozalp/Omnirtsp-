package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    var pendingSaveAsClip by remember { mutableStateOf<RecordedClipEntity?>(null) }
    var fileManagerUnavailableMsg by remember { mutableStateOf<String?>(null) }
    val dateFormatter = remember(selectedLanguageTag) {
        SimpleDateFormat("MMM dd • HH:mm:ss", AppLocaleManager.getEffectiveLocale(context))
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

            // Active Local / Remote Save Folder Bar
            val activeFolderText = when (cloudConfig.localSaveDestination) {
                LocalSaveDestination.CUSTOM_SAF_FOLDER ->
                    cloudConfig.safFolderDisplayName.ifBlank { stringResource(R.string.recordings_selected_folder) }
                LocalSaveDestination.PUBLIC_MOVIES -> stringResource(R.string.recordings_public_movies)
                LocalSaveDestination.APP_INTERNAL -> stringResource(R.string.recordings_app_private)
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("recordings_save_destination_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
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
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.recordings_save_to_label),
                                color = Color.Gray,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = activeFolderText,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = launchFolderPicker,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("recordings_choose_folder_btn")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.recordings_choose_folder), fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Rolling Storage Limit Strip
            val dayUnit = stringResource(R.string.retention_unit_day_short)
            val hourUnit = stringResource(R.string.retention_unit_hour_short)
            val minUnit = stringResource(R.string.retention_unit_min_short)
            val localizedWindowLabel = buildList {
                if (cloudConfig.retentionDays > 0) add("${cloudConfig.retentionDays}$dayUnit")
                if (cloudConfig.retentionHours > 0) add("${cloudConfig.retentionHours}$hourUnit")
                if (cloudConfig.retentionMinutes > 0) add("${cloudConfig.retentionMinutes}$minUnit")
            }.joinToString(" ").ifBlank { stringResource(R.string.retention_unlimited_label) }
            val effectiveRetentionOn = cloudConfig.retentionEnabled && cloudConfig.totalRetentionMillis() > 0L

            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("recordings_retention_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = if (effectiveRetentionOn) {
                                stringResource(R.string.recordings_auto_replace_last, localizedWindowLabel.uppercase())
                            } else {
                                stringResource(R.string.recordings_auto_replace_off)
                            },
                            color = if (effectiveRetentionOn) NeonCyan else NeonAmber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        val prunedInfo = if (retentionStatus.totalPrunedSession > 0) {
                            stringResource(R.string.recordings_replaced_suffix, retentionStatus.totalPrunedSession)
                        } else {
                            ""
                        }
                        Text(
                            text = "${stringResource(R.string.recordings_usage_summary, retentionStatus.totalClipsKept, retentionStatus.totalUsedMb)}$prunedInfo",
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, bannerColor),
                    modifier = Modifier.fillMaxWidth().testTag("local_export_status_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, tint = bannerColor, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(exportMsg, color = bannerColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan),
                    modifier = Modifier.fillMaxWidth().testTag("sync_progress_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(progressMsg, color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Clips List
            if (clips.isEmpty()) {
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
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(clips, key = { it.id }) { clip ->
                        val triggerColor = when (clip.triggerType) {
                            ClipTriggerType.MOTION_ALERT -> NeonAmber
                            ClipTriggerType.SCHEDULED -> NeonCyan
                            ClipTriggerType.MANUAL -> NeonRed
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("clip_item_${clip.id}"),
                            colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Thumbnail / Play button
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SurveillanceSurface)
                                        .clickable { clipToPlay = clip },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.recordings_playback_title, clip.cameraName),
                                        tint = NeonCyan,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Clip Details
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = clip.cameraName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = Color.White
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = triggerColor.copy(alpha = 0.2f)
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
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = "${clip.durationSeconds}s • ${clip.fileSizeMb} MB • ${dateFormatter.format(Date(clip.timestamp))}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Gray
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Device Save + Save As + Cloud Sync Actions (FlowRow prevents wrapping overflow)
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        TextButton(
                                            onClick = { viewModel.exportClipToConfiguredFolder(clip) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 1.dp, minHeight = 34.dp)
                                                .testTag("save_clip_device_${clip.id}")
                                        ) {
                                            Icon(
                                                imageVector = if (clip.localExportUri != null) Icons.Default.Check else Icons.Default.Save,
                                                contentDescription = null,
                                                tint = if (clip.localExportUri != null) NeonGreen else NeonCyan,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (clip.localExportUri != null) {
                                                    stringResource(R.string.recordings_saved_btn)
                                                } else {
                                                    stringResource(R.string.recordings_save_btn)
                                                },
                                                color = if (clip.localExportUri != null) NeonGreen else NeonCyan,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }

                                        TextButton(
                                            onClick = { launchSaveAsForClip(clip) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 1.dp, minHeight = 34.dp)
                                                .testTag("save_as_clip_${clip.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FolderOpen,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = stringResource(R.string.recordings_save_as_btn),
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }

                                        when (clip.cloudSyncStatus) {
                                            CloudSyncStatus.SYNCED -> {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                                                ) {
                                                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(13.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(stringResource(R.string.recordings_cloud_synced), color = NeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                                }
                                            }
                                            CloudSyncStatus.UPLOADING -> {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                                                ) {
                                                    Icon(Icons.Default.CloudSync, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(13.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(stringResource(R.string.recordings_uploading), color = NeonAmber, fontSize = 10.sp, maxLines = 1, softWrap = false)
                                                }
                                            }
                                            CloudSyncStatus.PENDING_UPLOAD -> {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                                                ) {
                                                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(13.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(stringResource(R.string.recordings_pending_upload), color = Color.Gray, fontSize = 10.sp, maxLines = 1, softWrap = false)
                                                }
                                            }
                                            CloudSyncStatus.LOCAL_ONLY -> {
                                                TextButton(
                                                    onClick = { viewModel.uploadClipToCloud(clip) },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 34.dp)
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.recordings_cloud_sync_btn),
                                                        color = NeonCyan,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        softWrap = false
                                                    )
                                                }
                                            }
                                            CloudSyncStatus.FAILED -> {
                                                TextButton(
                                                    onClick = { viewModel.uploadClipToCloud(clip) },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 34.dp)
                                                ) {
                                                    Text(stringResource(R.string.recordings_retry_cloud), color = NeonRed, fontSize = 11.sp, maxLines = 1, softWrap = false)
                                                }
                                            }
                                        }
                                    }
                                }

                                // Delete
                                IconButton(
                                    onClick = { viewModel.deleteClip(clip.id) },
                                    modifier = Modifier.size(32.dp).testTag("delete_clip_${clip.id}")
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.recordings_delete_clip_desc), tint = Color.Gray, modifier = Modifier.size(16.dp))
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
