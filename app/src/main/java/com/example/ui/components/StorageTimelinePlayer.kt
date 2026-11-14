package com.example.ui.components

import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.data.model.ClipTriggerType
import com.example.data.model.CloudSyncStatus
import com.example.data.model.RecordedClipEntity
import com.example.engine.StorageTimelineEngine
import com.example.engine.TimelineSegment
import com.example.i18n.AppLocaleManager
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceSurface
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorageTimelinePlayer(
    clips: List<RecordedClipEntity>,
    selectedLanguageTag: String,
    initialClipId: String? = null,
    onSaveAsClip: (RecordedClipEntity) -> Unit,
    onUploadClip: (RecordedClipEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locale = remember(selectedLanguageTag) { AppLocaleManager.getEffectiveLocale(context) }
    val wallClockFormatter = remember(selectedLanguageTag) {
        SimpleDateFormat("MMM dd • HH:mm:ss", locale)
    }
    val dayChipFormatter = remember(selectedLanguageTag) {
        SimpleDateFormat("MMM dd", locale)
    }
    val timeOnlyFormatter = remember(selectedLanguageTag) {
        SimpleDateFormat("HH:mm:ss", locale)
    }

    val availableCameras = remember(clips) {
        clips.map { it.cameraId to it.cameraName }.distinctBy { it.first }
    }
    val availableDays = remember(clips) {
        StorageTimelineEngine.computeAvailableDays(clips)
    }

    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var selectedDayStartMs by remember { mutableStateOf<Long?>(null) }

    // If launched from a specific clip card, align camera filter if helpful or leave All Cameras
    val timeline = remember(clips, selectedCameraId, selectedDayStartMs) {
        StorageTimelineEngine.buildTimeline(
            clips = clips,
            cameraIdFilter = selectedCameraId,
            dayStartFilterMs = selectedDayStartMs,
            context = context,
            requirePlayable = true
        )
    }

    val playlistSignature = remember(timeline) {
        timeline.segments.joinToString("|") { "${it.clip.id}:${it.mediaUri}" }
    }

    var currentSegmentIndex by remember { mutableIntStateOf(0) }
    var currentPositionInClipMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var isMuted by remember { mutableStateOf(false) }

    val timelinePlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = timelinePlayer.currentMediaItemIndex
                if (idx >= 0) {
                    currentSegmentIndex = idx
                    currentPositionInClipMs = timelinePlayer.currentPosition.coerceAtLeast(0L)
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && timeline.segments.isNotEmpty()) {
                    isPlaying = false
                }
            }
        }
        timelinePlayer.addListener(listener)
        onDispose {
            timelinePlayer.removeListener(listener)
            timelinePlayer.release()
        }
    }

    LaunchedEffect(playlistSignature, initialClipId) {
        val playableSegments = timeline.segments.filter { it.mediaUri != null }
        if (playableSegments.isEmpty()) {
            timelinePlayer.stop()
            timelinePlayer.clearMediaItems()
            currentSegmentIndex = 0
            currentPositionInClipMs = 0L
            isPlaying = false
        } else {
            val mediaItems = playableSegments.mapNotNull { seg ->
                seg.mediaUri?.let { uri ->
                    MediaItem.Builder()
                        .setMediaId(seg.clip.id)
                        .setUri(uri)
                        .build()
                }
            }
            val startIdx = if (initialClipId != null) {
                playableSegments.indexOfFirst { it.clip.id == initialClipId }.coerceAtLeast(0)
            } else {
                0
            }
            currentSegmentIndex = startIdx
            currentPositionInClipMs = 0L
            timelinePlayer.setMediaItems(mediaItems, startIdx, 0L)
            timelinePlayer.setPlaybackSpeed(playbackSpeed)
            timelinePlayer.volume = if (isMuted) 0f else 1f
            timelinePlayer.prepare()
            timelinePlayer.playWhenReady = true
            isPlaying = true
        }
    }

    LaunchedEffect(timelinePlayer, timeline) {
        while (true) {
            if (timeline.segments.isNotEmpty()) {
                val idx = timelinePlayer.currentMediaItemIndex
                    .coerceIn(0, timeline.segments.lastIndex)
                currentSegmentIndex = idx
                currentPositionInClipMs = timelinePlayer.currentPosition.coerceAtLeast(0L)
                isPlaying = timelinePlayer.isPlaying
            }
            delay(250L)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(currentSegmentIndex) {
        if (currentSegmentIndex in timeline.segments.indices) {
            runCatching { listState.animateScrollToItem(currentSegmentIndex) }
        }
    }

    val activeSegment: TimelineSegment? = timeline.segments.getOrNull(currentSegmentIndex)
    val overallProgress = StorageTimelineEngine.computeOverallProgress(
        timeline = timeline,
        currentIndex = currentSegmentIndex,
        positionInClipMs = currentPositionInClipMs
    )
    val elapsedTotalMs = activeSegment?.let { seg ->
        seg.timelineStartOffsetMs + currentPositionInClipMs.coerceIn(0L, seg.durationMs)
    } ?: 0L
    val currentWallClockMs = StorageTimelineEngine.resolveWallClockAt(activeSegment, currentPositionInClipMs)

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("storage_timeline_view")
    ) {
        // Camera & Day Filter Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedCameraId == null,
                onClick = { selectedCameraId = null },
                label = { Text(stringResource(R.string.recordings_filter_all_cameras), fontSize = 11.sp, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NeonCyan.copy(alpha = 0.22f),
                    selectedLabelColor = NeonCyan,
                    labelColor = Color.White.copy(alpha = 0.8f)
                ),
                modifier = Modifier.testTag("timeline_filter_all_cameras")
            )
            availableCameras.forEach { (camId, camName) ->
                FilterChip(
                    selected = selectedCameraId == camId,
                    onClick = { selectedCameraId = if (selectedCameraId == camId) null else camId },
                    label = { Text(camName, fontSize = 11.sp, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonCyan.copy(alpha = 0.22f),
                        selectedLabelColor = NeonCyan,
                        labelColor = Color.White.copy(alpha = 0.8f)
                    )
                )
            }
            availableDays.forEach { dayMs ->
                FilterChip(
                    selected = selectedDayStartMs == dayMs,
                    onClick = { selectedDayStartMs = if (selectedDayStartMs == dayMs) null else dayMs },
                    label = { Text(dayChipFormatter.format(Date(dayMs)), fontSize = 11.sp, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonAmber.copy(alpha = 0.22f),
                        selectedLabelColor = NeonAmber,
                        labelColor = Color.White.copy(alpha = 0.8f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (timeline.isEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Timeline,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.recordings_timeline_empty),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            // 16:9 Gapless Timeline Video Viewport
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            factory = { viewCtx ->
                                (LayoutInflater.from(viewCtx).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
                                    this.player = timelinePlayer
                                    useController = false
                                    keepScreenOn = true
                                }
                            },
                            update = { pv ->
                                if (pv.player !== timelinePlayer) {
                                    pv.player = timelinePlayer
                                }
                            },
                            onRelease = { pv ->
                                pv.player = null
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Top Surveillance HUD Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopStart)
                                .background(Color.Black.copy(alpha = 0.58f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = activeSegment?.clip?.cameraName.orEmpty(),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                activeSegment?.clip?.triggerType?.let { trigger ->
                                    val badgeColor = when (trigger) {
                                        ClipTriggerType.MOTION_ALERT -> NeonAmber
                                        ClipTriggerType.SCHEDULED -> NeonCyan
                                        ClipTriggerType.MANUAL -> NeonRed
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = badgeColor.copy(alpha = 0.25f)
                                    ) {
                                        Text(
                                            text = when (trigger) {
                                                ClipTriggerType.MOTION_ALERT -> stringResource(R.string.recordings_trigger_motion)
                                                ClipTriggerType.SCHEDULED -> stringResource(R.string.recordings_trigger_scheduled)
                                                ClipTriggerType.MANUAL -> stringResource(R.string.recordings_trigger_manual)
                                            },
                                            color = badgeColor,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (currentWallClockMs > 0L) wallClockFormatter.format(Date(currentWallClockMs)) else "",
                                    color = NeonCyan,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(
                                        R.string.recordings_timeline_chunk_counter,
                                        currentSegmentIndex + 1,
                                        timeline.segments.size
                                    ),
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Interactive Visual NVR Timeline Ruler
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurveillanceCardBg)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        val seekAction: (Float) -> Unit = { fraction ->
                            StorageTimelineEngine.resolveSeekTarget(timeline, fraction)?.let { target ->
                                currentSegmentIndex = target.segmentIndex
                                currentPositionInClipMs = target.positionInClipMs
                                timelinePlayer.seekTo(target.segmentIndex, target.positionInClipMs)
                            }
                        }

                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurveillanceSurface)
                                .pointerInput(timeline) {
                                    detectTapGestures { offset ->
                                        if (size.width > 0) {
                                            seekAction(offset.x / size.width.toFloat())
                                        }
                                    }
                                }
                                .pointerInput(timeline) {
                                    detectHorizontalDragGestures { change, _ ->
                                        change.consume()
                                        if (size.width > 0) {
                                            seekAction(change.position.x / size.width.toFloat())
                                        }
                                    }
                                }
                                .testTag("timeline_scrubber_bar")
                        ) {
                            val w = size.width
                            val h = size.height
                            val totalMs = timeline.totalFootageMs.coerceAtLeast(1L).toFloat()

                            // Draw each stored segment block proportionally
                            for (seg in timeline.segments) {
                                val startX = (seg.timelineStartOffsetMs.toFloat() / totalMs) * w
                                val segW = ((seg.durationMs.toFloat() / totalMs) * w).coerceAtLeast(3f)
                                val baseColor = when (seg.clip.triggerType) {
                                    ClipTriggerType.MOTION_ALERT -> NeonAmber
                                    ClipTriggerType.SCHEDULED -> NeonCyan
                                    ClipTriggerType.MANUAL -> NeonRed
                                }
                                val isCurrent = seg.index == currentSegmentIndex
                                val alpha = if (isCurrent) 0.9f else 0.45f

                                drawRoundRect(
                                    color = baseColor.copy(alpha = alpha),
                                    topLeft = Offset(startX + 1f, 6f),
                                    size = Size((segW - 2f).coerceAtLeast(2f), h - 12f),
                                    cornerRadius = CornerRadius(4f, 4f)
                                )
                            }

                            // Playhead needle
                            val playheadX = (overallProgress * w).coerceIn(2f, (w - 2f).coerceAtLeast(2f))
                            drawLine(
                                color = Color.White,
                                start = Offset(playheadX, 0f),
                                end = Offset(playheadX, h),
                                strokeWidth = 4f
                            )
                            drawCircle(
                                color = NeonCyan,
                                radius = 6f,
                                center = Offset(playheadX, h / 2f)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${StorageTimelineEngine.formatDurationHms(elapsedTotalMs)} / ${StorageTimelineEngine.formatDurationHms(timeline.totalFootageMs)}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.recordings_timeline_seamless_hint),
                                color = Color.Gray,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // NVR Transport Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    val prev = (currentSegmentIndex - 1).coerceAtLeast(0)
                                    timelinePlayer.seekTo(prev, 0L)
                                },
                                enabled = currentSegmentIndex > 0,
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_prev_chunk_btn")
                            ) {
                                Icon(
                                    Icons.Default.SkipPrevious,
                                    contentDescription = stringResource(R.string.recordings_timeline_prev_chunk),
                                    tint = if (currentSegmentIndex > 0) Color.White else Color.Gray
                                )
                            }

                            IconButton(
                                onClick = {
                                    val target = (timelinePlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                    timelinePlayer.seekTo(target)
                                },
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_rewind_btn")
                            ) {
                                Icon(
                                    Icons.Default.Replay10,
                                    contentDescription = stringResource(R.string.recordings_timeline_rewind),
                                    tint = Color.White
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = NeonCyan,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        if (timelinePlayer.isPlaying) {
                                            timelinePlayer.pause()
                                        } else {
                                            if (timelinePlayer.playbackState == Player.STATE_ENDED) {
                                                timelinePlayer.seekTo(0, 0L)
                                            }
                                            timelinePlayer.play()
                                        }
                                    }
                                    .testTag("timeline_play_pause_btn")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.recordings_timeline_play_pause),
                                        tint = Color.Black,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    val segDur = activeSegment?.durationMs ?: 0L
                                    val nextPos = timelinePlayer.currentPosition + 10_000L
                                    if (nextPos >= segDur && currentSegmentIndex < timeline.segments.lastIndex) {
                                        timelinePlayer.seekTo(currentSegmentIndex + 1, 0L)
                                    } else {
                                        timelinePlayer.seekTo(nextPos.coerceAtMost(segDur))
                                    }
                                },
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_forward_btn")
                            ) {
                                Icon(
                                    Icons.Default.Forward10,
                                    contentDescription = stringResource(R.string.recordings_timeline_forward),
                                    tint = Color.White
                                )
                            }

                            IconButton(
                                onClick = {
                                    val next = (currentSegmentIndex + 1).coerceAtMost(timeline.segments.lastIndex)
                                    timelinePlayer.seekTo(next, 0L)
                                },
                                enabled = currentSegmentIndex < timeline.segments.lastIndex,
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_next_chunk_btn")
                            ) {
                                Icon(
                                    Icons.Default.SkipNext,
                                    contentDescription = stringResource(R.string.recordings_timeline_next_chunk),
                                    tint = if (currentSegmentIndex < timeline.segments.lastIndex) Color.White else Color.Gray
                                )
                            }

                            // Speed Cycle Button (1x -> 1.5x -> 2x -> 4x)
                            val speedLabel = if (playbackSpeed % 1f == 0f) "${playbackSpeed.toInt()}x" else "${playbackSpeed}x"
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SurveillanceSurface,
                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val nextSpeed = when (playbackSpeed) {
                                            1f -> 1.5f
                                            1.5f -> 2f
                                            2f -> 4f
                                            else -> 1f
                                        }
                                        playbackSpeed = nextSpeed
                                        timelinePlayer.setPlaybackSpeed(nextSpeed)
                                    }
                                    .testTag("timeline_speed_btn")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = speedLabel,
                                        color = NeonCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    isMuted = !isMuted
                                    timelinePlayer.volume = if (isMuted) 0f else 1f
                                },
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_mute_btn")
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                    contentDescription = stringResource(R.string.recordings_timeline_mute_toggle),
                                    tint = if (isMuted) Color.Gray else NeonCyan
                                )
                            }
                        }

                        // Quick Export / Sync actions for active segment
                        activeSegment?.clip?.let { activeClip ->
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                TextButton(
                                    onClick = { onSaveAsClip(activeClip) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.recordings_save_as_btn), color = Color.White, fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = { onUploadClip(activeClip) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = if (activeClip.cloudSyncStatus == CloudSyncStatus.SYNCED) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                                        contentDescription = null,
                                        tint = if (activeClip.cloudSyncStatus == CloudSyncStatus.SYNCED) NeonGreen else NeonCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (activeClip.cloudSyncStatus == CloudSyncStatus.SYNCED) {
                                            stringResource(R.string.recordings_cloud_synced)
                                        } else {
                                            stringResource(R.string.recordings_sync_to_cloud)
                                        },
                                        color = if (activeClip.cloudSyncStatus == CloudSyncStatus.SYNCED) NeonGreen else NeonCyan,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Chronological Chunk Queue synced with Playback
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(timeline.segments, key = { it.clip.id }) { segment ->
                    val isCurrent = segment.index == currentSegmentIndex
                    val triggerColor = when (segment.clip.triggerType) {
                        ClipTriggerType.MOTION_ALERT -> NeonAmber
                        ClipTriggerType.SCHEDULED -> NeonCyan
                        ClipTriggerType.MANUAL -> NeonRed
                    }

                    if (segment.gapBeforeMs > 0L) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = SurveillanceSurface
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.recordings_timeline_gap_badge,
                                        StorageTimelineEngine.formatDurationHms(segment.gapBeforeMs)
                                    ),
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) NeonCyan.copy(alpha = 0.14f) else SurveillanceCardBg
                        ),
                        border = if (isCurrent) BorderStroke(1.dp, NeonCyan) else null,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                currentSegmentIndex = segment.index
                                currentPositionInClipMs = 0L
                                timelinePlayer.seekTo(segment.index, 0L)
                                if (!timelinePlayer.isPlaying) {
                                    timelinePlayer.play()
                                }
                            }
                            .testTag("timeline_segment_item_${segment.clip.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(if (isCurrent) NeonCyan else SurveillanceSurface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = if (isCurrent) Color.Black else triggerColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "#${segment.index + 1} • ${segment.clip.cameraName}",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (isCurrent) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = NeonGreen.copy(alpha = 0.22f)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.recordings_timeline_now_playing),
                                                    color = NeonGreen,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "${timeOnlyFormatter.format(Date(segment.startMs))} → ${timeOnlyFormatter.format(Date(segment.endMs))} • ${segment.clip.durationSeconds}s • ${segment.clip.fileSizeMb} MB",
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
