package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.ClipTriggerType
import com.example.data.model.CloudSyncStatus
import com.example.data.model.RecordedClipEntity
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
import java.util.Locale

@Composable
fun RecordingsScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val clips by viewModel.clips.collectAsState()
    val syncProgress by viewModel.cloudStorageManager.syncProgress.collectAsState()
    val cloudConfig by viewModel.cloudConfig.collectAsState()

    var clipToPlay by remember { mutableStateOf<RecordedClipEntity?>(null) }
    val dateFormatter = remember { SimpleDateFormat("MMM dd • HH:mm:ss", Locale.US) }

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
                Column {
                    Text(
                        text = "Recorded Surveillance",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = NeonCyan
                    )
                    Text(
                        text = "${clips.size} Saved Clips • Cloud Target: ${cloudConfig.provider.name}",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f)
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
                        modifier = Modifier.testTag("sync_all_clips_btn")
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sync All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Cloud Sync Banner if active
            syncProgress?.let { progressMsg ->
                Spacer(modifier = Modifier.height(10.dp))
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

            Spacer(modifier = Modifier.height(14.dp))

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
                            text = "No recordings yet",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Scheduled or motion triggered clips appear here",
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
                                        contentDescription = "Play Video",
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
                                                text = clip.triggerType.name.replace("_", " "),
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

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Cloud sync status pill
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        when (clip.cloudSyncStatus) {
                                            CloudSyncStatus.SYNCED -> {
                                                Icon(Icons.Default.CloudDone, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Cloud Synced", color = NeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                            CloudSyncStatus.UPLOADING -> {
                                                Icon(Icons.Default.CloudSync, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Uploading...", color = NeonAmber, fontSize = 10.sp)
                                            }
                                            CloudSyncStatus.PENDING_UPLOAD -> {
                                                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Pending Upload", color = Color.Gray, fontSize = 10.sp)
                                            }
                                            CloudSyncStatus.LOCAL_ONLY -> {
                                                TextButton(
                                                    onClick = { viewModel.uploadClipToCloud(clip) },
                                                    modifier = Modifier.height(24.dp)
                                                ) {
                                                    Text("Upload to Cloud →", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            CloudSyncStatus.FAILED -> {
                                                Text("Sync Failed (Retry)", color = NeonRed, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }

                                // Delete
                                IconButton(
                                    onClick = { viewModel.deleteClip(clip.id) },
                                    modifier = Modifier.size(32.dp).testTag("delete_clip_${clip.id}")
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete Clip", tint = Color.Gray, modifier = Modifier.size(16.dp))
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
                            text = "Playback: ${clip.cameraName}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val previewPlayer = remember(clip.id) {
                            androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                                setVideoChangeFrameRateStrategy(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                                val file = java.io.File(clip.filePath)
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
                                factory = { ctx ->
                                    (android.view.LayoutInflater.from(ctx).inflate(com.example.R.layout.player_view_texture, null) as androidx.media3.ui.PlayerView).apply {
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    viewModel.uploadClipToCloud(clip)
                                    clipToPlay = null
                                }
                            ) {
                                Text("Sync To Cloud", color = NeonCyan)
                            }
                            TextButton(onClick = { clipToPlay = null }) {
                                Text("Close", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
