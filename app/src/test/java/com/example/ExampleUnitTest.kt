package com.example

import com.example.data.model.CameraEntity
import com.example.data.model.ClipTriggerType
import com.example.data.model.CloudConfigEntity
import com.example.data.model.CloudProviderType
import com.example.data.model.CloudSyncStatus
import com.example.data.model.MotionSeverity
import com.example.data.model.RecordedClipEntity
import com.example.data.model.RecordingMode
import com.example.data.model.RecordingScheduleEntity
import com.example.data.model.RtspTransport
import com.example.data.model.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun cameraEntity_initialization_hasValidDefaults() {
    val camera = CameraEntity(
      id = "cam_front",
      name = "Front Gate HD",
      rtspUrl = "rtsp://192.168.1.10:554/live",
      transport = RtspTransport.TCP,
      quality = StreamQuality.MAIN_HD,
      motionSensitivity = 75
    )
    assertEquals("Front Gate HD", camera.name)
    assertEquals(RtspTransport.TCP, camera.transport)
    assertEquals(75, camera.motionSensitivity)
    assertTrue(camera.isMotionDetectionEnabled)
  }

  @Test
  fun recordingSchedule_scheduleWindowEvaluation() {
    val schedule = RecordingScheduleEntity(
      id = "sched_night",
      name = "Night Perimeter Sentry",
      daysOfWeek = "MON,TUE,WED,THU,FRI,SAT,SUN",
      startHour = 22,
      startMinute = 0,
      endHour = 6,
      endMinute = 0,
      mode = RecordingMode.MOTION_ONLY,
      isEnabled = true,
      cloudBackupOnFinish = true
    )
    assertTrue(schedule.daysOfWeek.contains("MON"))
    assertTrue(schedule.daysOfWeek.contains("SUN"))
    assertEquals(RecordingMode.MOTION_ONLY, schedule.mode)
    assertTrue(schedule.cloudBackupOnFinish)
  }

  @Test
  fun recordedClipEntity_metadataIntegrity() {
    val clip = RecordedClipEntity(
      id = "clip_123",
      cameraId = "cam_front",
      cameraName = "Front Gate HD",
      timestamp = 1731333600000L,
      durationSeconds = 15,
      fileSizeMb = 6.8f,
      triggerType = ClipTriggerType.MOTION_ALERT,
      motionScore = 88,
      filePath = "/data/recordings/clip_123.mp4",
      cloudSyncStatus = CloudSyncStatus.LOCAL_ONLY
    )
    assertEquals(15, clip.durationSeconds)
    assertEquals(88, clip.motionScore)
    assertEquals(CloudSyncStatus.LOCAL_ONLY, clip.cloudSyncStatus)
    assertEquals(ClipTriggerType.MOTION_ALERT, clip.triggerType)
  }

  @Test
  fun cloudConfig_multiProviderSupport() {
    val gDriveConfig = CloudConfigEntity(
      provider = CloudProviderType.GOOGLE_DRIVE,
      bucketOrFolderName = "OmniView-Clips",
      autoSyncRecordings = true
    )
    assertEquals(CloudProviderType.GOOGLE_DRIVE, gDriveConfig.provider)
    assertTrue(gDriveConfig.autoSyncRecordings)

    val s3Config = gDriveConfig.copy(
      provider = CloudProviderType.AWS_S3_COMPATIBLE,
      endpointUrl = "https://s3.us-west-2.amazonaws.com"
    )
    assertEquals(CloudProviderType.AWS_S3_COMPATIBLE, s3Config.provider)
  }
}
