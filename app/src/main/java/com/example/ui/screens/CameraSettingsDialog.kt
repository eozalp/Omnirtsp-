package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.CameraEntity
import com.example.data.model.RtspTransport
import com.example.data.model.StreamQuality
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceSurface
import java.util.UUID

@Composable
fun CameraSettingsDialog(
    camera: CameraEntity?,
    onDismiss: () -> Unit,
    onSave: (CameraEntity) -> Unit
) {
    var name by remember { mutableStateOf(camera?.name ?: "Security Cam ${System.currentTimeMillis() % 1000}") }
    var rtspUrl by remember { mutableStateOf(camera?.rtspUrl ?: "rtsp://192.168.1.100:554/h264Preview_01_main") }
    var location by remember { mutableStateOf(camera?.location ?: "Front Perimeter") }
    var transport by remember { mutableStateOf(camera?.transport ?: RtspTransport.TCP) }
    var isMotionEnabled by remember { mutableStateOf(camera?.isMotionDetectionEnabled ?: true) }
    var motionSensitivity by remember { mutableFloatStateOf((camera?.motionSensitivity ?: 65).toFloat()) }
    var isDemoCamera by remember { mutableStateOf(camera?.isDemoCamera ?: false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .testTag("camera_settings_dialog"),
            color = SurveillanceCardBg,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = NeonCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (camera == null) "Add RTSP Camera" else "Configure Camera",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Camera Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Camera Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cam_name_input")
                )

                // RTSP Stream URL
                OutlinedTextField(
                    value = rtspUrl,
                    onValueChange = { rtspUrl = it },
                    label = { Text("RTSP Stream URL") },
                    supportingText = { Text("e.g. rtsp://user:pass@192.168.1.x:554/live") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cam_rtsp_url_input")
                )

                // Presets
                Text("Quick Presets:", fontSize = 12.sp, color = NeonCyan)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = rtspUrl.contains("wowza"),
                        onClick = {
                            rtspUrl = "rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mp4"
                            isDemoCamera = false
                        },
                        label = { Text("Public RTSP", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = isDemoCamera,
                        onClick = {
                            isDemoCamera = true
                            rtspUrl = "rtsp://demo.rtsp.stream/synthetic_feed"
                        },
                        label = { Text("Demo Sentry", fontSize = 11.sp) }
                    )
                }

                // Location Tag
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location / Zone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cam_location_input")
                )

                // Transport Protocol (TCP vs UDP)
                Text("RTSP Transport Protocol:", fontSize = 13.sp, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = transport == RtspTransport.TCP,
                        onClick = { transport = RtspTransport.TCP },
                        label = { Text("TCP (Reliable / Interleaved)") }
                    )
                    FilterChip(
                        selected = transport == RtspTransport.UDP,
                        onClick = { transport = RtspTransport.UDP },
                        label = { Text("UDP (Low Latency)") }
                    )
                }

                // Motion Detection Switch & Sensitivity
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Motion Detection Alerts", color = Color.White, fontSize = 14.sp)
                        Text("Triggers alerts & auto-recording", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = isMotionEnabled,
                        onCheckedChange = { isMotionEnabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                    )
                }

                if (isMotionEnabled) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Sensitivity Threshold", fontSize = 12.sp, color = Color.White)
                            Text("${motionSensitivity.toInt()}%", fontSize = 12.sp, color = NeonCyan, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = motionSensitivity,
                            onValueChange = { motionSensitivity = it },
                            valueRange = 10f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = NeonCyan,
                                activeTrackColor = NeonCyan
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val entity = camera?.copy(
                                name = name.ifBlank { "Camera" },
                                rtspUrl = rtspUrl.ifBlank { "rtsp://demo" },
                                location = location.ifBlank { "Main" },
                                transport = transport,
                                isMotionDetectionEnabled = isMotionEnabled,
                                motionSensitivity = motionSensitivity.toInt(),
                                isDemoCamera = isDemoCamera
                            ) ?: CameraEntity(
                                id = UUID.randomUUID().toString(),
                                name = name.ifBlank { "Camera" },
                                rtspUrl = rtspUrl.ifBlank { "rtsp://demo" },
                                location = location.ifBlank { "Main" },
                                transport = transport,
                                isMotionDetectionEnabled = isMotionEnabled,
                                motionSensitivity = motionSensitivity.toInt(),
                                isDemoCamera = isDemoCamera
                            )
                            onSave(entity)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                        modifier = Modifier.testTag("save_camera_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("Save Camera")
                    }
                }
            }
        }
    }
}
