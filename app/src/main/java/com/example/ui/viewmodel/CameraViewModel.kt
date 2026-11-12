package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.example.engine.CloudStorageManager
import com.example.engine.MotionDetectionEngine
import com.example.engine.RecordingSchedulerEngine
import com.example.engine.RtspStreamManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    val streamManager = RtspStreamManager(application)
    val cloudStorageManager = CloudStorageManager(application, database)
    val recordingSchedulerEngine = RecordingSchedulerEngine(
        application,
        database,
        streamManager,
        cloudStorageManager
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

    val cameras: StateFlow<List<CameraEntity>> = database.cameraDao().getAllCameras()
        .map { list ->
            val localFeed = "android.resource://${getApplication<Application>().packageName}/${com.example.R.raw.surveillance_feed}"
            list.map { cam ->
                if (cam.rtspUrl.contains("commondatastorage.googleapis.com") || cam.rtspUrl.contains("wowzaec2demo")) {
                    cam.copy(rtspUrl = localFeed)
                } else {
                    cam
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    private val _selectedFullscreenCamera = MutableStateFlow<CameraEntity?>(null)
    val selectedFullscreenCamera: StateFlow<CameraEntity?> = _selectedFullscreenCamera.asStateFlow()

    private val _floatingCamera = MutableStateFlow<CameraEntity?>(null)
    val floatingCamera: StateFlow<CameraEntity?> = _floatingCamera.asStateFlow()

    private val _isTvMode = MutableStateFlow(false)
    val isTvMode: StateFlow<Boolean> = _isTvMode.asStateFlow()

    private val _activeScreen = MutableStateFlow("live")
    val activeScreen: StateFlow<String> = _activeScreen.asStateFlow()

    init {
        seedInitialSurveillanceData()
    }

    private fun seedInitialSurveillanceData() {
        viewModelScope.launch {
            val localFeedUri = "android.resource://${getApplication<Application>().packageName}/${com.example.R.raw.surveillance_feed}"
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
                        name = "Front Gate HD",
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
                        name = "Driveway 4K",
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
                        name = "Backyard Yard",
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

    fun selectFullscreenCamera(camera: CameraEntity?) {
        _selectedFullscreenCamera.value = camera
    }

    fun setFloatingCamera(camera: CameraEntity?) {
        _floatingCamera.value = camera
    }

    fun cycleFloatingCamera() {
        val list = cameras.value
        if (list.isEmpty()) return
        val current = _floatingCamera.value
        val currentIndex = list.indexOfFirst { it.id == current?.id }
        val nextIndex = (currentIndex + 1) % list.size
        _floatingCamera.value = list[nextIndex]
    }

    fun toggleTvMode() {
        _isTvMode.value = !_isTvMode.value
    }

    fun navigateTo(screen: String) {
        _activeScreen.value = screen
    }

    fun triggerMotionAlertManually(camera: CameraEntity, level: Int = 85) {
        motionDetectionEngine.triggerMotionAlert(
            camera = camera,
            motionLevel = level,
            customDescription = "Manual alert test triggered by operator (${level}% intensity)"
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
            if (_floatingCamera.value?.id == camera.id) {
                _floatingCamera.value = null
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
