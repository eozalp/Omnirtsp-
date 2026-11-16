package com.example.engine

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.model.CameraEntity
import com.example.data.model.ClipTriggerType
import com.example.data.model.CloudSyncStatus
import com.example.data.model.LocalSaveDestination
import com.example.data.model.RecordedClipEntity
import com.example.data.model.RecordingMode
import com.example.data.model.RecordingScheduleEntity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    val scheduleId: String? = null,
    val cameraSnapshot: CameraEntity? = null
)

class RecordingSchedulerEngine(
    private val context: Context,
    private val database: AppDatabase,
    private val streamManager: RtspStreamManager,
    private val cloudStorageManager: CloudStorageManager,
    private val storageRetentionEngine: StorageRetentionEngine
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("RecordingScheduler", "Coroutine error: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private var schedulerLoopJob: Job? = null
    private val cameraStreamRecorder = CameraStreamRecorder(context, streamManager)

    private val activeSessions = ConcurrentHashMap<String, ActiveRecordingSession>()
    private val cameraLocks = ConcurrentHashMap<String, Any>()
    private val lastCodecFinalizeJobs = ConcurrentHashMap<String, Job>()
    private val finalizeJobs = ConcurrentHashMap<String, Job>()
    private val recoveringClipIds = ConcurrentHashMap<String, Boolean>()
    private val consecutiveZeroFrameFailures = ConcurrentHashMap<String, Int>()
    private val nextScheduledRetryAtMs = ConcurrentHashMap<String, Long>()

    private val _currentlyRecordingCameras = MutableStateFlow<Set<String>>(emptySet())
    val currentlyRecordingCameras: StateFlow<Set<String>> = _currentlyRecordingCameras.asStateFlow()

    private val _activeSchedulesState = MutableStateFlow<List<RecordingScheduleEntity>>(emptyList())
    val activeSchedulesState: StateFlow<List<RecordingScheduleEntity>> = _activeSchedulesState.asStateFlow()

    private val _recordingErrors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val recordingErrors: SharedFlow<String> = _recordingErrors.asSharedFlow()

    init {
        ensureRecordingsDirectory()
        storageRetentionEngine.activeRecordingPathsProvider = {
            activeSessions.values.map { it.filePath }.toSet()
        }
        repairExistingEmptyRecordings()
        scope.launch {
            runCatching { storageRetentionEngine.enforceRetention() }
        }
        startSchedulerLoop()
    }

    private fun cameraLock(cameraId: String): Any =
        cameraLocks.getOrPut(cameraId) { Any() }

    private fun ensureRecordingsDirectory(): File {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun buildClipFile(camera: CameraEntity): File {
        val dir = ensureRecordingsDirectory()
        val safeName = camera.name.replace(" ", "_").ifBlank { camera.id }
        return File(dir, "clip_${safeName}_${System.currentTimeMillis()}.mp4")
    }

    fun startManualRecording(camera: CameraEntity): String {
        synchronized(cameraLock(camera.id)) {
            val existing = activeSessions[camera.id]
            if (existing != null &&
                existing.triggerType == ClipTriggerType.MANUAL &&
                cameraStreamRecorder.isRecorderAlive(camera.id, existing.clipId)
            ) {
                return existing.clipId
            }
            consecutiveZeroFrameFailures.remove(camera.id)
            nextScheduledRetryAtMs.remove(camera.id)
            if (existing != null) {
                return rotateRecordingSessionLocked(existing, camera, ClipTriggerType.MANUAL)
            }
            return startRecordingSessionLocked(camera, ClipTriggerType.MANUAL, null)
        }
    }

    fun stopRecording(cameraId: String) {
        synchronized(cameraLock(cameraId)) {
            consecutiveZeroFrameFailures.remove(cameraId)
            nextScheduledRetryAtMs.remove(cameraId)
            val session = activeSessions.remove(cameraId) ?: return
            finishRecordingSession(session, keepRecordingState = false, emitErrorOnEmpty = true)
        }
    }

    fun handleMotionTrigger(camera: CameraEntity, motionScore: Int) {
        if (activeSessions.containsKey(camera.id)) {
            // Already recording
            return
        }

        scope.launch {
            try {
                val schedules = database.scheduleDao().getActiveSchedules()
                val isMotionScheduleActive = schedules.any { schedule ->
                    isScheduleCurrentlyActive(schedule) &&
                            (schedule.mode == RecordingMode.MOTION_ONLY || schedule.mode == RecordingMode.CONTINUOUS) &&
                            isCameraTargetedBySchedule(camera.id, schedule)
                }

                // If a motion schedule is active or camera has motion detection enabled
                if (isMotionScheduleActive || camera.isMotionDetectionEnabled) {
                    val clipId = synchronized(cameraLock(camera.id)) {
                        if (activeSessions.containsKey(camera.id)) return@launch
                        startRecordingSessionLocked(camera, ClipTriggerType.MOTION_ALERT, motionScore)
                    }
                    // Record for 15 seconds after motion
                    delay(15000)
                    synchronized(cameraLock(camera.id)) {
                        val session = activeSessions[camera.id]
                        if (session != null && session.clipId == clipId) {
                            activeSessions.remove(camera.id)
                            finishRecordingSession(session, motionScore, keepRecordingState = false, emitErrorOnEmpty = true)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w("RecordingScheduler", "handleMotionTrigger failed: ${t.message}")
            }
        }
    }

    private fun repairExistingEmptyRecordings() {
        scope.launch(Dispatchers.IO) {
            try {
                val clips = database.recordedClipDao().getAllClipsSync()
                for (clip in clips) {
                    val file = File(clip.filePath)
                    if (StorageTimelineEngine.isPlayableClipFile(file)) {
                        val realMb = (Math.round((file.length().toFloat() / (1024f * 1024f)) * 100f) / 100f).coerceAtLeast(0.1f)
                        if (clip.fileSizeMb != realMb) {
                            database.recordedClipDao().updateClip(clip.copy(fileSizeMb = realMb))
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun startRecordingSessionLocked(
        camera: CameraEntity,
        triggerType: ClipTriggerType,
        motionScore: Int?,
        waitForCodecJob: Job? = null
    ): String {
        val clipId = UUID.randomUUID().toString()
        val clipFile = buildClipFile(camera)

        val session = ActiveRecordingSession(
            clipId = clipId,
            cameraId = camera.id,
            cameraName = camera.name,
            startTimestamp = System.currentTimeMillis(),
            triggerType = triggerType,
            filePath = clipFile.absolutePath,
            cameraSnapshot = camera
        )

        activeSessions[camera.id] = session
        _currentlyRecordingCameras.value = activeSessions.keys.toSet()
        streamManager.updateRecordingState(camera.id, true)

        cameraStreamRecorder.startRecording(
            clipId = clipId,
            camera = camera,
            outputFile = clipFile,
            scope = scope,
            waitForPreviousFinalize = waitForCodecJob ?: lastCodecFinalizeJobs[camera.id],
            onUnexpectedStop = { failed -> handleUnexpectedRecorderStop(failed) }
        )
        return clipId
    }

    private fun rotateRecordingSessionLocked(
        existing: ActiveRecordingSession,
        camera: CameraEntity,
        newTriggerType: ClipTriggerType
    ): String {
        val newClipId = UUID.randomUUID().toString()
        val newFile = buildClipFile(camera)
        val newSession = ActiveRecordingSession(
            clipId = newClipId,
            cameraId = camera.id,
            cameraName = camera.name,
            startTimestamp = System.currentTimeMillis(),
            triggerType = newTriggerType,
            filePath = newFile.absolutePath,
            cameraSnapshot = camera
        )

        // Swap session atomically BEFORE finalizing previous chunk so _currentlyRecordingCameras
        // never flickers empty during chunk boundaries and KeepAlive service stays uninterrupted.
        activeSessions[camera.id] = newSession
        _currentlyRecordingCameras.value = activeSessions.keys.toSet()
        streamManager.updateRecordingState(camera.id, true)

        finishRecordingSession(
            session = existing,
            keepRecordingState = true,
            emitErrorOnEmpty = false
        )
        val codecFinalizeJob = lastCodecFinalizeJobs[camera.id]

        cameraStreamRecorder.startRecording(
            clipId = newClipId,
            camera = camera,
            outputFile = newFile,
            scope = scope,
            waitForPreviousFinalize = codecFinalizeJob,
            onUnexpectedStop = { failed -> handleUnexpectedRecorderStop(failed) }
        )
        return newClipId
    }

    private fun handleUnexpectedRecorderStop(failed: CameraStreamRecorder.ActiveRecorder) {
        val cameraId = failed.camera.id
        if (recoveringClipIds.putIfAbsent(failed.clipId, true) != null) return

        scope.launch {
            var placeholderClipId: String? = null
            try {
                val active: ActiveRecordingSession?
                val shouldRecoverContinuous: Boolean
                var codecFinalizeJob: Job? = null

                synchronized(cameraLock(cameraId)) {
                    active = activeSessions[cameraId]
                    if (active == null || active.clipId != failed.clipId) {
                        return@launch
                    }

                    if (active.triggerType == ClipTriggerType.MOTION_ALERT) {
                        activeSessions.remove(cameraId, active)
                        finishRecordingSession(
                            session = active,
                            keepRecordingState = false,
                            emitErrorOnEmpty = failed.frameCount == 0 && !failed.initFailed
                        )
                        return@launch
                    }

                    shouldRecoverContinuous = true
                    val hadFrames = failed.frameCount > 0
                    val failures = if (hadFrames) {
                        consecutiveZeroFrameFailures[cameraId] = 0
                        0
                    } else {
                        ((consecutiveZeroFrameFailures[cameraId] ?: 0) + 1).also {
                            consecutiveZeroFrameFailures[cameraId] = it
                        }
                    }

                    val nextClipId = UUID.randomUUID().toString()
                    val nextFile = buildClipFile(failed.camera)
                    placeholderClipId = nextClipId
                    recoveringClipIds[nextClipId] = true

                    val placeholderSession = ActiveRecordingSession(
                        clipId = nextClipId,
                        cameraId = active.cameraId,
                        cameraName = active.cameraName,
                        startTimestamp = System.currentTimeMillis(),
                        triggerType = active.triggerType,
                        filePath = nextFile.absolutePath,
                        cameraSnapshot = active.cameraSnapshot ?: failed.camera
                    )
                    activeSessions[cameraId] = placeholderSession
                    _currentlyRecordingCameras.value = activeSessions.keys.toSet()
                    streamManager.updateRecordingState(cameraId, true)

                    finishRecordingSession(
                        session = active,
                        keepRecordingState = true,
                        emitErrorOnEmpty = shouldEmitRecordingError(hadFrames, failures)
                    )
                    codecFinalizeJob = lastCodecFinalizeJobs[cameraId]
                }

                if (!shouldRecoverContinuous || active == null) return@launch

                runCatching { withTimeoutOrNull(4_500L) { codecFinalizeJob?.join() } }

                val failures = consecutiveZeroFrameFailures[cameraId] ?: 0
                val backoffMs = if (failed.frameCount > 0) 400L else computeRetryBackoffMs(failures)
                nextScheduledRetryAtMs[cameraId] = System.currentTimeMillis() + backoffMs

                var waited = 0L
                while (waited < backoffMs && isActive) {
                    val step = minOf(200L, backoffMs - waited)
                    delay(step)
                    waited += step
                    val current = activeSessions[cameraId]
                    if (current == null || current.clipId != placeholderClipId) {
                        return@launch
                    }
                }

                val latestCamera = runCatching { database.cameraDao().getCameraById(cameraId) }.getOrNull()
                    ?: active.cameraSnapshot
                    ?: failed.camera

                synchronized(cameraLock(cameraId)) {
                    val current = activeSessions[cameraId]
                    if (current == null || current.clipId != placeholderClipId) {
                        return@launch
                    }
                    val targetFile = File(current.filePath)
                    val refreshedSession = current.copy(
                        startTimestamp = System.currentTimeMillis(),
                        cameraSnapshot = latestCamera
                    )
                    activeSessions[cameraId] = refreshedSession
                    _currentlyRecordingCameras.value = activeSessions.keys.toSet()
                    streamManager.updateRecordingState(cameraId, true)

                    cameraStreamRecorder.startRecording(
                        clipId = refreshedSession.clipId,
                        camera = latestCamera,
                        outputFile = targetFile,
                        scope = scope,
                        waitForPreviousFinalize = lastCodecFinalizeJobs[cameraId],
                        onUnexpectedStop = { nextFailed -> handleUnexpectedRecorderStop(nextFailed) }
                    )
                }
            } catch (t: Throwable) {
                Log.w("RecordingScheduler", "Auto-recovery failed for $cameraId: ${t.message}")
            } finally {
                placeholderClipId?.let { recoveringClipIds.remove(it) }
                recoveringClipIds.remove(failed.clipId)
            }
        }
    }

    private fun finishRecordingSession(
        session: ActiveRecordingSession,
        motionScore: Int = 0,
        keepRecordingState: Boolean = false,
        emitErrorOnEmpty: Boolean = true
    ): Job {
        if (!keepRecordingState) {
            _currentlyRecordingCameras.value = activeSessions.keys.toSet()
            streamManager.updateRecordingState(session.cameraId, activeSessions.containsKey(session.cameraId))
        }

        val file = File(session.filePath)
        val detachedRecorder = cameraStreamRecorder.detachRecorder(session.cameraId, expectedClipId = session.clipId)

        val endTimestamp = System.currentTimeMillis()
        val durationSeconds = ((endTimestamp - session.startTimestamp) / 1000).toInt().coerceAtLeast(1)

        val codecFinalizeJob = scope.launch {
            if (detachedRecorder != null) {
                runCatching { cameraStreamRecorder.finalizeRecorder(detachedRecorder) }
            }
        }
        lastCodecFinalizeJobs[session.cameraId] = codecFinalizeJob

        val job = scope.launch {
            try {
                codecFinalizeJob.join()

                if (!StorageTimelineEngine.isPlayableClipFile(file)) {
                    runCatching {
                        if (file.exists()) file.delete()
                    }
                    Log.w("RecordingScheduler", "No real video frames decoded for ${session.cameraName}; discarded empty file")
                    if (emitErrorOnEmpty) {
                        _recordingErrors.tryEmit(session.cameraName)
                    }
                    return@launch
                }

                consecutiveZeroFrameFailures[session.cameraId] = 0
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
                runCatching { storageRetentionEngine.enforceRetention() }

                // Auto cloud sync
                if (config?.autoSyncRecordings == true) {
                    cloudStorageManager.uploadClipToCloud(clip, forced = false)
                }
            } catch (t: Throwable) {
                Log.w("RecordingScheduler", "finishRecordingSession failed for ${session.cameraId}: ${t.message}")
            } finally {
                finalizeJobs.remove(session.clipId)
            }
        }
        finalizeJobs[session.clipId] = job
        return job
    }

    /**
     * Stops all active recordings and awaits their MP4 moov-atom flush + DB persistence
     * before complete app shutdown.
     */
    suspend fun stopAllRecordingsAndFlush(timeoutMs: Long = 6_000L) {
        schedulerLoopJob?.cancel()
        val sessionsToStop = activeSessions.entries.mapNotNull { (camId, session) ->
            synchronized(cameraLock(camId)) {
                if (activeSessions.remove(camId, session)) session else null
            }
        }
        val jobs = sessionsToStop.map {
            finishRecordingSession(it, keepRecordingState = false, emitErrorOnEmpty = false)
        } + finalizeJobs.values.toList()
        if (jobs.isNotEmpty()) {
            withTimeoutOrNull(timeoutMs) {
                jobs.joinAll()
            }
        }
    }

    private fun startSchedulerLoop() {
        schedulerLoopJob = scope.launch {
            var tickCount = 0
            while (isActive) {
                try {
                    val schedules = database.scheduleDao().getActiveSchedules()
                    val activeList = mutableListOf<RecordingScheduleEntity>()
                    val config = database.cloudConfigDao().getCloudConfigSync()
                    val segmentDurationMs = config?.totalChunkMillis() ?: (5L * 60L * 1000L)
                    val enabledCameras = database.cameraDao().getEnabledCameras()
                    val camerasById = enabledCameras.associateBy { it.id }

                    val continuousTargetCameraIds = mutableSetOf<String>()
                    val now = System.currentTimeMillis()
                    for (schedule in schedules) {
                        if (isScheduleCurrentlyActive(schedule)) {
                            activeList.add(schedule)
                            if (schedule.mode == RecordingMode.CONTINUOUS) {
                                for (camera in enabledCameras) {
                                    if (isCameraTargetedBySchedule(camera.id, schedule)) {
                                        continuousTargetCameraIds.add(camera.id)
                                        val retryAllowed = now >= (nextScheduledRetryAtMs[camera.id] ?: 0L)
                                        if (retryAllowed) {
                                            synchronized(cameraLock(camera.id)) {
                                                if (!activeSessions.containsKey(camera.id)) {
                                                    startRecordingSessionLocked(camera, ClipTriggerType.SCHEDULED, null)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Single clean pass: end expired scheduled sessions, rotate MANUAL/SCHEDULED chunks
                    // seamlessly, and recover any recorder whose codec stopped unexpectedly.
                    for ((camId, session) in activeSessions.entries.toList()) {
                        if (recoveringClipIds.containsKey(session.clipId)) continue
                        val elapsed = now - session.startTimestamp
                        val isAlive = cameraStreamRecorder.isRecorderAlive(camId, session.clipId)
                        val action = evaluateSessionAction(
                            triggerType = session.triggerType,
                            elapsedMs = elapsed,
                            segmentDurationMs = segmentDurationMs,
                            isScheduleStillActive = continuousTargetCameraIds.contains(camId),
                            isRecorderAlive = isAlive
                        )

                        when (action) {
                            SessionTickAction.STOP_ENDED_SCHEDULE -> {
                                synchronized(cameraLock(camId)) {
                                    val latest = activeSessions[camId]
                                    if (latest != null && latest.clipId == session.clipId &&
                                        activeSessions.remove(camId, latest)
                                    ) {
                                        finishRecordingSession(latest, keepRecordingState = false, emitErrorOnEmpty = true)
                                    }
                                }
                            }
                            SessionTickAction.ROTATE_CHUNK -> {
                                val cam = camerasById[camId] ?: session.cameraSnapshot
                                if (cam != null) {
                                    synchronized(cameraLock(camId)) {
                                        val latest = activeSessions[camId]
                                        if (latest != null && latest.clipId == session.clipId) {
                                            rotateRecordingSessionLocked(latest, cam, latest.triggerType)
                                        }
                                    }
                                }
                            }
                            SessionTickAction.RECOVER_DEAD_RECORDER -> {
                                val cam = camerasById[camId] ?: session.cameraSnapshot
                                if (cam != null && !recoveringClipIds.containsKey(session.clipId)) {
                                    synchronized(cameraLock(camId)) {
                                        val latest = activeSessions[camId]
                                        if (latest != null && latest.clipId == session.clipId &&
                                            !cameraStreamRecorder.isRecorderAlive(camId, latest.clipId)
                                        ) {
                                            rotateRecordingSessionLocked(latest, cam, latest.triggerType)
                                        }
                                    }
                                }
                            }
                            SessionTickAction.KEEP_RECORDING -> Unit
                        }
                    }

                    tickCount++
                    if (tickCount % 6 == 0) {
                        scope.launch {
                            runCatching { storageRetentionEngine.enforceRetention() }
                        }
                    }

                    _activeSchedulesState.value = activeList
                } catch (t: Throwable) {
                    Log.w("RecordingScheduler", "Scheduler tick error: ${t.message}")
                }
                delay(5000) // check every 5 seconds for timely chunk splits & liveness
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
        for ((camId, session) in activeSessions.entries.toList()) {
            synchronized(cameraLock(camId)) {
                if (activeSessions.remove(camId, session)) {
                    finishRecordingSession(session, keepRecordingState = false, emitErrorOnEmpty = false)
                }
            }
        }
        activeSessions.clear()
    }

    enum class SessionTickAction {
        KEEP_RECORDING,
        ROTATE_CHUNK,
        STOP_ENDED_SCHEDULE,
        RECOVER_DEAD_RECORDER
    }

    companion object {
        fun evaluateSessionAction(
            triggerType: ClipTriggerType,
            elapsedMs: Long,
            segmentDurationMs: Long,
            isScheduleStillActive: Boolean,
            isRecorderAlive: Boolean
        ): SessionTickAction {
            return when (triggerType) {
                ClipTriggerType.SCHEDULED -> when {
                    !isScheduleStillActive -> SessionTickAction.STOP_ENDED_SCHEDULE
                    !isRecorderAlive -> SessionTickAction.RECOVER_DEAD_RECORDER
                    elapsedMs >= segmentDurationMs -> SessionTickAction.ROTATE_CHUNK
                    else -> SessionTickAction.KEEP_RECORDING
                }
                ClipTriggerType.MANUAL -> when {
                    !isRecorderAlive -> SessionTickAction.RECOVER_DEAD_RECORDER
                    elapsedMs >= segmentDurationMs -> SessionTickAction.ROTATE_CHUNK
                    else -> SessionTickAction.KEEP_RECORDING
                }
                ClipTriggerType.MOTION_ALERT -> SessionTickAction.KEEP_RECORDING
            }
        }

        fun computeRetryBackoffMs(consecutiveZeroFrameFailures: Int): Long {
            return when {
                consecutiveZeroFrameFailures <= 0 -> 500L
                consecutiveZeroFrameFailures == 1 -> 1_500L
                consecutiveZeroFrameFailures == 2 -> 3_000L
                consecutiveZeroFrameFailures == 3 -> 5_000L
                else -> 10_000L
            }
        }

        fun shouldEmitRecordingError(hadFrames: Boolean, consecutiveFailures: Int): Boolean {
            return !hadFrames && consecutiveFailures >= 2
        }
    }
}
