package com.example

import com.example.data.model.CameraEntity
import com.example.data.model.ClipTriggerType
import com.example.data.model.CloudConfigEntity
import com.example.data.model.CloudProviderType
import com.example.data.model.CloudSyncStatus
import com.example.data.model.LocalSaveDestination
import com.example.data.model.MotionSeverity
import com.example.data.model.RecordedClipEntity
import com.example.data.model.RecordingMode
import com.example.data.model.RecordingScheduleEntity
import com.example.data.model.RtspTransport
import com.example.data.model.StreamQuality
import androidx.media3.common.Player
import com.example.engine.CameraDiscoveryEngine
import com.example.engine.OnvifPtzClient
import com.example.engine.RtspStreamManager
import com.example.engine.StorageRetentionEngine
import com.example.i18n.AppLocaleManager
import com.example.service.FloatingOverlayStateHolder
import com.example.ui.components.ResizeCorner
import com.example.ui.components.baseWidthFromActualWidthDp
import com.example.ui.components.computeCoverFrameSizeForRotation
import com.example.ui.components.computeEffectiveContainerAspectRatio
import com.example.ui.components.formatRotationLabel
import com.example.ui.components.normalizeRotationDegrees
import com.example.ui.components.parseResolutionAspectRatio
import com.example.ui.components.resolveFloatingWindowSizeDp
import com.example.ui.viewmodel.CornerPosition
import com.example.ui.viewmodel.FloatingWindowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
      username = "admin",
      password = "secretPassword123!",
      transport = RtspTransport.TCP,
      quality = StreamQuality.MAIN_HD,
      motionSensitivity = 75
    )
    assertEquals("Front Gate HD", camera.name)
    assertEquals(RtspTransport.TCP, camera.transport)
    assertEquals("admin", camera.username)
    assertEquals("secretPassword123!", camera.password)
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
      endpointUrl = "https://s3.us-west-2.amazonaws.com",
      s3AccessKeyId = "AKIAIOSFODNN7EXAMPLE",
      s3SecretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
      s3Bucket = "surveillance-vault-prod",
      s3Region = "us-west-2"
    )
    assertEquals(CloudProviderType.AWS_S3_COMPATIBLE, s3Config.provider)
    assertEquals("AKIAIOSFODNN7EXAMPLE", s3Config.s3AccessKeyId)
    assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", s3Config.s3SecretAccessKey)
    assertEquals("surveillance-vault-prod", s3Config.s3Bucket)
    assertEquals("us-west-2", s3Config.s3Region)

    val webdavConfig = gDriveConfig.copy(
      provider = CloudProviderType.WEBDAV,
      endpointUrl = "https://nextcloud.company.com/remote.php/dav/files/user/cctv",
      webdavUsername = "sec_admin",
      webdavPassword = "DavSecretPassword123!"
    )
    assertEquals(CloudProviderType.WEBDAV, webdavConfig.provider)
    assertEquals("sec_admin", webdavConfig.webdavUsername)
    assertEquals("DavSecretPassword123!", webdavConfig.webdavPassword)
  }

  @Test
  fun floatingWindowState_supportsCornersAndFreePositioning() {
    val defaultWin = FloatingWindowState(cameraId = "cam_1")
    assertEquals(200f, defaultWin.widthDp, 0.01f)
    assertEquals(CornerPosition.TOP_RIGHT, defaultWin.cornerSnap)
    assertNull(defaultWin.offsetX)
    assertNull(defaultWin.offsetY)

    val freeMoved = defaultWin.copy(
      offsetX = 120f,
      offsetY = 340f,
      cornerSnap = null,
      widthDp = 320f.coerceIn(130f, 540f)
    )
    assertNull(freeMoved.cornerSnap)
    assertEquals(120f, freeMoved.offsetX!!, 0.01f)
    assertEquals(340f, freeMoved.offsetY!!, 0.01f)
    assertEquals(320f, freeMoved.widthDp, 0.01f)
    assertEquals(4, CornerPosition.entries.size)
    assertEquals(4, ResizeCorner.entries.size)
  }

  @Test
  fun floatingOverlayStateHolder_managesMultipleWindowsAndResize() {
    FloatingOverlayStateHolder.closeAll()
    FloatingOverlayStateHolder.floatAllCameras(listOf("cam_a", "cam_b", "cam_c", "cam_d"), defaultWidthDp = 180f)
    val windows = FloatingOverlayStateHolder.floatingWindows.value
    assertEquals(4, windows.size)
    assertEquals(CornerPosition.TOP_LEFT, windows[0].cornerSnap)
    assertEquals(CornerPosition.TOP_RIGHT, windows[1].cornerSnap)
    assertEquals(CornerPosition.BOTTOM_LEFT, windows[2].cornerSnap)
    assertEquals(CornerPosition.BOTTOM_RIGHT, windows[3].cornerSnap)

    FloatingOverlayStateHolder.resizeWindow("cam_b", widthDp = 999f, offsetX = 50f, offsetY = 80f)
    val resizedB = FloatingOverlayStateHolder.floatingWindows.value.first { it.cameraId == "cam_b" }
    assertEquals(540f, resizedB.widthDp, 0.01f)
    assertEquals(50f, resizedB.offsetX!!, 0.01f)
    assertEquals(80f, resizedB.offsetY!!, 0.01f)
    assertNull(resizedB.cornerSnap)

    FloatingOverlayStateHolder.closeFloatingCamera("cam_a")
    assertEquals(3, FloatingOverlayStateHolder.floatingWindows.value.size)

    FloatingOverlayStateHolder.closeAll()
    assertTrue(FloatingOverlayStateHolder.floatingWindows.value.isEmpty())
  }

  @Test
  fun localAndSafStorageOptions_configAndClipTracking() {
    val defaultConfig = CloudConfigEntity()
    assertEquals(LocalSaveDestination.PUBLIC_MOVIES, defaultConfig.localSaveDestination)
    assertEquals("", defaultConfig.safFolderUri)
    assertEquals("", defaultConfig.safFolderDisplayName)
    assertTrue(defaultConfig.autoSaveToDeviceFolder)
    assertEquals(3, LocalSaveDestination.entries.size)

    val customFolderConfig = defaultConfig.copy(
      localSaveDestination = LocalSaveDestination.CUSTOM_SAF_FOLDER,
      safFolderUri = "content://com.android.externalstorage.documents/tree/primary%3ACamClips",
      safFolderDisplayName = "Device / CamClips",
      autoSaveToDeviceFolder = true
    )
    assertEquals(LocalSaveDestination.CUSTOM_SAF_FOLDER, customFolderConfig.localSaveDestination)
    assertEquals("Device / CamClips", customFolderConfig.safFolderDisplayName)

    val clipWithExport = RecordedClipEntity(
      id = "clip_local_1",
      cameraId = "cam_1",
      cameraName = "Front Gate",
      timestamp = 1731333600000L,
      durationSeconds = 10,
      fileSizeMb = 4.2f,
      triggerType = ClipTriggerType.MANUAL,
      filePath = "/data/user/0/com.example/files/recordings/clip_local_1.mp4",
      localExportUri = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3D42"
    )
    assertEquals(
      "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3D42",
      clipWithExport.localExportUri
    )
  }

  @Test
  fun videoRotation_normalizesDegreesAndSyncsFloatingWindows() {
    assertEquals(0f, normalizeRotationDegrees(0f), 0.01f)
    assertEquals(0f, normalizeRotationDegrees(360f), 0.01f)
    assertEquals(270f, normalizeRotationDegrees(-90f), 0.01f)
    assertEquals(42.5f, normalizeRotationDegrees(402.5f), 0.01f)
    assertEquals("90°", formatRotationLabel(90f))
    assertEquals("42.5°", formatRotationLabel(42.5f))

    val cam = CameraEntity(
      id = "cam_rot",
      name = "Garden Cam",
      rtspUrl = "rtsp://192.168.1.55:554/stream",
      rotationDegrees = 90f
    )
    FloatingOverlayStateHolder.closeAll()
    FloatingOverlayStateHolder.updateCameras(listOf(cam))
    FloatingOverlayStateHolder.openFloatingCamera("cam_rot")

    val openedWin = FloatingOverlayStateHolder.floatingWindows.value.first { it.cameraId == "cam_rot" }
    assertEquals(90f, openedWin.rotationDegrees, 0.01f)

    // Free rotation update syncs both floating window and live camera state
    FloatingOverlayStateHolder.setRotation("cam_rot", -37.5f)
    val rotatedWin = FloatingOverlayStateHolder.floatingWindows.value.first { it.cameraId == "cam_rot" }
    val rotatedCam = FloatingOverlayStateHolder.cameras.value.first { it.id == "cam_rot" }
    assertEquals(322.5f, rotatedWin.rotationDegrees, 0.01f)
    assertEquals(322.5f, rotatedCam.rotationDegrees, 0.01f)

    FloatingOverlayStateHolder.closeAll()
  }

  @Test
  fun onvifPtz_buildsRealSoapRequestsAndExtractsHost() {
    val cam = CameraEntity(
      id = "cam_ptz",
      name = "PTZ Dome",
      rtspUrl = "rtsp://192.168.1.120:554/h264Preview_01_main",
      username = "admin",
      password = "ptzPassword!",
      onvifPort = 8000
    )
    assertEquals(8000, cam.onvifPort)
    assertEquals("192.168.1.120", OnvifPtzClient.extractHost(cam.rtspUrl))
    assertEquals(null, OnvifPtzClient.extractHost("android.resource://com.example/raw/sample"))
    assertEquals(
      "http://192.168.1.120:8000/onvif/ptz_service",
      OnvifPtzClient.defaultPtzUrl("192.168.1.120", cam.onvifPort)
    )

    // Pan/Tilt-only cameras (e.g. Tapo C520WS) omit unused <tt:Zoom> to avoid 400 Bad Request
    val panOnlyBody = OnvifPtzClient.buildContinuousMoveBody(
      profileToken = "profile_1",
      panX = 0.6f,
      tiltY = 0f,
      zoomX = 0f
    )
    assertTrue(panOnlyBody.contains("<tt:PanTilt"))
    assertFalse(panOnlyBody.contains("<tt:Zoom"))

    // Combined PTZ includes both axes
    val moveBody = OnvifPtzClient.buildContinuousMoveBody(
      profileToken = "Profile_1",
      panX = 0.6f,
      tiltY = -0.3f,
      zoomX = 0.8f
    )
    assertTrue(moveBody.contains("<tptz:ContinuousMove>"))
    assertTrue(moveBody.contains("<tptz:ProfileToken>Profile_1</tptz:ProfileToken>"))
    assertTrue(moveBody.contains("x=\"0.60\""))
    assertTrue(moveBody.contains("y=\"-0.30\""))
    assertTrue(moveBody.contains("<tt:Zoom"))

    val stopBody = OnvifPtzClient.buildStopBody("Profile_1")
    assertTrue(stopBody.contains("<tptz:Stop>"))
    assertTrue(stopBody.contains("<tptz:PanTilt>true</tptz:PanTilt>"))
    assertTrue(stopBody.contains("<tptz:Zoom>true</tptz:Zoom>"))

    val soap = OnvifPtzClient.buildSoapEnvelope("admin", "ptzPassword!", moveBody)
    // Regression check: XML declaration must start at byte 0 (no leading spaces -> avoids HTTP 400)
    assertTrue(soap.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
    assertFalse(soap.first().isWhitespace())
    assertTrue(soap.contains("<wsse:Username>admin</wsse:Username>"))
    assertTrue(soap.contains("PasswordDigest"))
    assertTrue(soap.contains("<tptz:ContinuousMove>"))

    // Embedded RTSP credentials fallback when camera.username is empty
    val embeddedCam = CameraEntity(
      id = "tapo_cam",
      name = "C520WS",
      rtspUrl = "rtsp://tapoUser:tapoPass%21@192.168.1.148:554/stream1"
    )
    assertEquals("tapoUser" to "tapoPass!", OnvifPtzClient.resolveCredentials(embeddedCam))

    // Candidate ports always include 2020 (Tapo) and standard ONVIF ports without duplicates
    val ports = OnvifPtzClient.candidateOnvifPorts(80)
    assertEquals(80, ports.first())
    assertTrue(ports.contains(2020))

    // Prefers PTZ-enabled profile when GetProfiles returns multiple profiles
    val multiProfilesXml = """
      <trt:GetProfilesResponse>
        <trt:Profiles token="snapshot_only"><tt:Name>Snap</tt:Name></trt:Profiles>
        <trt:Profiles token="profile_1"><tt:Name>Main</tt:Name><tt:PTZConfiguration token="ptz_0"/></trt:Profiles>
      </trt:GetProfilesResponse>
    """.trimIndent()
    assertEquals("profile_1", OnvifPtzClient.extractFirstProfileToken(multiProfilesXml))
    assertEquals(listOf("snapshot_only", "profile_1"), OnvifPtzClient.extractAllProfileTokens(multiProfilesXml))

    // Parses GetSystemDateAndTime UTC clock for WS-Security PasswordDigest sync
    val sysTimeXml = """
      <tds:GetSystemDateAndTimeResponse>
        <tds:SystemDateAndTime>
          <tt:UTCDateTime>
            <tt:Time><tt:Hour>12</tt:Hour><tt:Minute>30</tt:Minute><tt:Second>45</tt:Second></tt:Time>
            <tt:Date><tt:Year>2026</tt:Year><tt:Month>11</tt:Month><tt:Day>13</tt:Day></tt:Date>
          </tt:UTCDateTime>
        </tds:SystemDateAndTime>
      </tds:GetSystemDateAndTimeResponse>
    """.trimIndent()
    assertNotNull(OnvifPtzClient.parseSystemDateAndTimeUtcMs(sysTimeXml))

    // Parses GetNodes capabilities (PanTilt-only without Zoom, plus exact Continuous velocity space URI)
    val nodesXml = """
      <tptz:GetNodesResponse>
        <tptz:PTZNode token="PTZNode">
          <tt:SupportedPTZSpaces>
            <tt:ContinuousPanTiltVelocitySpace>
              <tt:URI>http://www.onvif.org/ver10/tptz/PanTiltSpaces/VelocityGenericSpace</tt:URI>
            </tt:ContinuousPanTiltVelocitySpace>
            <tt:RelativePanTiltTranslationSpace>
              <tt:URI>http://www.onvif.org/ver10/tptz/PanTiltSpaces/TranslationGenericSpace</tt:URI>
            </tt:RelativePanTiltTranslationSpace>
          </tt:SupportedPTZSpaces>
        </tptz:PTZNode>
      </tptz:GetNodesResponse>
    """.trimIndent()
    val parsedCaps = OnvifPtzClient.parseNodeCapabilities(nodesXml)
    assertTrue(parsedCaps.supportsPanTilt)
    assertFalse(parsedCaps.supportsZoom)
    assertTrue(parsedCaps.supportsContinuousMove)
    assertTrue(parsedCaps.supportsRelativeMove)
    assertEquals(
      "http://www.onvif.org/ver10/tptz/PanTiltSpaces/VelocityGenericSpace",
      parsedCaps.continuousPanTiltSpaceUri
    )

    // Parses GetPresets & builds RelativeMove fallback + HTTP Digest header
    val presetsXml = """
      <tptz:GetPresetsResponse>
        <tptz:Preset token="preset_1"><tt:Name>Gate</tt:Name></tptz:Preset>
        <tptz:Preset token="preset_2"><tt:Name>Yard</tt:Name></tptz:Preset>
      </tptz:GetPresetsResponse>
    """.trimIndent()
    val presets = OnvifPtzClient.extractPresets(presetsXml)
    assertEquals(2, presets.size)
    assertEquals("Gate", presets[0].name)

    val relBody = OnvifPtzClient.buildRelativeMoveBody("profile_1", 0.12f, -0.05f, 0f)
    assertTrue(relBody.contains("<tptz:RelativeMove>"))
    assertTrue(relBody.contains("<tt:PanTilt"))
    assertFalse(relBody.contains("<tt:Zoom"))

    val digest = OnvifPtzClient.buildDigestAuthHeader(
      wwwAuthenticate = "Digest realm=\"TP-LINK IP Camera\", nonce=\"abc123\", qop=\"auth\"",
      url = "http://192.168.1.148:2020/onvif/service",
      method = "POST",
      username = "admin",
      password = "secret"
    )
    assertNotNull(digest)
    assertTrue(digest!!.startsWith("Digest username=\"admin\""))
    assertTrue(digest.contains("uri=\"/onvif/service\""))
  }

  @Test
  fun rotationGeometry_rotates16by9To9by16WithZeroBlackSides() {
    val srcAspect = parseResolutionAspectRatio("1920x1080")
    assertEquals(16f / 9f, srcAspect, 0.001f)

    // Container flips from 16:9 to 9:16 at 90° and 270°
    assertEquals(16f / 9f, computeEffectiveContainerAspectRatio(srcAspect, 0f), 0.001f)
    assertEquals(9f / 16f, computeEffectiveContainerAspectRatio(srcAspect, 90f), 0.001f)
    assertEquals(9f / 16f, computeEffectiveContainerAspectRatio(srcAspect, 270f), 0.001f)

    // In a 9:16 frame (900x1600) rotated 90°, cover frame is 1600x900 -> rotated 900x1600 (exact 0px black sides)
    val (coverW, coverH) = computeCoverFrameSizeForRotation(
      viewportW = 900f,
      viewportH = 1600f,
      sourceAspect = srcAspect,
      rotationDegrees = 90f
    )
    assertEquals(1600f, coverW, 0.5f)
    assertEquals(900f, coverH, 0.5f)

    // In taller fullscreen portrait phone (1080x1800) rotated 90°, cover frame fills entire width & height
    val (fullW, fullH) = computeCoverFrameSizeForRotation(
      viewportW = 1080f,
      viewportH = 1800f,
      sourceAspect = srcAspect,
      rotationDegrees = 90f
    )
    assertTrue(fullH >= 1080f - 0.1f)
    assertTrue(fullW >= 1800f - 0.1f)

    // Floating window rotates footprint cleanly from 200x112.5 to 112.5x200 and preserves inverse base width
    val (floatW, floatH) = resolveFloatingWindowSizeDp(
      baseWidthDp = 200f,
      effectiveAspect = 9f / 16f,
      maxWidthDp = 340f,
      maxHeightDp = 600f
    )
    assertEquals(112.5f, floatW, 0.2f)
    assertEquals(200f, floatH, 0.2f)
    assertEquals(200f, baseWidthFromActualWidthDp(floatW, 9f / 16f), 0.2f)
  }

  @Test
  fun cameraDiscovery_parsesOnvifWsDiscoveryAndRtspStreamUri() {
    val probeXml = CameraDiscoveryEngine.buildWsDiscoveryProbeXml("1111-2222")
    assertTrue(probeXml.contains("dn:NetworkVideoTransmitter"))
    assertTrue(probeXml.contains("uuid:1111-2222"))

    val sampleResponse = """
      <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
        <s:Body>
          <d:ProbeMatches>
            <d:ProbeMatch>
              <d:Scopes>onvif://www.onvif.org/type/Network_Video_Transmitter onvif://www.onvif.org/name/Reolink%20Front</d:Scopes>
              <d:XAddrs>http://192.168.1.120:8000/onvif/device_service</d:XAddrs>
            </d:ProbeMatch>
          </d:ProbeMatches>
        </s:Body>
      </s:Envelope>
    """.trimIndent()

    val parsed = CameraDiscoveryEngine.parseWsDiscoveryResponse(sampleResponse, "192.168.1.120")
    assertNotNull(parsed)
    assertEquals("192.168.1.120", parsed?.ipAddress)
    assertEquals(8000, parsed?.onvifPort)
    assertEquals("Reolink", parsed?.manufacturer)
    assertEquals("rtsp://192.168.1.120:554/h264Preview_01_main", parsed?.suggestedRtspUrl)

    val streamUriXml = """
      <trt:GetStreamUriResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
        <trt:MediaUri>
          <tt:Uri>rtsp://192.168.1.120:554/h264Preview_01_main?token=1&amp;ch=1</tt:Uri>
        </trt:MediaUri>
      </trt:GetStreamUriResponse>
    """.trimIndent()
    assertEquals(
      "rtsp://192.168.1.120:554/h264Preview_01_main?token=1&ch=1",
      OnvifPtzClient.extractStreamUriFromXml(streamUriXml)
    )

    val hosts = CameraDiscoveryEngine.enumerateSubnetHosts("192.168.1.42")
    assertEquals(253, hosts.size)
    assertFalse(hosts.contains("192.168.1.42"))
    assertTrue(hosts.contains("192.168.1.1"))
    assertTrue(hosts.contains("192.168.1.254"))
  }

  @Test
  fun rollingStorageRetention_calculatesCutoffAndReplacesExpiredClips() {
    val defaultCfg = CloudConfigEntity()
    assertTrue(defaultCfg.retentionEnabled)
    assertEquals(3, defaultCfg.retentionDays)
    assertEquals(0, defaultCfg.retentionHours)
    assertEquals(0, defaultCfg.retentionMinutes)
    assertEquals(3L * 24L * 60L * 60L * 1000L, defaultCfg.totalRetentionMillis())
    assertEquals("3d", defaultCfg.formatRetentionWindow())

    val customCfg = defaultCfg.copy(
      retentionDays = 1,
      retentionHours = 6,
      retentionMinutes = 30
    )
    val expectedMs = ((1L * 24L + 6L) * 60L + 30L) * 60L * 1000L
    assertEquals(expectedMs, customCfg.totalRetentionMillis())
    assertEquals("1d 6h 30m", customCfg.formatRetentionWindow())

    val disabledCfg = customCfg.copy(retentionEnabled = false)
    assertEquals(0L, disabledCfg.totalRetentionMillis())

    val now = 1_800_000_000_000L
    val threeDaysCutoff = StorageRetentionEngine.computeCutoffTimestamp(
      nowMillis = now,
      days = 3,
      hours = 0,
      minutes = 0
    )!!
    assertEquals(now - 3L * 86_400_000L, threeDaysCutoff)

    val fourDaysAgo = now - 4L * 86_400_000L
    val threeDaysAndOneSecAgo = now - (3L * 86_400_000L + 1_000L)
    val twoDaysAgo = now - 2L * 86_400_000L
    val fiveMinutesAgo = now - 5L * 60_000L

    val retained = StorageRetentionEngine.filterRetainedTimestamps(
      timestamps = listOf(fourDaysAgo, threeDaysAndOneSecAgo, twoDaysAgo, fiveMinutesAgo),
      cutoffTimestamp = threeDaysCutoff
    )
    assertEquals(listOf(twoDaysAgo, fiveMinutesAgo), retained)
  }

  @Test
  fun appLocaleManager_normalizesAndResolvesLanguagesCorrectly() {
    assertEquals(AppLocaleManager.LANG_TURKISH, AppLocaleManager.normalizeTag("tr"))
    assertEquals(AppLocaleManager.LANG_TURKISH, AppLocaleManager.normalizeTag("TR-tr"))
    assertEquals(AppLocaleManager.LANG_ENGLISH, AppLocaleManager.normalizeTag("en"))
    assertEquals(AppLocaleManager.LANG_ENGLISH, AppLocaleManager.normalizeTag("en-US"))
    assertEquals(AppLocaleManager.LANG_SYSTEM, AppLocaleManager.normalizeTag("system"))
    assertEquals(AppLocaleManager.LANG_SYSTEM, AppLocaleManager.normalizeTag("fr"))

    assertEquals("tr", AppLocaleManager.resolveEffectiveLanguage("system", "tr"))
    assertEquals("tr", AppLocaleManager.resolveEffectiveLanguage("system", "tr-TR"))
    assertEquals("en", AppLocaleManager.resolveEffectiveLanguage("system", "en"))
    assertEquals("en", AppLocaleManager.resolveEffectiveLanguage("system", "de"))
    assertEquals("tr", AppLocaleManager.resolveEffectiveLanguage("tr", "en"))
    assertEquals("en", AppLocaleManager.resolveEffectiveLanguage("en", "tr"))
  }

  @Test
  fun resilientChunking_calculatesUserDefinedIntervalsSafely() {
    val defaultCfg = CloudConfigEntity()
    assertEquals(0, defaultCfg.chunkDurationHours)
    assertEquals(5, defaultCfg.chunkDurationMinutes)
    assertTrue(defaultCfg.keepAppAlive)
    assertEquals(5L * 60L * 1000L, defaultCfg.totalChunkMillis())
    assertEquals("5m", defaultCfg.formatChunkWindow())

    val oneHourCfg = defaultCfg.copy(chunkDurationHours = 1, chunkDurationMinutes = 0)
    assertEquals(60L * 60L * 1000L, oneHourCfg.totalChunkMillis())
    assertEquals("1h", oneHourCfg.formatChunkWindow())

    val threeHoursCfg = defaultCfg.copy(chunkDurationHours = 3, chunkDurationMinutes = 0)
    assertEquals(3L * 60L * 60L * 1000L, threeHoursCfg.totalChunkMillis())
    assertEquals("3h", threeHoursCfg.formatChunkWindow())

    val mixedCfg = defaultCfg.copy(chunkDurationHours = 1, chunkDurationMinutes = 30)
    assertEquals(90L * 60L * 1000L, mixedCfg.totalChunkMillis())
    assertEquals("1h 30m", mixedCfg.formatChunkWindow())

    // Zero minutes safely clamps to minimum 1 minute so segments are always valid
    val zeroClamped = defaultCfg.copy(chunkDurationHours = 0, chunkDurationMinutes = 0)
    assertEquals(60_000L, zeroClamped.totalChunkMillis())
    assertEquals("1m", zeroClamped.formatChunkWindow())

    // Exceeding 24h safely clamps to 24h
    val maxClamped = defaultCfg.copy(chunkDurationHours = 48, chunkDurationMinutes = 30)
    assertEquals(24L * 60L * 60L * 1000L, maxClamped.totalChunkMillis())
    assertEquals("24h", maxClamped.formatChunkWindow())
  }

  @Test
  fun rtspWatchdog_sanitizesEndOfSourceBufferAndDetectsFrozenFrames() {
    // Regression check for screenshot telemetry bug: 9223372036821781ms at ~33s TIME_END_OF_SOURCE
    assertEquals(0L, RtspStreamManager.sanitizeBufferedLatencyMs(9_223_372_036_821_781L))
    assertEquals(0L, RtspStreamManager.sanitizeBufferedLatencyMs(-500L))
    assertEquals(420L, RtspStreamManager.sanitizeBufferedLatencyMs(420L))

    assertTrue(RtspStreamManager.isAbnormalBufferSentinel(9_223_372_036_821_781L, isLiveRtsp = true))
    assertFalse(RtspStreamManager.isAbnormalBufferSentinel(850L, isLiveRtsp = true))
    assertFalse(RtspStreamManager.isAbnormalBufferSentinel(9_223_372_036_821_781L, isLiveRtsp = false))

    // Normal advancing rendered frames keep stream LIVE with zero stall increment
    val healthy = RtspStreamManager.evaluateLiveVideoHealth(
      isLiveRtsp = true,
      isPlaying = true,
      playbackState = Player.STATE_READY,
      hasError = false,
      hasSelectedVideoTrack = true,
      rawBufferedMs = 320L,
      currentRenderedFrames = 900,
      previousRenderedFrames = 870,
      currentPositionMs = 30_000L,
      previousPositionMs = 29_000L
    )
    assertTrue(healthy.healthyProgress)
    assertEquals(0, healthy.stallIncrement)
    assertTrue(healthy.isLive)
    assertFalse(healthy.isReconnecting)

    // Frozen video frames (renderedOutputBufferCount stops increasing) while isPlaying stays true
    val frozenFrames = RtspStreamManager.evaluateLiveVideoHealth(
      isLiveRtsp = true,
      isPlaying = true,
      playbackState = Player.STATE_READY,
      hasError = false,
      hasSelectedVideoTrack = true,
      rawBufferedMs = 200L,
      currentRenderedFrames = 990,
      previousRenderedFrames = 990,
      currentPositionMs = 33_000L,
      previousPositionMs = 33_000L
    )
    assertFalse(frozenFrames.healthyProgress)
    assertEquals(1, frozenFrames.stallIncrement)
    assertFalse(frozenFrames.isLive)

    // TIME_END_OF_SOURCE buffer overflow while isPlaying=true triggers immediate fast reconnect weight
    val eosOverflow = RtspStreamManager.evaluateLiveVideoHealth(
      isLiveRtsp = true,
      isPlaying = true,
      playbackState = Player.STATE_READY,
      hasError = false,
      hasSelectedVideoTrack = false,
      rawBufferedMs = 9_223_372_036_821_781L,
      currentRenderedFrames = 990,
      previousRenderedFrames = 990,
      currentPositionMs = 32_994L,
      previousPositionMs = 32_994L
    )
    assertFalse(eosOverflow.healthyProgress)
    assertEquals(3, eosOverflow.stallIncrement)
    assertFalse(eosOverflow.isLive)
    assertTrue(eosOverflow.isReconnecting)

    // Live RTSP reaching STATE_ENDED is treated as dead stream needing immediate reconnect
    val rtspEnded = RtspStreamManager.evaluateLiveVideoHealth(
      isLiveRtsp = true,
      isPlaying = false,
      playbackState = Player.STATE_ENDED,
      hasError = false,
      hasSelectedVideoTrack = false,
      rawBufferedMs = 0L,
      currentRenderedFrames = 990,
      previousRenderedFrames = 990,
      currentPositionMs = 33_000L,
      previousPositionMs = 32_994L
    )
    assertFalse(rtspEnded.healthyProgress)
    assertEquals(3, rtspEnded.stallIncrement)
    assertTrue(rtspEnded.isReconnecting)

    // Startup grace prevents aggressive reconnect loops during initial RTSP handshake & first keyframe wait
    assertTrue(
      RtspStreamManager.isWithinStartupGrace(
        hasRenderedFrame = false,
        elapsedSincePrepareMs = 5_000L,
        hasError = false,
        playbackState = Player.STATE_BUFFERING
      )
    )
    assertFalse(
      RtspStreamManager.isWithinStartupGrace(
        hasRenderedFrame = false,
        elapsedSincePrepareMs = 15_000L,
        hasError = false,
        playbackState = Player.STATE_BUFFERING
      )
    )
    assertFalse(
      RtspStreamManager.isWithinStartupGrace(
        hasRenderedFrame = true,
        elapsedSincePrepareMs = 3_000L,
        hasError = false,
        playbackState = Player.STATE_READY
      )
    )
    assertFalse(
      RtspStreamManager.isWithinStartupGrace(
        hasRenderedFrame = false,
        elapsedSincePrepareMs = 3_000L,
        hasError = false,
        playbackState = Player.STATE_ENDED
      )
    )

    // When offscreen (e.g. on Alerts/Storage tab with no attached TextureView), advancing timeline stays healthy
    val offscreenAdvancing = RtspStreamManager.evaluateLiveVideoHealth(
      isLiveRtsp = true,
      isPlaying = true,
      playbackState = Player.STATE_READY,
      hasError = false,
      hasSelectedVideoTrack = true,
      rawBufferedMs = 250L,
      currentRenderedFrames = 990,
      previousRenderedFrames = 990,
      currentPositionMs = 42_000L,
      previousPositionMs = 41_000L,
      hasAttachedSurface = false
    )
    assertTrue(offscreenAdvancing.healthyProgress)
    assertEquals(0, offscreenAdvancing.stallIncrement)
  }
}
