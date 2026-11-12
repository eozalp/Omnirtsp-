package com.example.engine

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.model.CloudConfigEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class RetentionStatus(
    val isEnabled: Boolean = true,
    val windowLabel: String = "3d",
    val totalClipsKept: Int = 0,
    val totalUsedMb: Float = 0f,
    val lastPrunedCount: Int = 0,
    val totalPrunedSession: Int = 0,
    val lastPrunedTimestamp: Long = 0L
)

/**
 * Enforces rolling retention (ring-buffer auto-replace) for recordings & motion alerts.
 * Data older than (now - configured Days/Hours/Minutes limit) is deleted cleanly from:
 * 1. App-private recordings folder (`filesDir/recordings`)
 * 2. Picked device/remote SAF folder or MediaStore `Movies/OmniView`
 * 3. Room database (`recorded_clips` & `motion_alerts`)
 */
class StorageRetentionEngine(
    private val context: Context,
    private val database: AppDatabase
) {
    private val mutex = Mutex()
    private var cumulativePruned = 0

    private val _status = MutableStateFlow(RetentionStatus())
    val status: StateFlow<RetentionStatus> = _status.asStateFlow()

    suspend fun enforceRetention(nowMillis: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val config = database.cloudConfigDao().getCloudConfigSync() ?: CloudConfigEntity()
                val windowMs = config.totalRetentionMillis()
                var prunedCount = 0

                if (config.retentionEnabled && windowMs > 0L) {
                    val cutoff = nowMillis - windowMs
                    val expiredClips = database.recordedClipDao().getClipsOlderThan(cutoff)
                    for (clip in expiredClips) {
                        runCatching {
                            val localFile = File(clip.filePath)
                            if (localFile.exists()) {
                                localFile.delete()
                            }
                        }
                        if (!clip.localExportUri.isNullOrBlank()) {
                            DeviceStorageExporter.deleteExportedClip(context, clip.localExportUri)
                        }
                    }

                    if (expiredClips.isNotEmpty()) {
                        prunedCount += database.recordedClipDao().deleteClipsOlderThan(cutoff)
                    }

                    // Sweep any orphaned raw files older than cutoff inside internal recordings folder
                    val recordingsDir = File(context.filesDir, "recordings")
                    if (recordingsDir.exists()) {
                        recordingsDir.listFiles()?.forEach { file ->
                            if (file.isFile && file.lastModified() > 0L && file.lastModified() < cutoff) {
                                if (file.delete()) {
                                    prunedCount++
                                }
                            }
                        }
                    }

                    // Roll motion alerts on the same retention horizon so logs don't grow unbounded
                    database.motionAlertDao().deleteAlertsOlderThan(cutoff)
                }

                if (prunedCount > 0) {
                    cumulativePruned += prunedCount
                }

                val remaining = database.recordedClipDao().getAllClipsSync()
                val totalMb = remaining.sumOf { it.fileSizeMb.toDouble() }.toFloat()
                _status.value = RetentionStatus(
                    isEnabled = config.retentionEnabled && windowMs > 0L,
                    windowLabel = config.formatRetentionWindow(),
                    totalClipsKept = remaining.size,
                    totalUsedMb = (Math.round(totalMb * 100f) / 100f),
                    lastPrunedCount = prunedCount,
                    totalPrunedSession = cumulativePruned,
                    lastPrunedTimestamp = if (prunedCount > 0) nowMillis else _status.value.lastPrunedTimestamp
                )
                prunedCount
            }
        }

    companion object {
        fun computeCutoffTimestamp(
            nowMillis: Long,
            days: Int,
            hours: Int,
            minutes: Int
        ): Long? {
            val totalMs = CloudConfigEntity(
                retentionEnabled = true,
                retentionDays = days,
                retentionHours = hours,
                retentionMinutes = minutes
            ).totalRetentionMillis()
            return if (totalMs > 0L) nowMillis - totalMs else null
        }

        fun filterRetainedTimestamps(
            timestamps: List<Long>,
            cutoffTimestamp: Long
        ): List<Long> = timestamps.filter { it >= cutoffTimestamp }
    }
}
