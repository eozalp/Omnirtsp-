package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.CameraEntity
import com.example.data.model.RtspTransport
import com.example.data.model.StreamQuality
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceSurface
import java.util.UUID

@Composable
fun CameraSettingsDialog(
    camera: CameraEntity?,
    onDismiss: () -> Unit,
    onSave: (CameraEntity) -> Unit,
    onDelete: ((CameraEntity) -> Unit)? = null
) {
    var name by remember { mutableStateOf(camera?.name ?: "Security Cam ${System.currentTimeMillis() % 1000}") }
    var rtspUrl by remember { mutableStateOf(camera?.rtspUrl ?: "rtsp://192.168.1.100:554/h264Preview_01_main") }
    var username by remember { mutableStateOf(camera?.username ?: "") }
    var password by remember { mutableStateOf(camera?.password ?: "") }
    var isPasswordVisible by remember { mutableStateOf(false) }

    var location by remember { mutableStateOf(camera?.location ?: "Front Perimeter") }
    var transport by remember { mutableStateOf(camera?.transport ?: RtspTransport.TCP) }
    var isMotionEnabled by remember { mutableStateOf(camera?.isMotionDetectionEnabled ?: true) }
    var motionSensitivity by remember { mutableFloatStateOf((camera?.motionSensitivity ?: 65).toFloat()) }

    var showDeleteConfirm by remember { mutableStateOf(false) }

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
                    supportingText = { Text("e.g. rtsp://192.168.1.120:554/live/ch0") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cam_rtsp_url_input")
                )

                // RTSP Authentication Credentials Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurveillanceSurface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "RTSP Camera Credentials",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Username Field
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Camera Username (e.g. admin)") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = NeonCyan) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("cam_username_input")
                        )

                        // Password Field with Show/Hide toggle
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Camera Password") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = NeonCyan) },
                            trailingIcon = {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                                        tint = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            },
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("cam_password_input")
                        )
                    }
                }

                // Presets
                Text("Camera Presets:", fontSize = 12.sp, color = NeonCyan)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = rtspUrl.contains("Streaming/Channels"),
                        onClick = {
                            rtspUrl = "rtsp://192.168.1.64:554/Streaming/Channels/101"
                            if (username.isBlank()) username = "admin"
                        },
                        label = { Text("Hikvision", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = rtspUrl.contains("realmonitor"),
                        onClick = {
                            rtspUrl = "rtsp://192.168.1.108:554/cam/realmonitor?channel=1&subtype=0"
                            if (username.isBlank()) username = "admin"
                        },
                        label = { Text("Dahua / Amcrest", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = rtspUrl.contains("h264Preview"),
                        onClick = {
                            rtspUrl = "rtsp://192.168.1.120:554/h264Preview_01_main"
                            if (username.isBlank()) username = "admin"
                        },
                        label = { Text("Reolink", fontSize = 11.sp) }
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
                        label = { Text("TCP (Interleaved / Reliable)") }
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

                // Actions: Delete button (if editing existing) and Save/Cancel buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Delete Camera Button
                    if (camera != null && onDelete != null) {
                        Button(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonRed.copy(alpha = 0.2f),
                                contentColor = NeonRed
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("dialog_delete_camera_btn")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Camera", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Remove", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val entity = camera?.copy(
                                    name = name.ifBlank { "Camera" },
                                    rtspUrl = rtspUrl.ifBlank { "rtsp://demo" },
                                    username = username.trim(),
                                    password = password.trim(),
                                    location = location.ifBlank { "Main" },
                                    transport = transport,
                                    isMotionDetectionEnabled = isMotionEnabled,
                                    motionSensitivity = motionSensitivity.toInt()
                                ) ?: CameraEntity(
                                    id = UUID.randomUUID().toString(),
                                    name = name.ifBlank { "Camera" },
                                    rtspUrl = rtspUrl.ifBlank { "rtsp://demo" },
                                    username = username.trim(),
                                    password = password.trim(),
                                    location = location.ifBlank { "Main" },
                                    transport = transport,
                                    isMotionDetectionEnabled = isMotionEnabled,
                                    motionSensitivity = motionSensitivity.toInt()
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

    // Confirmation Alert Dialog for Deleting Camera
    if (showDeleteConfirm && camera != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = NeonRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Remove Camera?", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = "Are you sure you want to permanently remove '${camera.name}' (${camera.location})? This camera will be deleted from your matrix grid and active streams.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(camera)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonRed, contentColor = Color.White),
                    modifier = Modifier.testTag("confirm_delete_camera_btn")
                ) {
                    Text("Confirm Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = SurveillanceCardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
