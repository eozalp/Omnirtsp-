package com.example.engine

import android.content.Context
import android.net.Uri
import com.example.data.model.RecordedClipEntity
import java.io.File
import java.util.Calendar

data class TimelineSegment(
    val index: Int,
    val clip: RecordedClipEntity,
    val mediaUri: Uri?,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val timelineStartOffsetMs: Long,
    val gapBeforeMs: Long
)

data class StorageTimeline(
    val segments: List<TimelineSegment>,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val totalSpanMs: Long,
    val totalFootageMs: Long
) {
    val isEmpty: Boolean get() = segments.isEmpty()
}

data class TimelineSeekTarget(
    val segmentIndex: Int,
    val positionInClipMs: Long
)

/**
 * Pure timeline builder & scrubbing engine for seamless, gapless playback of stored video chunks.
 * Arranges saved recordings chronologically, preserves wall-clock timestamps across chunk boundaries,
 * and maps scrubber gestures directly to (playlistIndex, seekPositionMs).
 */
object StorageTimelineEngine {

    private const val CONTIGUOUS_TOLERANCE_MS = 5_000L

    fun dayStartMillis(timestampMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestampMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun computeAvailableDays(clips: List<RecordedClipEntity>): List<Long> {
        return clips
            .map { dayStartMillis(it.timestamp) }
            .distinct()
            .sortedDescending()
    }

    fun resolvePlayableUri(context: Context?, clip: RecordedClipEntity): Uri? {
        val internalFile = File(clip.filePath)
        if (internalFile.exists() && internalFile.length() > 32L) {
            return Uri.fromFile(internalFile)
        }
        val exportStr = clip.localExportUri
        if (!exportStr.isNullOrBlank()) {
            val uri = runCatching { Uri.parse(exportStr) }.getOrNull()
            if (uri != null) {
                if (uri.scheme == "file") {
                    val f = uri.path?.let { File(it) }
                    if (f != null && f.exists() && f.length() > 32L) return uri
                } else if (context != null) {
                    val exists = runCatching {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                    }.getOrDefault(false)
                    if (exists) return uri
                } else {
                    return uri
                }
            }
        }
        if (internalFile.exists() && internalFile.length() > 0L) {
            return Uri.fromFile(internalFile)
        }
        return null
    }

    fun buildTimeline(
        clips: List<RecordedClipEntity>,
        cameraIdFilter: String? = null,
        dayStartFilterMs: Long? = null,
        context: Context? = null,
        requirePlayable: Boolean = false
    ): StorageTimeline {
        val dayEndFilterMs = dayStartFilterMs?.let { start ->
            Calendar.getInstance().apply {
                timeInMillis = start
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }

        val sorted = clips
            .asSequence()
            .filter { cameraIdFilter == null || it.cameraId == cameraIdFilter }
            .filter {
                if (dayStartFilterMs == null || dayEndFilterMs == null) true
                else it.timestamp in dayStartFilterMs until dayEndFilterMs
            }
            .sortedBy { it.timestamp }
            .toList()

        val segments = mutableListOf<TimelineSegment>()
        var cumulativeMs = 0L
        var prevEndMs: Long? = null

        for (clip in sorted) {
            val uri = resolvePlayableUri(context, clip)
            if (requirePlayable && uri == null) {
                continue
            }
            val durationMs = clip.durationSeconds.coerceAtLeast(1) * 1000L
            val startMs = clip.timestamp
            val endMs = startMs + durationMs
            val gapBeforeMs = if (prevEndMs != null) {
                val rawGap = startMs - prevEndMs
                if (rawGap > CONTIGUOUS_TOLERANCE_MS) rawGap else 0L
            } else {
                0L
            }

            segments.add(
                TimelineSegment(
                    index = segments.size,
                    clip = clip,
                    mediaUri = uri,
                    startMs = startMs,
                    endMs = endMs,
                    durationMs = durationMs,
                    timelineStartOffsetMs = cumulativeMs,
                    gapBeforeMs = gapBeforeMs
                )
            )
            cumulativeMs += durationMs
            prevEndMs = endMs
        }

        if (segments.isEmpty()) {
            return StorageTimeline(
                segments = emptyList(),
                windowStartMs = 0L,
                windowEndMs = 0L,
                totalSpanMs = 0L,
                totalFootageMs = 0L
            )
        }

        val windowStart = segments.first().startMs
        val windowEnd = segments.last().endMs
        return StorageTimeline(
            segments = segments,
            windowStartMs = windowStart,
            windowEndMs = windowEnd,
            totalSpanMs = (windowEnd - windowStart).coerceAtLeast(1000L),
            totalFootageMs = cumulativeMs.coerceAtLeast(1000L)
        )
    }

    fun resolveSeekTarget(timeline: StorageTimeline, continuousFraction: Float): TimelineSeekTarget? {
        if (timeline.segments.isEmpty() || timeline.totalFootageMs <= 0L) return null
        val clamped = continuousFraction.coerceIn(0f, 1f)
        val targetOffsetMs = (clamped * timeline.totalFootageMs).toLong()
            .coerceIn(0L, (timeline.totalFootageMs - 1L).coerceAtLeast(0L))

        for (segment in timeline.segments) {
            val segEnd = segment.timelineStartOffsetMs + segment.durationMs
            if (targetOffsetMs < segEnd || segment.index == timeline.segments.lastIndex) {
                val posInClip = (targetOffsetMs - segment.timelineStartOffsetMs)
                    .coerceIn(0L, (segment.durationMs - 1L).coerceAtLeast(0L))
                return TimelineSeekTarget(segment.index, posInClip)
            }
        }
        return TimelineSeekTarget(0, 0L)
    }

    fun computeOverallProgress(
        timeline: StorageTimeline,
        currentIndex: Int,
        positionInClipMs: Long
    ): Float {
        if (timeline.segments.isEmpty() || timeline.totalFootageMs <= 0L) return 0f
        val segment = timeline.segments.getOrNull(currentIndex) ?: return 0f
        val clampedPos = positionInClipMs.coerceIn(0L, segment.durationMs)
        val globalOffset = segment.timelineStartOffsetMs + clampedPos
        return (globalOffset.toFloat() / timeline.totalFootageMs.toFloat()).coerceIn(0f, 1f)
    }

    fun resolveWallClockAt(segment: TimelineSegment?, positionInClipMs: Long): Long {
        if (segment == null) return 0L
        return segment.startMs + positionInClipMs.coerceIn(0L, segment.durationMs)
    }

    fun formatDurationHms(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }
}
