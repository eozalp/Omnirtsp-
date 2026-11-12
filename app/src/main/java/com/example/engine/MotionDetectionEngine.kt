package com.example.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.model.CameraEntity
import com.example.data.model.MotionAlertEntity
import com.example.data.model.MotionSeverity
import com.example.i18n.AppLocaleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class MotionDetectionEngine(
    private val context: Context,
    private val database: AppDatabase,
    private val streamManager: RtspStreamManager
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val _motionAlertEvents = MutableSharedFlow<MotionAlertEntity>(extraBufferCapacity = 64)
    val motionAlertEvents: SharedFlow<MotionAlertEntity> = _motionAlertEvents.asSharedFlow()

    private val lastTriggerTimestamp = ConcurrentHashMap<String, Long>()
    private val previousFrameLuminance = ConcurrentHashMap<String, IntArray>()

    var onMotionTriggered: ((CameraEntity, MotionAlertEntity) -> Unit)? = null

    init {
        createNotificationChannel()
        ensureSnapshotsDirectory()
    }

    private fun ensureSnapshotsDirectory(): File {
        val dir = File(context.filesDir, "snapshots")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                AppLocaleManager.getString(context, R.string.alerts_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = AppLocaleManager.getString(context, R.string.alerts_channel_desc)
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Real-time Computer Vision Frame Differencing Algorithm:
     * Analyzes video frames pixel-by-pixel using luminance delta comparison.
     */
    fun processVideoFrame(camera: CameraEntity, frameBitmap: Bitmap) {
        if (!camera.isMotionDetectionEnabled) return

        scope.launch {
            try {
                // Downscale to 64x36 for high-performance low-overhead pixel differencing
                val width = 64
                val height = 36
                val scaled = Bitmap.createScaledBitmap(frameBitmap, width, height, false)
                val totalPixels = width * height
                val currentLuminance = IntArray(totalPixels)
                val pixels = IntArray(totalPixels)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)

                // Calculate ITU-R BT.601 luminance for each pixel
                for (i in 0 until totalPixels) {
                    val p = pixels[i]
                    val r = Color.red(p)
                    val g = Color.green(p)
                    val b = Color.blue(p)
                    currentLuminance[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                }

                val previous = previousFrameLuminance[camera.id]
                previousFrameLuminance[camera.id] = currentLuminance

                if (previous != null) {
                    var changedPixels = 0
                    val luminanceDeltaThreshold = 25 // Minimum grayscale intensity change per pixel

                    for (i in 0 until totalPixels) {
                        if (abs(currentLuminance[i] - previous[i]) > luminanceDeltaThreshold) {
                            changedPixels++
                        }
                    }

                    val motionScore = ((changedPixels.toFloat() / totalPixels) * 100).toInt()

                    // Sensitivity: 100 means trigger on 3% change, 50 means 15% change, 10 means 30% change
                    val triggerThreshold = ((100 - camera.motionSensitivity) * 0.3f + 3).toInt()

                    if (motionScore >= triggerThreshold) {
                        // Save real frame snapshot to disk
                        val snapshotFile = saveSnapshotToFile(camera.id, frameBitmap)
                        triggerMotionAlert(
                            camera = camera,
                            motionLevel = motionScore.coerceIn(1, 100),
                            snapshotPath = snapshotFile?.absolutePath,
                            customDescription = AppLocaleManager.getString(
                                context,
                                R.string.alerts_frame_var_desc,
                                motionScore,
                                triggerThreshold
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Frame processing error handling
            }
        }
    }

    private fun saveSnapshotToFile(cameraId: String, bitmap: Bitmap): File? {
        return try {
            val file = File(ensureSnapshotsDirectory(), "snap_${cameraId}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    fun triggerMotionAlert(
        camera: CameraEntity,
        motionLevel: Int,
        snapshotPath: String? = null,
        customDescription: String? = null
    ) {
        val now = System.currentTimeMillis()
        val lastTime = lastTriggerTimestamp[camera.id] ?: 0L
        val cooldownMs = camera.motionCooldownSeconds * 1000L

        // Respect camera motion cooldown
        if (now - lastTime < cooldownMs) {
            return
        }
        lastTriggerTimestamp[camera.id] = now

        val severity = when {
            motionLevel >= 60 -> MotionSeverity.HIGH
            motionLevel >= 30 -> MotionSeverity.MEDIUM
            else -> MotionSeverity.LOW
        }

        val desc = customDescription ?: when (severity) {
            MotionSeverity.HIGH -> AppLocaleManager.getString(context, R.string.alerts_desc_high, motionLevel)
            MotionSeverity.MEDIUM -> AppLocaleManager.getString(context, R.string.alerts_desc_medium, motionLevel)
            MotionSeverity.LOW -> AppLocaleManager.getString(context, R.string.alerts_desc_low, motionLevel)
        }

        val alert = MotionAlertEntity(
            id = UUID.randomUUID().toString(),
            cameraId = camera.id,
            cameraName = camera.name,
            timestamp = now,
            motionLevel = motionLevel,
            severity = severity,
            description = desc,
            snapshotPath = snapshotPath,
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

        createNotificationChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🚨 ${AppLocaleManager.getString(context, R.string.alerts_notif_title, alert.cameraName)}")
            .setContentText(alert.description)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(alert.id.hashCode(), notification)
    }

    fun release() {
        previousFrameLuminance.clear()
        lastTriggerTimestamp.clear()
    }

    companion object {
        const val CHANNEL_ID = "omniview_motion_alerts"
    }
}
