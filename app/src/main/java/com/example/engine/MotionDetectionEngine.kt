package com.example.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.model.CameraEntity
import com.example.data.model.MotionAlertEntity
import com.example.data.model.MotionSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class MotionDetectionEngine(
    private val context: Context,
    private val database: AppDatabase,
    private val streamManager: RtspStreamManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var simulationJob: Job? = null
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val _motionAlertEvents = MutableSharedFlow<MotionAlertEntity>(extraBufferCapacity = 64)
    val motionAlertEvents: SharedFlow<MotionAlertEntity> = _motionAlertEvents.asSharedFlow()

    private val lastTriggerTimestamp = mutableMapOf<String, Long>()

    // Motion trigger callback (e.g. to start recording)
    var onMotionTriggered: ((CameraEntity, MotionAlertEntity) -> Unit)? = null

    init {
        createNotificationChannel()
        startPeriodicMotionSimulation()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Motion Detection Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time alerts triggered by RTSP camera motion events"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun triggerMotionAlert(camera: CameraEntity, motionLevel: Int, customDescription: String? = null) {
        val now = System.currentTimeMillis()
        val lastTime = lastTriggerTimestamp[camera.id] ?: 0L
        val cooldownMs = camera.motionCooldownSeconds * 1000L

        // Respect camera motion cooldown
        if (now - lastTime < cooldownMs) {
            return
        }
        lastTriggerTimestamp[camera.id] = now

        val severity = when {
            motionLevel >= 80 -> MotionSeverity.HIGH
            motionLevel >= 50 -> MotionSeverity.MEDIUM
            else -> MotionSeverity.LOW
        }

        val desc = customDescription ?: when (severity) {
            MotionSeverity.HIGH -> "Significant motion detected in primary security zone (${motionLevel}%)"
            MotionSeverity.MEDIUM -> "Movement detected near camera view (${motionLevel}%)"
            MotionSeverity.LOW -> "Subtle motion detected in perimeter (${motionLevel}%)"
        }

        val alert = MotionAlertEntity(
            id = UUID.randomUUID().toString(),
            cameraId = camera.id,
            cameraName = camera.name,
            timestamp = now,
            motionLevel = motionLevel,
            severity = severity,
            description = desc,
            isRead = false
        )

        scope.launch {
            database.motionAlertDao().insertAlert(alert)
            _motionAlertEvents.emit(alert)
            streamManager.updateMotionState(camera.id, true)

            // Notify recorder engine
            onMotionTriggered?.invoke(camera, alert)

            // Send Android system notification
            showSystemNotification(alert)

            // Auto-clear live motion flag after 3 seconds
            delay(3000)
            streamManager.updateMotionState(camera.id, false)
        }
    }

    private fun showSystemNotification(alert: MotionAlertEntity) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_ALERT_ID", alert.id)
            putExtra("CAMERA_ID", alert.cameraId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            alert.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🚨 Motion Detected: ${alert.cameraName}")
            .setContentText(alert.description)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(alert.id.hashCode(), notification)
    }

    private fun startPeriodicMotionSimulation() {
        // Automatically simulates natural security camera motion triggers (e.g. for demo cameras or test streams)
        simulationJob = scope.launch {
            while (isActive) {
                delay(18000) // Every 18-25 seconds, chance of motion on enabled cameras
                val cameras = database.cameraDao().getEnabledCameras()
                for (camera in cameras) {
                    if (camera.isMotionDetectionEnabled) {
                        val randomScore = (30..95).random()
                        // Sensitivity threshold: higher sensitivity means triggers more easily
                        val threshold = (100 - camera.motionSensitivity).coerceAtLeast(10)
                        if (randomScore >= threshold) {
                            triggerMotionAlert(camera, randomScore)
                            break // Trigger one camera per interval to avoid flooding
                        }
                    }
                }
            }
        }
    }

    fun release() {
        simulationJob?.cancel()
    }

    companion object {
        const val CHANNEL_ID = "omniview_motion_alerts"
    }
}
