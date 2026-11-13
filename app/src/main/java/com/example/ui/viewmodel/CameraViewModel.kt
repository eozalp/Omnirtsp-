package com.example.ui.viewmodel

import android.app.Application
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.model.CameraEntity
import com.example.data.model.CloudConfigEntity
import com.example.data.model.CloudProviderType
import com.example.data.model.MotionAlertEntity
import com.example.data.model.RecordedClipEntity
import com.example.data.model.RecordingMode
import com.example.data.model.RecordingScheduleEntity
import com.example.data.model.RtspTransport
import com.example.data.model.StreamQuality
import android.net.Uri
import android.provider.Settings
import com.example.data.model.LocalSaveDestination
import com.example.engine.CameraDiscoveryEngine
import com.example.engine.CloudStorageManager
import com.example.engine.DeviceStorageExporter
import com.example.engine.DiscoveredCamera
import com.example.engine.DiscoveryScanState
import com.example.engine.MotionDetectionEngine
import com.example.engine.OnvifPtzClient
import com.example.engine.PtzCommandState
import com.example.engine.RecordingSchedulerEngine
import com.example.engine.RetentionStatus
import com.example.engine.RtspStreamManager
import com.example.engine.StorageRetentionEngine
import com.example.i18n.AppLocaleManager
import com.example.service.FloatingCameraOverlayService
import com.example.service.FloatingOverlayStateHolder
import com.example.service.SurveillanceKeepAliveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class CornerPosition {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

data class FloatingWindowState(
    val cameraId: String,
    val widthDp: Float = 200f,
    val offsetX: Float? = null,
    val offsetY: Float? = null,
    val cornerSnap: CornerPosition? = CornerPosition.TOP_RIGHT,
    val zIndex: Float = 1f,
    val rotationDegrees: Float = 0f
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    val streamManager = RtspStreamManager(application)
    val onvifPtzClient = OnvifPtzClient(application)
    val cameraDiscoveryEngine = CameraDiscoveryEngine(application, onvifPtzClient)
    val cloudStorageManager = CloudStorageManager(application, database)
    val storageRetentionEngine = StorageRetentionEngine(application, database)
    val recordingSchedulerEngine = RecordingSchedulerEngine(
        application,
        database,
        streamManager,
        cloudStorageManager,
        storageRetentionEngine
    )
    val motionDetectionEngine = MotionDetectionEngine(
        application,
        database,
        streamManager
    ).apply {
        onMotionTriggered = { camera, alert ->
            recordingSchedulerEngine.handleMotionTrigger(camera, alert.motionLevel)
        }
    }

    val cameras: StateFlow<List<CameraEntity>> = combine(
        database.cameraDao().getAllCameras(),
        FloatingOverlayStateHolder.liveRotations
    ) { list, liveRotations ->
        val localFeed = "android.resource://${getApplication<Application>().packageName}/${R.raw.surveillance_feed}"
        val sanitized = list.map { cam ->
            val withFeed = if (cam.rtspUrl.contains("commondatastorage.googleapis.com") || cam.rtspUrl.contains("wowzaec2demo")) {
                cam.copy(rtspUrl = localFeed)
            } else {
                cam
            }
            val liveRot = liveRotations[withFeed.id]
            if (liveRot != null && liveRot != withFeed.rotationDegrees) {
                withFeed.copy(rotationDegrees = liveRot)
            } else {
                withFeed
            }
        }
        FloatingOverlayStateHolder.updateCameras(sanitized)
        cameraDiscoveryEngine.markExistingCameras(sanitized)
        sanitized
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val alerts: StateFlow<List<MotionAlertEntity>> = database.motionAlertDao().getAllAlerts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadAlertCount: StateFlow<Int> = database.motionAlertDao().getUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val schedules: StateFlow<List<RecordingScheduleEntity>> = database.scheduleDao().getAllSchedules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val clips: StateFlow<List<RecordedClipEntity>> = database.recordedClipDao().getAllClips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cloudConfig: StateFlow<CloudConfigEntity> = database.cloudConfigDao().getCloudConfig()
        .map { it ?: CloudConfigEntity() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            CloudConfigEntity()
        )

    private val _gridColumns = MutableStateFlow(2)
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    private val _feedSizeScales = MutableStateFlow<Map<String, Float>>(emptyMap())
    val feedSizeScales: StateFlow<Map<String, Float>> = _feedSizeScales.asStateFlow()

    private val _selectedFullscreenCamera = MutableStateFlow<CameraEntity?>(null)
    val selectedFullscreenCamera: StateFlow<CameraEntity?> = _selectedFullscreenCamera.asStateFlow()

    private val _openFullscreenWithPtz = MutableStateFlow(false)
    val openFullscreenWithPtz: StateFlow<Boolean> = _openFullscreenWithPtz.asStateFlow()

    val ptzStatus: StateFlow<PtzCommandState> = onvifPtzClient.status
    val discoveryState: StateFlow<DiscoveryScanState> = cameraDiscoveryEngine.state
    val retentionStatus: StateFlow<RetentionStatus> = storageRetentionEngine.status
    val selectedLanguageTag: StateFlow<String> = AppLocaleManager.selectedLanguageTag

    // Multi-camera floating windows & global audio state (synced across live grid, fullscreen, & overlays)
    val floatingWindows: StateFlow<List<FloatingWindowState>> = FloatingOverlayStateHolder.floatingWindows
    val isSystemOverlayActive: StateFlow<Boolean> = FloatingOverlayStateHolder.isSystemOverlayActive
    val isGlobalMuted: StateFlow<Boolean> = FloatingOverlayStateHolder.isGlobalMuted

    // Hidden by default: show only clean video & camera name unless user requests technical details
    val showTechnicalDetails: StateFlow<Boolean> = FloatingOverlayStateHolder.showTechnicalDetails

    private val isHardwareTv = (application.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION

    private val _isTvMode = MutableStateFlow(isHardwareTv)
    val isTvMode: StateFlow<Boolean> = _isTvMode.asStateFlow()

    private val _activeScreen = MutableStateFlow("live")
    val activeScreen: StateFlow<String> = _activeScreen.asStateFlow()

    private val _localExportStatus = MutableStateFlow<String?>(null)
    val localExportStatus: StateFlow<String?> = _localExportStatus.asStateFlow()

    private val rotationPersistJobs = mutableMapOf<String, Job>()

    init {
        AppLocaleManager.init(application)
        FloatingOverlayStateHolder.initAudioSettings(application)
        seedInitialSurveillanceData()
        observeFloatingRotationPersistence()
        observeKeepAliveService()
        scanForNetworkCameras()
    }

    private fun observeKeepAliveService() {
        viewModelScope.launch {
            combine(
                database.cloudConfigDao().getCloudConfig(),
                recordingSchedulerEngine.currentlyRecordingCameras
            ) { cfg, activeRecs ->
                (cfg?.keepAppAlive != false) || activeRecs.isNotEmpty()
            }.collect { shouldKeepAlive ->
                val app = getApplication<Application>()
                if (shouldKeepAlive) {
                    SurveillanceKeepAliveService.start(app)
                } else {
                    SurveillanceKeepAliveService.stop(app)
                }
            }
        }
    }

    private fun observeFloatingRotationPersistence() {
        viewModelScope.launch {
            FloatingOverlayStateHolder.rotationEvents.collect { (cameraId, degrees) ->
                rotationPersistJobs[cameraId]?.cancel()
                rotationPersistJobs[cameraId] = launch(Dispatchers.IO) {
                    delay(120)
                    database.cameraDao().updateRotation(cameraId, degrees)
                }
            }
        }
    }

    private fun seedInitialSurveillanceData() {
        viewModelScope.launch {
            val localFeedUri = "android.resource://${getApplication<Application>().packageName}/${R.raw.surveillance_feed}"
            val existing = database.cameraDao().getEnabledCameras()
            for (cam in existing) {
                if (cam.rtspUrl.contains("commondatastorage.googleapis.com") ||
                    cam.rtspUrl.contains("wowzaec2demo.streamlock.net")
                ) {
                    database.cameraDao().insertCamera(
                        cam.copy(rtspUrl = localFeedUri)
                    )
                }
            }
            if (existing.isEmpty()) {
                val seedCameras = listOf(
                    CameraEntity(
                        id = "cam_front_gate",
                        name = "Front Gate",
                        rtspUrl = localFeedUri,
                        transport = RtspTransport.TCP,
                        quality = StreamQuality.MAIN_HD,
                        isMotionDetectionEnabled = true,
                        motionSensitivity = 75,
                        location = "Exterior Perimeter",
                        isDemoCamera = false
                    ),
                    CameraEntity(
                        id = "cam_driveway",
                        name = "Driveway",
                        rtspUrl = localFeedUri,
                        transport = RtspTransport.TCP,
                        quality = StreamQuality.MAIN_HD,
                        isMotionDetectionEnabled = true,
                        motionSensitivity = 60,
                        location = "Driveway North",
                        isDemoCamera = false
                    ),
                    CameraEntity(
                        id = "cam_backyard",
                        name = "Backyard",
                        rtspUrl = localFeedUri,
                        transport = RtspTransport.UDP,
                        quality = StreamQuality.SUB_SD,
                        isMotionDetectionEnabled = true,
                        motionSensitivity = 80,
                        location = "Garden South",
                        isDemoCamera = false
                    ),
                    CameraEntity(
                        id = "cam_living_room",
                        name = "Main Entrance",
                        rtspUrl = localFeedUri,
                        transport = RtspTransport.TCP,
                        quality = StreamQuality.MAIN_HD,
                        isMotionDetectionEnabled = true,
                        motionSensitivity = 70,
                        location = "Foyer Indoor",
                        isDemoCamera = false
                    )
                )
                database.cameraDao().insertCameras(seedCameras)

                val defaultSchedules = listOf(
                    RecordingScheduleEntity(
                        id = "sched_night_guard",
                        name = "Night Perimeter Sentry",
                        daysOfWeek = "MON,TUE,WED,THU,FRI,SAT,SUN",
                        startHour = 22,
                        startMinute = 0,
                        endHour = 6,
                        endMinute = 0,
                        mode = RecordingMode.MOTION_ONLY,
                        isEnabled = true,
                        targetCameraIds = "ALL",
                        cloudBackupOnFinish = true
                    ),
                    RecordingScheduleEntity(
                        id = "sched_business_hours",
                        name = "Daytime Continuous Surveillance",
                        daysOfWeek = "MON,TUE,WED,THU,FRI",
                        startHour = 8,
                        startMinute = 30,
                        endHour = 18,
                        endMinute = 0,
                        mode = RecordingMode.CONTINUOUS,
                        isEnabled = true,
                        targetCameraIds = "cam_front_gate,cam_driveway",
                        cloudBackupOnFinish = true
                    )
                )
                database.scheduleDao().insertSchedules(defaultSchedules)

                database.cloudConfigDao().insertOrUpdateConfig(
                    CloudConfigEntity(
                        provider = CloudProviderType.GOOGLE_DRIVE,
                        endpointUrl = "https://cloud.surveillance.storage/v1/sync",
                        bucketOrFolderName = "OmniView-Surveillance-Drive",
                        autoSyncRecordings = true,
                        autoSyncMotionSnapshots = true,
                        wifiOnly = false
                    )
                )
            }
        }
    }

    fun setGridColumns(cols: Int) {
        _gridColumns.value = cols
    }

    fun updateFeedSizeScale(cameraId: String, scale: Float) {
        val clamped = scale.coerceIn(0.7f, 1.8f)
        _feedSizeScales.value = _feedSizeScales.value.toMutableMap().apply {
            this[cameraId] = clamped
        }
    }

    fun resetFeedSizeScale(cameraId: String) {
        _feedSizeScales.value = _feedSizeScales.value.toMutableMap().apply {
            remove(cameraId)
        }
    }

    fun selectFullscreenCamera(camera: CameraEntity?, openPtz: Boolean = false) {
        _openFullscreenWithPtz.value = openPtz && camera != null
        _selectedFullscreenCamera.value = camera
    }

    fun probeCameraPtz(camera: CameraEntity) {
        viewModelScope.launch {
            onvifPtzClient.probeCamera(camera)
        }
    }

    fun startCameraPtzMove(
        camera: CameraEntity,
        panVelocity: Float,
        tiltVelocity: Float,
        zoomVelocity: Float,
        actionLabel: String
    ) {
        viewModelScope.launch {
            onvifPtzClient.continuousMove(
                camera = camera,
                panVelocity = panVelocity,
                tiltVelocity = tiltVelocity,
                zoomVelocity = zoomVelocity,
                actionLabel = actionLabel
            )
        }
    }

    fun stopCameraPtz(camera: CameraEntity) {
        viewModelScope.launch {
            onvifPtzClient.stop(camera)
        }
    }

    fun gotoCameraPtzHome(camera: CameraEntity) {
        viewModelScope.launch {
            onvifPtzClient.gotoHome(camera)
        }
    }

    fun gotoCameraPtzPreset(camera: CameraEntity, presetToken: String) {
        viewModelScope.launch {
            onvifPtzClient.gotoPreset(camera, presetToken)
        }
    }

    fun updateCameraOnvifPort(camera: CameraEntity, onvifPort: Int) {
        viewModelScope.launch {
            onvifPtzClient.clearCache(camera.id)
            val updated = camera.copy(onvifPort = onvifPort.coerceIn(1, 65535))
            database.cameraDao().insertCamera(updated)
            onvifPtzClient.probeCamera(updated)
        }
    }

    fun scanForNetworkCameras() {
        viewModelScope.launch {
            val current = database.cameraDao().getEnabledCameras()
            cameraDiscoveryEngine.scanLocalNetwork(current)
        }
    }

    suspend fun fetchOnvifRtspUrl(
        rtspOrIp: String,
        onvifPort: Int,
        username: String,
        password: String
    ): Result<String> {
        val host = if (rtspOrIp.contains("://")) {
            OnvifPtzClient.extractHost(rtspOrIp)
        } else {
            rtspOrIp.trim().takeIf { it.isNotBlank() }
        } ?: return Result.failure(IllegalArgumentException("Enter camera IP or RTSP URL first"))
        return onvifPtzClient.fetchRtspStreamUri(
            host = host,
            onvifPort = onvifPort,
            username = username,
            password = password
        )
    }

    fun connectDiscoveredCamera(
        discovered: DiscoveredCamera,
        customName: String? = null,
        username: String = "",
        password: String = "",
        rtspUrlOverride: String? = null
    ) {
        viewModelScope.launch {
            val entity = CameraEntity(
                id = "cam_${discovered.ipAddress.replace(".", "_")}_${System.currentTimeMillis() % 1000}",
                name = customName?.takeIf { it.isNotBlank() } ?: discovered.name,
                rtspUrl = rtspUrlOverride?.takeIf { it.isNotBlank() } ?: discovered.suggestedRtspUrl,
                username = username.trim(),
                password = password.trim(),
                transport = RtspTransport.TCP,
                quality = StreamQuality.MAIN_HD,
                onvifPort = discovered.onvifPort,
                location = AppLocaleManager.getString(getApplication(), R.string.cam_network_location, discovered.ipAddress),
                isDemoCamera = false
            )
            database.cameraDao().insertCamera(entity)
        }
    }

    fun toggleTechnicalDetails() {
        FloatingOverlayStateHolder.setTechnicalDetails(!showTechnicalDetails.value)
    }

    fun toggleFloatingCamera(camera: CameraEntity, defaultWidthDp: Float = 190f) {
        val targetWidth = if (_isTvMode.value) 260f else defaultWidthDp
        FloatingOverlayStateHolder.toggleFloatingCamera(camera.id, targetWidth)
        syncSystemOverlayIfPermitted()
    }

    fun openFloatingCamera(camera: CameraEntity, defaultWidthDp: Float = 190f) {
        val targetWidth = if (_isTvMode.value) 260f else defaultWidthDp
        FloatingOverlayStateHolder.openFloatingCamera(camera.id, targetWidth)
        syncSystemOverlayIfPermitted()
    }

    fun floatAllCameras(defaultWidthDp: Float = 180f) {
        val allIds = cameras.value.map { it.id }
        if (allIds.isEmpty()) return
        val targetWidth = if (_isTvMode.value) 260f else defaultWidthDp
        FloatingOverlayStateHolder.floatAllCameras(allIds, targetWidth)
        syncSystemOverlayIfPermitted()
    }

    fun closeFloatingCamera(cameraId: String) {
        FloatingOverlayStateHolder.closeFloatingCamera(cameraId)
        if (FloatingOverlayStateHolder.floatingWindows.value.isEmpty()) {
            FloatingCameraOverlayService.stop(getApplication())
        }
    }

    fun closeAllFloatingWindows() {
        FloatingOverlayStateHolder.closeAll()
        FloatingCameraOverlayService.stop(getApplication())
    }

    fun bringFloatingToFront(cameraId: String) {
        FloatingOverlayStateHolder.bringToFront(cameraId)
    }

    fun updateFloatingPosition(cameraId: String, offsetX: Float, offsetY: Float) {
        FloatingOverlayStateHolder.updatePosition(cameraId, offsetX, offsetY)
    }

    fun snapFloatingToCorner(cameraId: String, corner: CornerPosition) {
        FloatingOverlayStateHolder.snapToCorner(cameraId, corner)
    }

    fun resizeFloatingWindow(
        cameraId: String,
        widthDp: Float,
        offsetX: Float? = null,
        offsetY: Float? = null
    ) {
        FloatingOverlayStateHolder.resizeWindow(cameraId, widthDp, offsetX, offsetY)
    }

    fun setCameraRotation(cameraId: String, degrees: Float) {
        FloatingOverlayStateHolder.setRotation(cameraId, degrees)
    }

    fun startSystemOverlayOverApps(): Boolean {
        val app = getApplication<Application>()
        if (!Settings.canDrawOverlays(app)) return false
        if (FloatingOverlayStateHolder.floatingWindows.value.isEmpty()) {
            val allIds = cameras.value.map { it.id }
            if (allIds.isNotEmpty()) {
                val targetWidth = if (_isTvMode.value) 260f else 180f
                FloatingOverlayStateHolder.floatAllCameras(allIds, targetWidth)
            }
        }
        if (FloatingOverlayStateHolder.floatingWindows.value.isNotEmpty()) {
            FloatingCameraOverlayService.startOrSync(app)
            return true
        }
        return false
    }

    fun stopSystemOverlayOverApps() {
        FloatingCameraOverlayService.stop(getApplication())
    }

    private fun syncSystemOverlayIfPermitted() {
        val app = getApplication<Application>()
        if (Settings.canDrawOverlays(app) && FloatingOverlayStateHolder.floatingWindows.value.isNotEmpty()) {
            FloatingCameraOverlayService.startOrSync(app)
        }
    }

    fun toggleTvMode() {
        _isTvMode.value = !_isTvMode.value
    }

    fun setAppLanguage(tag: String) {
        val app = getApplication<Application>()
        AppLocaleManager.setLanguage(app, tag)
        val displayLabel = when (AppLocaleManager.normalizeTag(tag)) {
            AppLocaleManager.LANG_TURKISH -> AppLocaleManager.getString(app, R.string.language_turkish)
            AppLocaleManager.LANG_ENGLISH -> AppLocaleManager.getString(app, R.string.language_english)
            else -> AppLocaleManager.getString(app, R.string.language_system)
        }
        _localExportStatus.value = AppLocaleManager.getString(app, R.string.language_changed_status, displayLabel)
        if (FloatingOverlayStateHolder.isSystemOverlayActive.value) {
            FloatingCameraOverlayService.startOrSync(app)
        }
    }

    fun navigateTo(screen: String) {
        _activeScreen.value = screen
    }

    fun triggerMotionAlertManually(camera: CameraEntity, level: Int = 85) {
        val app = getApplication<Application>()
        motionDetectionEngine.triggerMotionAlert(
            camera = camera,
            motionLevel = level,
            customDescription = AppLocaleManager.getString(app, R.string.alerts_manual_desc, level)
        )
    }

    fun startManualRecording(camera: CameraEntity) {
        recordingSchedulerEngine.startManualRecording(camera)
    }

    fun stopRecording(cameraId: String) {
        recordingSchedulerEngine.stopRecording(cameraId)
    }

    fun addOrUpdateCamera(camera: CameraEntity) {
        viewModelScope.launch {
            database.cameraDao().insertCamera(camera)
        }
    }

    fun deleteCamera(camera: CameraEntity) {
        viewModelScope.launch {
            FloatingOverlayStateHolder.closeFloatingCamera(camera.id)
            if (FloatingOverlayStateHolder.floatingWindows.value.isEmpty()) {
                FloatingCameraOverlayService.stop(getApplication())
            }
            if (_selectedFullscreenCamera.value?.id == camera.id) {
                _selectedFullscreenCamera.value = null
            }
            streamManager.releasePlayer(camera.id)
            database.cameraDao().deleteCamera(camera)
        }
    }

    fun markAlertRead(alertId: String) {
        viewModelScope.launch {
            database.motionAlertDao().markAsRead(alertId)
        }
    }

    fun markAllAlertsRead() {
        viewModelScope.launch {
            database.motionAlertDao().markAllAsRead()
        }
    }

    fun clearAllAlerts() {
        viewModelScope.launch {
            database.motionAlertDao().clearAllAlerts()
        }
    }

    fun saveSchedule(schedule: RecordingScheduleEntity) {
        viewModelScope.launch {
            database.scheduleDao().insertSchedule(schedule)
        }
    }

    fun toggleSchedule(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            database.scheduleDao().setScheduleEnabled(id, isEnabled)
        }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch {
            database.scheduleDao().deleteScheduleById(id)
        }
    }

    fun saveCloudConfig(config: CloudConfigEntity) {
        viewModelScope.launch {
            database.cloudConfigDao().insertOrUpdateConfig(config)
        }
    }

    fun updateRetentionPolicy(enabled: Boolean, days: Int, hours: Int, minutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val clampedDays = days.coerceIn(0, 3650)
            val clampedHours = hours.coerceIn(0, 23)
            val clampedMinutes = minutes.coerceIn(0, 59)
            val current = database.cloudConfigDao().getCloudConfigSync() ?: CloudConfigEntity()
            val updated = current.copy(
                retentionEnabled = enabled,
                retentionDays = clampedDays,
                retentionHours = clampedHours,
                retentionMinutes = clampedMinutes
            )
            database.cloudConfigDao().insertOrUpdateConfig(updated)
            val pruned = storageRetentionEngine.enforceRetention()
            val windowText = formatLocalizedRetention(updated)
            _localExportStatus.value = if (!enabled || updated.totalRetentionMillis() == 0L) {
                AppLocaleManager.getString(app, R.string.status_retention_off)
            } else if (pruned > 0) {
                AppLocaleManager.getString(app, R.string.status_retention_updated_pruned, windowText, pruned)
            } else {
                AppLocaleManager.getString(app, R.string.status_retention_updated, windowText)
            }
        }
    }

    fun pruneExpiredStorageNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val pruned = storageRetentionEngine.enforceRetention()
            val config = database.cloudConfigDao().getCloudConfigSync() ?: CloudConfigEntity()
            val label = formatLocalizedRetention(config)
            _localExportStatus.value = if (pruned > 0) {
                AppLocaleManager.getString(app, R.string.status_pruned_count, pruned, label)
            } else {
                AppLocaleManager.getString(app, R.string.status_all_within_limit, label)
            }
        }
    }

    fun toggleGlobalMute() {
        val app = getApplication<Application>()
        val nowMuted = FloatingOverlayStateHolder.toggleGlobalMuted(app)
        _localExportStatus.value = AppLocaleManager.getString(
            app,
            if (nowMuted) R.string.sound_muted_status else R.string.sound_unmuted_status
        )
    }

    fun setGlobalMuted(muted: Boolean) {
        val app = getApplication<Application>()
        FloatingOverlayStateHolder.setGlobalMuted(app, muted)
    }

    fun updateChunkDuration(hours: Int, minutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val totalMins = (hours.coerceIn(0, 24) * 60 + minutes.coerceIn(0, 59)).coerceIn(1, 24 * 60)
            val normalizedHours = totalMins / 60
            val normalizedMinutes = totalMins % 60
            val current = database.cloudConfigDao().getCloudConfigSync() ?: CloudConfigEntity()
            val updated = current.copy(
                chunkDurationHours = normalizedHours,
                chunkDurationMinutes = normalizedMinutes
            )
            database.cloudConfigDao().insertOrUpdateConfig(updated)
            val label = formatLocalizedChunkWindow(updated)
            _localExportStatus.value = AppLocaleManager.getString(app, R.string.status_chunk_updated, label)
        }
    }

    fun setKeepAppAlive(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val current = database.cloudConfigDao().getCloudConfigSync() ?: CloudConfigEntity()
            database.cloudConfigDao().insertOrUpdateConfig(current.copy(keepAppAlive = enabled))
            if (enabled) {
                SurveillanceKeepAliveService.start(app)
                _localExportStatus.value = AppLocaleManager.getString(app, R.string.status_keepalive_enabled)
            } else {
                SurveillanceKeepAliveService.stop(app)
                _localExportStatus.value = AppLocaleManager.getString(app, R.string.status_keepalive_disabled)
            }
        }
    }

    fun isBatteryOptimizationIgnored(): Boolean {
        return SurveillanceKeepAliveService.isBatteryOptimizationIgnored(getApplication())
    }

    fun openBatteryOptimizationSettings() {
        val app = getApplication<Application>()
        try {
            app.startActivity(SurveillanceKeepAliveService.createBatteryOptimizationSettingsIntent(app))
        } catch (_: Exception) {}
    }

    private fun formatLocalizedChunkWindow(config: CloudConfigEntity): String {
        val app = getApplication<Application>()
        val totalMins = (config.totalChunkMillis() / 60_000L).toInt()
        val h = totalMins / 60
        val m = totalMins % 60
        val hourU = AppLocaleManager.getString(app, R.string.retention_unit_hour_short)
        val minU = AppLocaleManager.getString(app, R.string.retention_unit_min_short)
        return buildList {
            if (h > 0) add("$h$hourU")
            if (m > 0 || h == 0) add("$m$minU")
        }.joinToString(" ")
    }

    private fun formatLocalizedRetention(config: CloudConfigEntity): String {
        val app = getApplication<Application>()
        if (!config.retentionEnabled) return AppLocaleManager.getString(app, R.string.retention_unlimited_label)
        val d = config.retentionDays.coerceAtLeast(0)
        val h = config.retentionHours.coerceAtLeast(0)
        val m = config.retentionMinutes.coerceAtLeast(0)
        if (d == 0 && h == 0 && m == 0) return AppLocaleManager.getString(app, R.string.retention_unlimited_label)
        val dayU = AppLocaleManager.getString(app, R.string.retention_unit_day_short)
        val hourU = AppLocaleManager.getString(app, R.string.retention_unit_hour_short)
        val minU = AppLocaleManager.getString(app, R.string.retention_unit_min_short)
        return buildList {
            if (d > 0) add("$d$dayU")
            if (h > 0) add("$h$hourU")
            if (m > 0) add("$m$minU")
        }.joinToString(" ")
    }

    fun selectSafFolder(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val selection = DeviceStorageExporter.persistTreePermission(app, treeUri)
            val current = database.cloudConfigDao().getCloudConfigSync() ?: CloudConfigEntity()
            database.cloudConfigDao().insertOrUpdateConfig(
                current.copy(
                    localSaveDestination = LocalSaveDestination.CUSTOM_SAF_FOLDER,
                    safFolderUri = selection.uriString,
                    safFolderDisplayName = selection.displayName,
                    autoSaveToDeviceFolder = true
                )
            )
            _localExportStatus.value = if (selection.isRemoteProvider) {
                AppLocaleManager.getString(app, R.string.status_remote_folder_selected, selection.displayName)
            } else {
                AppLocaleManager.getString(app, R.string.status_local_folder_selected, selection.displayName)
            }
        }
    }

    fun clearSafFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val current = database.cloudConfigDao().getCloudConfigSync() ?: CloudConfigEntity()
            database.cloudConfigDao().insertOrUpdateConfig(
                current.copy(
                    localSaveDestination = LocalSaveDestination.PUBLIC_MOVIES,
                    safFolderUri = "",
                    safFolderDisplayName = ""
                )
            )
            _localExportStatus.value = AppLocaleManager.getString(app, R.string.status_reset_save_location)
        }
    }

    fun setLocalSaveDestination(destination: LocalSaveDestination) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = database.cloudConfigDao().getCloudConfigSync() ?: CloudConfigEntity()
            database.cloudConfigDao().insertOrUpdateConfig(
                current.copy(localSaveDestination = destination)
            )
        }
    }

    fun setAutoSaveToDeviceFolder(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = database.cloudConfigDao().getCloudConfigSync() ?: CloudConfigEntity()
            database.cloudConfigDao().insertOrUpdateConfig(
                current.copy(autoSaveToDeviceFolder = enabled)
            )
        }
    }

    fun exportClipToConfiguredFolder(clip: RecordedClipEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val sourceFile = File(clip.filePath)
            val config = database.cloudConfigDao().getCloudConfigSync() ?: CloudConfigEntity()

            _localExportStatus.value = AppLocaleManager.getString(app, R.string.status_saving_clip, clip.cameraName)
            val result = if (config.localSaveDestination == LocalSaveDestination.CUSTOM_SAF_FOLDER &&
                config.safFolderUri.isNotBlank()
            ) {
                DeviceStorageExporter.exportClipToSafTree(app, sourceFile, config.safFolderUri)
            } else {
                DeviceStorageExporter.exportClipToPublicMovies(app, sourceFile)
            }

            result.onSuccess { uri ->
                database.recordedClipDao().updateLocalExportUri(clip.id, uri.toString())
                val destName = if (config.localSaveDestination == LocalSaveDestination.CUSTOM_SAF_FOLDER &&
                    config.safFolderDisplayName.isNotBlank()
                ) config.safFolderDisplayName else "Movies/OmniView"
                _localExportStatus.value = AppLocaleManager.getString(app, R.string.status_saved_to_folder, destName)
            }.onFailure { err ->
                val detail = err.localizedMessage ?: AppLocaleManager.getString(app, R.string.status_check_folder_access)
                _localExportStatus.value = AppLocaleManager.getString(app, R.string.status_save_failed, detail)
            }
        }
    }

    fun exportClipToCustomUri(clip: RecordedClipEntity, targetUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val sourceFile = File(clip.filePath)
            _localExportStatus.value = AppLocaleManager.getString(app, R.string.status_saving_clip, clip.cameraName)
            DeviceStorageExporter.saveClipToUri(app, sourceFile, targetUri)
                .onSuccess { uri ->
                    database.recordedClipDao().updateLocalExportUri(clip.id, uri.toString())
                    _localExportStatus.value = AppLocaleManager.getString(app, R.string.status_clip_saved_custom)
                }
                .onFailure { err ->
                    val detail = err.localizedMessage ?: AppLocaleManager.getString(app, R.string.status_unable_write_file)
                    _localExportStatus.value = AppLocaleManager.getString(app, R.string.status_save_failed, detail)
                }
        }
    }

    fun clearLocalExportStatus() {
        _localExportStatus.value = null
    }

    fun testCloudConnection(config: CloudConfigEntity) {
        cloudStorageManager.testCloudConnection(config)
    }

    fun uploadClipToCloud(clip: RecordedClipEntity) {
        cloudStorageManager.uploadClipToCloud(clip, forced = true)
    }

    fun deleteClip(id: String) {
        viewModelScope.launch {
            database.recordedClipDao().deleteClipById(id)
        }
    }

    override fun onCleared() {
        super.onCleared()
        streamManager.releaseAll()
        motionDetectionEngine.release()
        recordingSchedulerEngine.release()
    }
}
