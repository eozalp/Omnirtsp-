package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.data.model.ClipTriggerType
import com.example.data.model.CloudSyncStatus
import com.example.data.model.RecordedClipEntity
import com.example.engine.StorageTimeline
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

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

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
    val activity = remember(context) { context.findHostActivity() }
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
    var motionOnlyFilter by remember { mutableStateOf(false) }

    val filteredClipsInput = remember(clips, motionOnlyFilter) {
        if (motionOnlyFilter) clips.filter { it.triggerType == ClipTriggerType.MOTION_ALERT } else clips
    }

    val timeline = remember(filteredClipsInput, selectedCameraId, selectedDayStartMs) {
        StorageTimelineEngine.buildTimeline(
            clips = filteredClipsInput,
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

    // Convenience UI states
    var isFullscreen by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(true) }
    var showFilters by remember { mutableStateOf(false) }
    var useCropMode by remember { mutableStateOf(false) }
    var isLandscapeLocked by remember { mutableStateOf(false) }
    var showFullscreenHud by remember { mutableStateOf(true) }
    var showFullscreenDrawer by remember { mutableStateOf(false) }
    var doubleTapFeedback by remember { mutableStateOf<String?>(null) }
    var scrubPreviewFraction by remember { mutableStateOf<Float?>(null) }

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
            runCatching { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            timelinePlayer.removeListener(listener)
            timelinePlayer.release()
        }
    }

    // Restore orientation whenever exiting fullscreen
    LaunchedEffect(isFullscreen, isLandscapeLocked) {
        if (isFullscreen && isLandscapeLocked) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Auto-clear double-tap seek feedback pill
    LaunchedEffect(doubleTapFeedback) {
        if (doubleTapFeedback != null) {
            delay(850L)
            doubleTapFeedback = null
        }
    }

    // Auto-hide fullscreen HUD after 4s while playing and not scrubbing/opening drawer
    LaunchedEffect(isFullscreen, showFullscreenHud, isPlaying, showFullscreenDrawer) {
        if (isFullscreen && showFullscreenHud && isPlaying && !showFullscreenDrawer) {
            delay(4_000L)
            showFullscreenHud = false
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

    val motionIndices = remember(timeline) {
        timeline.segments.mapIndexedNotNull { idx, seg ->
            if (seg.clip.triggerType == ClipTriggerType.MOTION_ALERT) idx else null
        }
    }
    val prevMotionIndex = motionIndices.lastOrNull { it < currentSegmentIndex }
    val nextMotionIndex = motionIndices.firstOrNull { it > currentSegmentIndex }

    val seekRelativeMs: (Long) -> Unit = { deltaMs ->
        val segDur = activeSegment?.durationMs ?: 0L
        val targetPos = timelinePlayer.currentPosition + deltaMs
        when {
            targetPos < 0L && currentSegmentIndex > 0 -> {
                val prevSeg = timeline.segments[currentSegmentIndex - 1]
                val adjusted = (prevSeg.durationMs + targetPos).coerceAtLeast(0L)
                timelinePlayer.seekTo(currentSegmentIndex - 1, adjusted)
            }
            targetPos >= segDur && currentSegmentIndex < timeline.segments.lastIndex -> {
                val overflow = (targetPos - segDur).coerceAtLeast(0L)
                timelinePlayer.seekTo(currentSegmentIndex + 1, overflow)
            }
            else -> {
                timelinePlayer.seekTo(targetPos.coerceIn(0L, segDur))
            }
        }
    }

    val cycleSpeed: () -> Unit = {
        val nextSpeed = when (playbackSpeed) {
            0.5f -> 1f
            1f -> 1.5f
            1.5f -> 2f
            2f -> 4f
            4f -> 8f
            else -> 0.5f
        }
        playbackSpeed = nextSpeed
        timelinePlayer.setPlaybackSpeed(nextSpeed)
    }

    val togglePlayPause: () -> Unit = {
        if (timelinePlayer.isPlaying) {
            timelinePlayer.pause()
        } else {
            if (timelinePlayer.playbackState == Player.STATE_ENDED) {
                timelinePlayer.seekTo(0, 0L)
            }
            timelinePlayer.play()
        }
    }

    val seekTimelineFraction: (Float) -> Unit = { fraction ->
        StorageTimelineEngine.resolveSeekTarget(timeline, fraction)?.let { target ->
            currentSegmentIndex = target.segmentIndex
            currentPositionInClipMs = target.positionInClipMs
            timelinePlayer.seekTo(target.segmentIndex, target.positionInClipMs)
        }
    }

    val jumpToSegment: (Int) -> Unit = { idx ->
        if (idx in timeline.segments.indices) {
            currentSegmentIndex = idx
            currentPositionInClipMs = 0L
            timelinePlayer.seekTo(idx, 0L)
            if (!timelinePlayer.isPlaying) timelinePlayer.play()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("storage_timeline_view")
    ) {
        // Compact Top Utility Strip: Details Toggle, Filters Toggle, Fullscreen Button & Quick Stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (showFilters || selectedCameraId != null || selectedDayStartMs != null || motionOnlyFilter) {
                        NeonCyan.copy(alpha = 0.2f)
                    } else SurveillanceCardBg,
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showFilters = !showFilters }
                        .testTag("timeline_toggle_filters_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(Icons.Default.FilterList, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(15.dp))
                        Text(
                            text = stringResource(R.string.timeline_filters_btn),
                            color = NeonCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (showDetails) NeonCyan.copy(alpha = 0.2f) else SurveillanceCardBg,
                    border = BorderStroke(1.dp, if (showDetails) NeonCyan.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showDetails = !showDetails }
                        .testTag("timeline_toggle_details_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (showDetails) Icons.Filled.Info else Icons.Outlined.Info,
                            contentDescription = null,
                            tint = if (showDetails) NeonCyan else Color.LightGray,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = stringResource(
                                if (showDetails) R.string.timeline_details_on else R.string.timeline_details_off
                            ),
                            color = if (showDetails) NeonCyan else Color.LightGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (!timeline.isEmpty) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SurveillanceCardBg,
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            showFullscreenHud = true
                            isFullscreen = true
                        }
                        .testTag("timeline_fullscreen_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Fullscreen, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                        Text(
                            text = stringResource(R.string.timeline_fullscreen_btn),
                            color = NeonCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Collapsible Camera / Day / Motion Filter Strip
        AnimatedVisibility(visible = showFilters) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = selectedCameraId == null && selectedDayStartMs == null && !motionOnlyFilter,
                    onClick = {
                        selectedCameraId = null
                        selectedDayStartMs = null
                        motionOnlyFilter = false
                    },
                    label = { Text(stringResource(R.string.recordings_filter_all_cameras), fontSize = 11.sp, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonCyan.copy(alpha = 0.22f),
                        selectedLabelColor = NeonCyan,
                        labelColor = Color.White.copy(alpha = 0.8f)
                    ),
                    modifier = Modifier.testTag("timeline_filter_all_cameras")
                )
                FilterChip(
                    selected = motionOnlyFilter,
                    onClick = { motionOnlyFilter = !motionOnlyFilter },
                    label = { Text(stringResource(R.string.recordings_filter_motion), fontSize = 11.sp, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonAmber.copy(alpha = 0.24f),
                        selectedLabelColor = NeonAmber,
                        labelColor = Color.White.copy(alpha = 0.8f)
                    ),
                    modifier = Modifier.testTag("timeline_filter_motion_only")
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
            // Embedded 16:9 Player Card (yields to Fullscreen Dialog when expanded)
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
                        if (!isFullscreen) {
                            TimelineVideoSurface(
                                player = timelinePlayer,
                                useCropMode = useCropMode,
                                onSingleTap = togglePlayPause,
                                onDoubleTapLeft = {
                                    seekRelativeMs(-10_000L)
                                    doubleTapFeedback = "-10s"
                                },
                                onDoubleTapRight = {
                                    seekRelativeMs(10_000L)
                                    doubleTapFeedback = "+10s"
                                }
                            )
                        }

                        // Double-tap seek indicator
                        doubleTapFeedback?.let { pill ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color.Black.copy(alpha = 0.75f),
                                border = BorderStroke(1.dp, NeonCyan),
                                modifier = Modifier.align(Alignment.Center)
                            ) {
                                Text(
                                    text = pill,
                                    color = NeonCyan,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }
                        }

                        // Top Surveillance HUD Bar (shown when showDetails == true)
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showDetails,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.TopStart)
                        ) {
                            TimelineTopHud(
                                activeSegment = activeSegment,
                                currentSegmentIndex = currentSegmentIndex,
                                totalSegments = timeline.segments.size,
                                currentWallClockText = if (currentWallClockMs > 0L) wallClockFormatter.format(Date(currentWallClockMs)) else "",
                                useCropMode = useCropMode,
                                onToggleResize = { useCropMode = !useCropMode },
                                onEnterFullscreen = {
                                    showFullscreenHud = true
                                    isFullscreen = true
                                }
                            )
                        }
                    }

                    // Interactive Visual NVR Timeline Ruler + Transport
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurveillanceCardBg)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        // Floating Scrub Preview Bubble
                        scrubPreviewFraction?.let { frac ->
                            val previewTarget = StorageTimelineEngine.resolveSeekTarget(timeline, frac)
                            val previewSeg = previewTarget?.let { timeline.segments.getOrNull(it.segmentIndex) }
                            val previewClock = StorageTimelineEngine.resolveWallClockAt(
                                previewSeg,
                                previewTarget?.positionInClipMs ?: 0L
                            )
                            if (previewClock > 0L) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = NeonCyan,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                ) {
                                    Text(
                                        text = "${wallClockFormatter.format(Date(previewClock))} • ${previewSeg?.clip?.cameraName.orEmpty()}",
                                        color = Color.Black,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        TimelineRulerCanvas(
                            timeline = timeline,
                            currentSegmentIndex = currentSegmentIndex,
                            overallProgress = overallProgress,
                            onSeekFraction = seekTimelineFraction,
                            onScrubStateChanged = { scrubPreviewFraction = it }
                        )

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
                            if (showDetails) {
                                Text(
                                    text = stringResource(R.string.recordings_timeline_seamless_hint),
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        TimelineTransportBar(
                            isPlaying = isPlaying,
                            playbackSpeed = playbackSpeed,
                            isMuted = isMuted,
                            hasPrevChunk = currentSegmentIndex > 0,
                            hasNextChunk = currentSegmentIndex < timeline.segments.lastIndex,
                            hasPrevMotion = prevMotionIndex != null,
                            hasNextMotion = nextMotionIndex != null,
                            isFullscreen = false,
                            onPrevChunk = { jumpToSegment((currentSegmentIndex - 1).coerceAtLeast(0)) },
                            onRewind10 = { seekRelativeMs(-10_000L) },
                            onTogglePlayPause = togglePlayPause,
                            onForward10 = { seekRelativeMs(10_000L) },
                            onNextChunk = { jumpToSegment((currentSegmentIndex + 1).coerceAtMost(timeline.segments.lastIndex)) },
                            onPrevMotion = { prevMotionIndex?.let(jumpToSegment) },
                            onNextMotion = { nextMotionIndex?.let(jumpToSegment) },
                            onCycleSpeed = cycleSpeed,
                            onToggleMute = {
                                isMuted = !isMuted
                                timelinePlayer.volume = if (isMuted) 0f else 1f
                            },
                            onToggleFullscreen = {
                                showFullscreenHud = true
                                isFullscreen = true
                            }
                        )

                        if (showDetails) {
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
                    if (showDetails && segment.gapBeforeMs > 0L) {
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

                    TimelineSegmentRowCard(
                        segment = segment,
                        isCurrent = isCurrent,
                        isPlaying = isPlaying,
                        showDetails = showDetails,
                        timeOnlyFormatter = timeOnlyFormatter,
                        onClick = { jumpToSegment(segment.index) }
                    )
                }
            }
        }
    }

    // True Fullscreen Edge-to-Edge Timeline Watch Dialog
    if (isFullscreen && !timeline.isEmpty) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            BackHandler {
                if (showFullscreenDrawer) {
                    showFullscreenDrawer = false
                } else {
                    isFullscreen = false
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .testTag("timeline_fullscreen_dialog")
            ) {
                TimelineVideoSurface(
                    player = timelinePlayer,
                    useCropMode = useCropMode,
                    onSingleTap = { showFullscreenHud = !showFullscreenHud },
                    onDoubleTapLeft = {
                        seekRelativeMs(-10_000L)
                        doubleTapFeedback = "-10s"
                    },
                    onDoubleTapRight = {
                        seekRelativeMs(10_000L)
                        doubleTapFeedback = "+10s"
                    }
                )

                doubleTapFeedback?.let { pill ->
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color.Black.copy(alpha = 0.78f),
                        border = BorderStroke(1.5.dp, NeonCyan),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Text(
                            text = pill,
                            color = NeonCyan,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
                        )
                    }
                }

                // Fullscreen Top & Bottom HUD Overlay
                AnimatedVisibility(
                    visible = showFullscreenHud,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                    ) {
                        // Top bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                                    )
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                if (showDetails) {
                                    Text(
                                        text = activeSegment?.clip?.cameraName.orEmpty(),
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = buildString {
                                            if (currentWallClockMs > 0L) {
                                                append(wallClockFormatter.format(Date(currentWallClockMs)))
                                                append(" • ")
                                            }
                                            append("#${currentSegmentIndex + 1}/${timeline.segments.size}")
                                        },
                                        color = NeonCyan,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                } else {
                                    Text(
                                        text = activeSegment?.clip?.cameraName.orEmpty(),
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { showDetails = !showDetails },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = if (showDetails) Icons.Filled.Info else Icons.Outlined.Info,
                                        contentDescription = stringResource(R.string.timeline_toggle_details_desc),
                                        tint = if (showDetails) NeonCyan else Color.White
                                    )
                                }
                                IconButton(
                                    onClick = { useCropMode = !useCropMode },
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_resize_mode_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AspectRatio,
                                        contentDescription = stringResource(R.string.timeline_resize_mode_desc),
                                        tint = if (useCropMode) NeonCyan else Color.White
                                    )
                                }
                                IconButton(
                                    onClick = { isLandscapeLocked = !isLandscapeLocked },
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_rotate_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ScreenRotation,
                                        contentDescription = stringResource(R.string.timeline_rotate_screen_desc),
                                        tint = if (isLandscapeLocked) NeonCyan else Color.White
                                    )
                                }
                                IconButton(
                                    onClick = { showFullscreenDrawer = !showFullscreenDrawer },
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_fs_drawer_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.List,
                                        contentDescription = stringResource(R.string.timeline_segments_drawer_desc),
                                        tint = if (showFullscreenDrawer) NeonCyan else Color.White
                                    )
                                }
                                IconButton(
                                    onClick = { isFullscreen = false },
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_exit_fullscreen_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FullscreenExit,
                                        contentDescription = stringResource(R.string.timeline_exit_fullscreen_desc),
                                        tint = NeonCyan
                                    )
                                }
                            }
                        }

                        // Bottom Fullscreen Transport & Scrubber
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                                    )
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            scrubPreviewFraction?.let { frac ->
                                val previewTarget = StorageTimelineEngine.resolveSeekTarget(timeline, frac)
                                val previewSeg = previewTarget?.let { timeline.segments.getOrNull(it.segmentIndex) }
                                val previewClock = StorageTimelineEngine.resolveWallClockAt(
                                    previewSeg,
                                    previewTarget?.positionInClipMs ?: 0L
                                )
                                if (previewClock > 0L) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = NeonCyan,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    ) {
                                        Text(
                                            text = "${wallClockFormatter.format(Date(previewClock))} • ${previewSeg?.clip?.cameraName.orEmpty()}",
                                            color = Color.Black,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            TimelineRulerCanvas(
                                timeline = timeline,
                                currentSegmentIndex = currentSegmentIndex,
                                overallProgress = overallProgress,
                                onSeekFraction = seekTimelineFraction,
                                onScrubStateChanged = { scrubPreviewFraction = it }
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${StorageTimelineEngine.formatDurationHms(elapsedTotalMs)} / ${StorageTimelineEngine.formatDurationHms(timeline.totalFootageMs)}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                if (showDetails && activeSegment != null) {
                                    Text(
                                        text = "${activeSegment.clip.durationSeconds}s • ${activeSegment.clip.fileSizeMb} MB",
                                        color = Color.White.copy(alpha = 0.75f),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            TimelineTransportBar(
                                isPlaying = isPlaying,
                                playbackSpeed = playbackSpeed,
                                isMuted = isMuted,
                                hasPrevChunk = currentSegmentIndex > 0,
                                hasNextChunk = currentSegmentIndex < timeline.segments.lastIndex,
                                hasPrevMotion = prevMotionIndex != null,
                                hasNextMotion = nextMotionIndex != null,
                                isFullscreen = true,
                                onPrevChunk = { jumpToSegment((currentSegmentIndex - 1).coerceAtLeast(0)) },
                                onRewind10 = { seekRelativeMs(-10_000L) },
                                onTogglePlayPause = togglePlayPause,
                                onForward10 = { seekRelativeMs(10_000L) },
                                onNextChunk = { jumpToSegment((currentSegmentIndex + 1).coerceAtMost(timeline.segments.lastIndex)) },
                                onPrevMotion = { prevMotionIndex?.let(jumpToSegment) },
                                onNextMotion = { nextMotionIndex?.let(jumpToSegment) },
                                onCycleSpeed = cycleSpeed,
                                onToggleMute = {
                                    isMuted = !isMuted
                                    timelinePlayer.volume = if (isMuted) 0f else 1f
                                },
                                onToggleFullscreen = { isFullscreen = false }
                            )
                        }
                    }
                }

                // Slide-over Segments Drawer inside Fullscreen
                if (showFullscreenDrawer) {
                    Surface(
                        color = SurveillanceCardBg.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .widthIn(min = 260.dp, max = 330.dp)
                            .safeDrawingPadding()
                            .padding(vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.timeline_segments_drawer_desc),
                                    color = NeonCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(onClick = { showFullscreenDrawer = false }) {
                                    Text(stringResource(R.string.common_close), color = Color.LightGray, fontSize = 11.sp)
                                }
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(timeline.segments, key = { it.clip.id }) { segment ->
                                    TimelineSegmentRowCard(
                                        segment = segment,
                                        isCurrent = segment.index == currentSegmentIndex,
                                        isPlaying = isPlaying,
                                        showDetails = showDetails,
                                        timeOnlyFormatter = timeOnlyFormatter,
                                        onClick = {
                                            jumpToSegment(segment.index)
                                            showFullscreenDrawer = false
                                        }
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

@OptIn(UnstableApi::class)
@Composable
private fun TimelineVideoSurface(
    player: ExoPlayer,
    useCropMode: Boolean,
    onSingleTap: () -> Unit,
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = { offset ->
                        if (offset.x < size.width / 2f) {
                            onDoubleTapLeft()
                        } else {
                            onDoubleTapRight()
                        }
                    }
                )
            }
    ) {
        AndroidView(
            factory = { viewCtx ->
                (LayoutInflater.from(viewCtx).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    resizeMode = if (useCropMode) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { pv ->
                if (pv.player !== player) {
                    pv.player = player
                }
                val targetMode = if (useCropMode) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                if (pv.resizeMode != targetMode) {
                    pv.resizeMode = targetMode
                }
            },
            onRelease = { pv ->
                pv.player = null
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun TimelineTopHud(
    activeSegment: TimelineSegment?,
    currentSegmentIndex: Int,
    totalSegments: Int,
    currentWallClockText: String,
    useCropMode: Boolean,
    onToggleResize: () -> Unit,
    onEnterFullscreen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.62f))
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

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = currentWallClockText,
                    color = NeonCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(
                        R.string.recordings_timeline_chunk_counter,
                        currentSegmentIndex + 1,
                        totalSegments
                    ),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(
                onClick = onToggleResize,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AspectRatio,
                    contentDescription = stringResource(R.string.timeline_resize_mode_desc),
                    tint = if (useCropMode) NeonCyan else Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = onEnterFullscreen,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = stringResource(R.string.timeline_fullscreen_btn),
                    tint = NeonCyan,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun TimelineRulerCanvas(
    timeline: StorageTimeline,
    currentSegmentIndex: Int,
    overallProgress: Float,
    onSeekFraction: (Float) -> Unit,
    onScrubStateChanged: (Float?) -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SurveillanceSurface)
            .pointerInput(timeline) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        onSeekFraction((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
            .pointerInput(timeline) {
                detectHorizontalDragGestures(
                    onDragEnd = { onScrubStateChanged(null) },
                    onDragCancel = { onScrubStateChanged(null) }
                ) { change, _ ->
                    change.consume()
                    if (size.width > 0) {
                        val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onScrubStateChanged(frac)
                        onSeekFraction(frac)
                    }
                }
            }
            .testTag("timeline_scrubber_bar")
    ) {
        val w = size.width
        val h = size.height
        val totalMs = timeline.totalFootageMs.coerceAtLeast(1L).toFloat()

        for (seg in timeline.segments) {
            val startX = (seg.timelineStartOffsetMs.toFloat() / totalMs) * w
            val segW = ((seg.durationMs.toFloat() / totalMs) * w).coerceAtLeast(3f)
            val baseColor = when (seg.clip.triggerType) {
                ClipTriggerType.MOTION_ALERT -> NeonAmber
                ClipTriggerType.SCHEDULED -> NeonCyan
                ClipTriggerType.MANUAL -> NeonRed
            }
            val isCurrent = seg.index == currentSegmentIndex
            val alpha = if (isCurrent) 0.92f else 0.45f

            drawRoundRect(
                color = baseColor.copy(alpha = alpha),
                topLeft = Offset(startX + 1f, 6f),
                size = Size((segW - 2f).coerceAtLeast(2f), h - 12f),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }

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
}

@Composable
private fun TimelineTransportBar(
    isPlaying: Boolean,
    playbackSpeed: Float,
    isMuted: Boolean,
    hasPrevChunk: Boolean,
    hasNextChunk: Boolean,
    hasPrevMotion: Boolean,
    hasNextMotion: Boolean,
    isFullscreen: Boolean,
    onPrevChunk: () -> Unit,
    onRewind10: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onForward10: () -> Unit,
    onNextChunk: () -> Unit,
    onPrevMotion: () -> Unit,
    onNextMotion: () -> Unit,
    onCycleSpeed: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleFullscreen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevMotion,
            enabled = hasPrevMotion,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_prev_motion_btn")
        ) {
            Icon(
                Icons.Default.NotificationsActive,
                contentDescription = stringResource(R.string.timeline_prev_motion_desc),
                tint = if (hasPrevMotion) NeonAmber else Color.Gray.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp)
            )
        }

        IconButton(
            onClick = onPrevChunk,
            enabled = hasPrevChunk,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_prev_chunk_btn")
        ) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = stringResource(R.string.recordings_timeline_prev_chunk),
                tint = if (hasPrevChunk) Color.White else Color.Gray
            )
        }

        IconButton(
            onClick = onRewind10,
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
                .clickable(onClick = onTogglePlayPause)
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
            onClick = onForward10,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_forward_btn")
        ) {
            Icon(
                Icons.Default.Forward10,
                contentDescription = stringResource(R.string.recordings_timeline_forward),
                tint = Color.White
            )
        }

        IconButton(
            onClick = onNextChunk,
            enabled = hasNextChunk,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_next_chunk_btn")
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = stringResource(R.string.recordings_timeline_next_chunk),
                tint = if (hasNextChunk) Color.White else Color.Gray
            )
        }

        IconButton(
            onClick = onNextMotion,
            enabled = hasNextMotion,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_next_motion_btn")
        ) {
            Icon(
                Icons.Default.NotificationsActive,
                contentDescription = stringResource(R.string.timeline_next_motion_desc),
                tint = if (hasNextMotion) NeonAmber else Color.Gray.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp)
            )
        }

        val speedLabel = if (playbackSpeed % 1f == 0f) "${playbackSpeed.toInt()}x" else "${playbackSpeed}x"
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurveillanceSurface,
            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCycleSpeed)
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
            onClick = onToggleMute,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("timeline_mute_btn")
        ) {
            Icon(
                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(R.string.recordings_timeline_mute_toggle),
                tint = if (isMuted) Color.Gray else NeonCyan
            )
        }

        IconButton(
            onClick = onToggleFullscreen,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Icon(
                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = stringResource(
                    if (isFullscreen) R.string.timeline_exit_fullscreen_desc else R.string.timeline_fullscreen_btn
                ),
                tint = NeonCyan
            )
        }
    }
}

@Composable
private fun TimelineSegmentRowCard(
    segment: TimelineSegment,
    isCurrent: Boolean,
    isPlaying: Boolean,
    showDetails: Boolean,
    timeOnlyFormatter: SimpleDateFormat,
    onClick: () -> Unit
) {
    val triggerColor = when (segment.clip.triggerType) {
        ClipTriggerType.MOTION_ALERT -> NeonAmber
        ClipTriggerType.SCHEDULED -> NeonCyan
        ClipTriggerType.MANUAL -> NeonRed
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) NeonCyan.copy(alpha = 0.14f) else SurveillanceCardBg
        ),
        border = if (isCurrent) BorderStroke(1.dp, NeonCyan) else null,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                            text = if (showDetails) "#${segment.index + 1} • ${segment.clip.cameraName}" else segment.clip.cameraName,
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
                        text = if (showDetails) {
                            "${timeOnlyFormatter.format(Date(segment.startMs))} → ${timeOnlyFormatter.format(Date(segment.endMs))} • ${segment.clip.durationSeconds}s • ${segment.clip.fileSizeMb} MB"
                        } else {
                            "${timeOnlyFormatter.format(Date(segment.startMs))} • ${segment.clip.durationSeconds}s"
                        },
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
