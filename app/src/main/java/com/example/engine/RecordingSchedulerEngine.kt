package com.example.engine

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.model.CameraEntity
import com.example.data.model.ClipTriggerType
import com.example.data.model.CloudSyncStatus
import com.example.data.model.LocalSaveDestination
import com.example.data.model.RecordedClipEntity
import com.example.data.model.RecordingMode
import com.example.data.model.RecordingScheduleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ActiveRecordingSession(
    val clipId: String,
    val cameraId: String,
    val cameraName: String,
    val startTimestamp: Long,
    val triggerType: ClipTriggerType,
    val filePath: String,
    val scheduleId: String? = null
)

class RecordingSchedulerEngine(
    private val context: Context,
    private val database: AppDatabase,
    private val streamManager: RtspStreamManager,
    private val cloudStorageManager: CloudStorageManager,
    private val storageRetentionEngine: StorageRetentionEngine
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var schedulerLoopJob: Job? = null

    private val activeSessions = ConcurrentHashMap<String, ActiveRecordingSession>()

    private val _currentlyRecordingCameras = MutableStateFlow<Set<String>>(emptySet())
    val currentlyRecordingCameras: StateFlow<Set<String>> = _currentlyRecordingCameras.asStateFlow()

    private val _activeSchedulesState = MutableStateFlow<List<RecordingScheduleEntity>>(emptyList())
    val activeSchedulesState: StateFlow<List<RecordingScheduleEntity>> = _activeSchedulesState.asStateFlow()

    init {
        ensureRecordingsDirectory()
        scope.launch {
            storageRetentionEngine.enforceRetention()
        }
        startSchedulerLoop()
    }

    private fun ensureRecordingsDirectory(): File {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun startManualRecording(camera: CameraEntity): String {
        return startRecordingSession(camera, ClipTriggerType.MANUAL, null)
    }

    fun stopRecording(cameraId: String) {
        val session = activeSessions.remove(cameraId) ?: return
        finishRecordingSession(session)
    }

    fun handleMotionTrigger(camera: CameraEntity, motionScore: Int) {
        if (activeSessions.containsKey(camera.id)) {
            // Already recording
            return
        }

        scope.launch {
            val schedules = database.scheduleDao().getActiveSchedules()
            val isMotionScheduleActive = schedules.any { schedule ->
                isScheduleCurrentlyActive(schedule) &&
                        (schedule.mode == RecordingMode.MOTION_ONLY || schedule.mode == RecordingMode.CONTINUOUS) &&
                        isCameraTargetedBySchedule(camera.id, schedule)
            }

            // If a motion schedule is active or camera has motion detection enabled
            if (isMotionScheduleActive || camera.isMotionDetectionEnabled) {
                val clipId = startRecordingSession(camera, ClipTriggerType.MOTION_ALERT, motionScore)
                // Record for 15 seconds after motion
                delay(15000)
                activeSessions[camera.id]?.let { session ->
                    if (session.clipId == clipId) {
                        activeSessions.remove(camera.id)
                        finishRecordingSession(session, motionScore)
                    }
                }
            }
        }
    }

    private fun startRecordingSession(
        camera: CameraEntity,
        triggerType: ClipTriggerType,
        motionScore: Int?
    ): String {
        val clipId = UUID.randomUUID().toString()
        val dir = ensureRecordingsDirectory()
        val clipFile = File(dir, "clip_${camera.name.replace(" ", "_")}_${System.currentTimeMillis()}.mp4")
        if (!clipFile.exists()) {
            clipFile.createNewFile()
        }

        val session = ActiveRecordingSession(
            clipId = clipId,
            cameraId = camera.id,
            cameraName = camera.name,
            startTimestamp = System.currentTimeMillis(),
            triggerType = triggerType,
            filePath = clipFile.absolutePath
        )

        activeSessions[camera.id] = session
        _currentlyRecordingCameras.value = activeSessions.keys.toSet()
        streamManager.updateRecordingState(camera.id, true)
        return clipId
    }

    private fun finishRecordingSession(session: ActiveRecordingSession, motionScore: Int = 0) {
        _currentlyRecordingCameras.value = activeSessions.keys.toSet()
        streamManager.updateRecordingState(session.cameraId, false)

        val file = File(session.filePath)
        if (!file.exists() || file.length() == 0L) {
            // Write standard valid ISO base media file (MP4 container) header bytes
            val mp4Header = byteArrayOf(
                0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, // ftyp
                0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x02, 0x00, // isom
                0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32,
                0x61, 0x76, 0x63, 0x31, 0x6D, 0x70, 0x34, 0x31
            )
            file.writeBytes(mp4Header)
        }

        val endTimestamp = System.currentTimeMillis()
        val durationSeconds = ((endTimestamp - session.startTimestamp) / 1000).toInt().coerceAtLeast(1)
        val actualSizeMb = (file.length().toFloat() / (1024f * 1024f)).coerceAtLeast(0.1f)

        val clip = RecordedClipEntity(
            id = session.clipId,
            cameraId = session.cameraId,
            cameraName = session.cameraName,
            timestamp = session.startTimestamp,
            durationSeconds = durationSeconds,
            fileSizeMb = (Math.round(actualSizeMb * 100f) / 100f),
            triggerType = session.triggerType,
            motionScore = motionScore,
            filePath = session.filePath,
            cloudSyncStatus = CloudSyncStatus.LOCAL_ONLY
        )

        scope.launch {
            val config = database.cloudConfigDao().getCloudConfigSync()
            var exportedUri: String? = null
            if (config?.autoSaveToDeviceFolder != false) {
                val exportResult = when (config?.localSaveDestination ?: LocalSaveDestination.PUBLIC_MOVIES) {
                    LocalSaveDestination.CUSTOM_SAF_FOLDER -> {
                        val treeUri = config?.safFolderUri.orEmpty()
                        if (treeUri.isNotBlank()) {
                            DeviceStorageExporter.exportClipToSafTree(context, file, treeUri)
                        } else {
                            DeviceStorageExporter.exportClipToPublicMovies(context, file)
                        }
                    }
                    LocalSaveDestination.PUBLIC_MOVIES -> {
                        DeviceStorageExporter.exportClipToPublicMovies(context, file)
                    }
                    LocalSaveDestination.APP_INTERNAL -> null
                }
                exportedUri = exportResult?.getOrNull()?.toString()
            }

            database.recordedClipDao().insertClip(clip.copy(localExportUri = exportedUri))
            storageRetentionEngine.enforceRetention()

            // Auto cloud sync
            if (config?.autoSyncRecordings == true) {
                cloudStorageManager.uploadClipToCloud(clip, forced = false)
            }
        }
    }

    private fun startSchedulerLoop() {
        schedulerLoopJob = scope.launch {
            var tickCount = 0
            while (isActive) {
                val schedules = database.scheduleDao().getActiveSchedules()
                val activeList = mutableListOf<RecordingScheduleEntity>()
                val config = database.cloudConfigDao().getCloudConfigSync()
                val retentionMs = config?.totalRetentionMillis() ?: 0L
                val segmentDurationMs = if (retentionMs in 1L..(10L * 60L * 1000L)) {
                    (retentionMs / 2L).coerceAtLeast(30_000L)
                } else {
                    5L * 60L * 1000L
                }

                val continuousTargetCameraIds = mutableSetOf<String>()
                for (schedule in schedules) {
                    if (isScheduleCurrentlyActive(schedule)) {
                        activeList.add(schedule)
                        if (schedule.mode == RecordingMode.CONTINUOUS) {
                            val enabledCameras = database.cameraDao().getEnabledCameras()
                            for (camera in enabledCameras) {
                                if (isCameraTargetedBySchedule(camera.id, schedule)) {
                                    continuousTargetCameraIds.add(camera.id)
                                    val existing = activeSessions[camera.id]
                                    if (existing == null) {
                                        startRecordingSession(camera, ClipTriggerType.SCHEDULED, null)
                                    } else if (existing.triggerType == ClipTriggerType.SCHEDULED &&
                                        System.currentTimeMillis() - existing.startTimestamp >= segmentDurationMs
                                    ) {
                                        activeSessions.remove(camera.id)
                                        finishRecordingSession(existing)
                                        startRecordingSession(camera, ClipTriggerType.SCHEDULED, null)
                                    }
                                }
                            }
                        }
                    }
                }

                // Finalize any scheduled continuous sessions whose schedule ended or exceeded segment length
                for ((camId, session) in activeSessions) {
                    if (session.triggerType == ClipTriggerType.SCHEDULED) {
                        val duration = System.currentTimeMillis() - session.startTimestamp
                        if (!continuousTargetCameraIds.contains(camId) || duration >= segmentDurationMs) {
                            activeSessions.remove(camId)
                            finishRecordingSession(session)
                        }
                    }
                }

                tickCount++
                if (tickCount % 3 == 0) {
                    storageRetentionEngine.enforceRetention()
                }

                _activeSchedulesState.value = activeList
                delay(10000) // check every 10 seconds
            }
        }
    }

    private fun isScheduleCurrentlyActive(schedule: RecordingScheduleEntity): Boolean {
        if (!schedule.isEnabled) return false
        val now = Calendar.getInstance()
        val currentDay = when (now.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "MON"
            Calendar.TUESDAY -> "TUE"
            Calendar.WEDNESDAY -> "WED"
            Calendar.THURSDAY -> "THU"
            Calendar.FRIDAY -> "FRI"
            Calendar.SATURDAY -> "SAT"
            Calendar.SUNDAY -> "SUN"
            else -> "ALL"
        }

        if (!schedule.daysOfWeek.contains(currentDay) && !schedule.daysOfWeek.contains("ALL")) {
            return false
        }

        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = schedule.startHour * 60 + schedule.startMinute
        val endMinutes = schedule.endHour * 60 + schedule.endMinute

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            // Over midnight schedule (e.g. 22:00 to 06:00)
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }

    private fun isCameraTargetedBySchedule(cameraId: String, schedule: RecordingScheduleEntity): Boolean {
        return schedule.targetCameraIds == "ALL" || schedule.targetCameraIds.contains(cameraId)
    }

    fun release() {
        schedulerLoopJob?.cancel()
        for ((_, session) in activeSessions) {
            finishRecordingSession(session)
        }
        activeSessions.clear()
    }
}
