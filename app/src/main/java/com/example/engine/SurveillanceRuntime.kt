package com.example.engine

import android.app.Application
import android.content.Context
import com.example.data.local.AppDatabase
import com.example.service.SurveillanceKeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Process-wide singleton holder for core surveillance engines.
 * Keeps active recordings, scheduled capture, motion triggers, retention pruning,
 * KeepAlive foreground protection, and RTSP streams alive across Activity stop/recreation
 * or ViewModel clearing until the user explicitly stops recording or chooses "Close App Completely".
 */
class SurveillanceRuntime private constructor(private val app: Application) {

    private val runtimeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _isHubActive = MutableStateFlow(runCatching { RemoteHubPreferences.load(app).enabled }.getOrDefault(false))
    private val _isShuttingDown = MutableStateFlow(false)
    private var keepAliveObserverJob: Job? = null

    val database: AppDatabase = AppDatabase.getDatabase(app)
    val streamManager: RtspStreamManager = RtspStreamManager.getInstance(app)
    val cloudStorageManager: CloudStorageManager = CloudStorageManager(app, database)
    val storageRetentionEngine: StorageRetentionEngine = StorageRetentionEngine(app, database)
    val recordingSchedulerEngine: RecordingSchedulerEngine = RecordingSchedulerEngine(
        app,
        database,
        streamManager,
        cloudStorageManager,
        storageRetentionEngine
    )
    val motionDetectionEngine: MotionDetectionEngine = MotionDetectionEngine(
        app,
        database,
        streamManager
    ).apply {
        onMotionTriggered = { camera, alert ->
            recordingSchedulerEngine.handleMotionTrigger(camera, alert.motionLevel)
        }
    }

    init {
        startKeepAliveSyncObserver()
    }

    private fun startKeepAliveSyncObserver() {
        keepAliveObserverJob?.cancel()
        keepAliveObserverJob = runtimeScope.launch {
            combine(
                database.cloudConfigDao().getCloudConfig(),
                recordingSchedulerEngine.currentlyRecordingCameras,
                _isHubActive,
                _isShuttingDown
            ) { cfg, activeRecs, hubActive, shuttingDown ->
                val persistedCount = recordingSchedulerEngine.getPersistedManualCameraIds().size
                val effectiveRecCount = maxOf(activeRecs.size, persistedCount)
                val shouldKeepAlive = !shuttingDown &&
                    ((cfg?.keepAppAlive != false) || effectiveRecCount > 0 || hubActive)
                shouldKeepAlive to effectiveRecCount
            }.collect { (shouldKeepAlive, effectiveRecCount) ->
                if (shouldKeepAlive) {
                    SurveillanceKeepAliveService.start(app, effectiveRecCount)
                } else {
                    SurveillanceKeepAliveService.stop(app)
                }
            }
        }
    }

    fun setHubActive(active: Boolean) {
        _isHubActive.value = active
    }

    suspend fun resumePersistedRecordingsIfNeeded() {
        if (_isShuttingDown.value) return
        recordingSchedulerEngine.resumePersistedRecordingsIfNeeded()
    }

    fun hasActiveRecordings(): Boolean =
        recordingSchedulerEngine.currentlyRecordingCameras.value.isNotEmpty() ||
            recordingSchedulerEngine.getPersistedManualCameraIds().isNotEmpty()

    fun activeRecordingCount(): Int =
        maxOf(
            recordingSchedulerEngine.currentlyRecordingCameras.value.size,
            recordingSchedulerEngine.getPersistedManualCameraIds().size
        )

    suspend fun shutdownCompletely(timeoutMs: Long = 6_000L) {
        _isShuttingDown.value = true
        keepAliveObserverJob?.cancel()
        synchronized(SurveillanceRuntime) {
            if (instance === this) {
                instance = null
            }
        }
        runCatching {
            recordingSchedulerEngine.stopAllRecordingsAndFlush(timeoutMs = timeoutMs)
        }
        runCatching { SurveillanceKeepAliveService.stop(app) }
        runCatching { motionDetectionEngine.release() }
        runCatching { streamManager.releaseAll() }
    }

    companion object {
        @Volatile
        private var instance: SurveillanceRuntime? = null

        fun getInstance(context: Context): SurveillanceRuntime {
            val app = context.applicationContext as Application
            return instance ?: synchronized(this) {
                instance ?: SurveillanceRuntime(app).also { instance = it }
            }
        }

        fun getIfInitialized(): SurveillanceRuntime? = instance
    }
}
