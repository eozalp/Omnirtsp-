package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.model.CameraEntity
import com.example.data.model.RtspTransport
import com.example.data.model.StreamQuality
import com.example.engine.DiscoveredCamera
import com.example.engine.DiscoveryScanState
import com.example.i18n.AppLocaleManager
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceSurface
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraSettingsDialog(
    camera: CameraEntity?,
    discoveryState: DiscoveryScanState = DiscoveryScanState(),
    onTriggerDiscoveryScan: (() -> Unit)? = null,
    onFetchOnvifRtspUrl: (suspend (rtspOrIp: String, onvifPort: Int, username: String, password: String) -> Result<String>)? = null,
    initialSuggestedCamera: DiscoveredCamera? = null,
    onDismiss: () -> Unit,
    onSave: (CameraEntity) -> Unit,
    onDelete: ((CameraEntity) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val defaultCamNum = remember { (System.currentTimeMillis() % 1000).toInt() }
    val defaultCamName = stringResource(R.string.cam_default_name, defaultCamNum)
    val defaultCamLocation = stringResource(R.string.cam_default_location)
    val suggestedNetLocation = initialSuggestedCamera?.let {
        stringResource(R.string.cam_network_location, it.ipAddress)
    }

    var name by remember {
        mutableStateOf(
            camera?.name
                ?: initialSuggestedCamera?.name
                ?: defaultCamName
        )
    }
    var rtspUrl by remember {
        mutableStateOf(
            camera?.rtspUrl
                ?: initialSuggestedCamera?.suggestedRtspUrl
                ?: "rtsp://192.168.1.100:554/h264Preview_01_main"
        )
    }
    var username by remember { mutableStateOf(camera?.username ?: "") }
    var password by remember { mutableStateOf(camera?.password ?: "") }
    var isPasswordVisible by remember { mutableStateOf(false) }

    var location by remember {
        mutableStateOf(
            camera?.location
                ?: suggestedNetLocation
                ?: defaultCamLocation
        )
    }
    var transport by remember { mutableStateOf(camera?.transport ?: RtspTransport.TCP) }
    var onvifPortText by remember {
        mutableStateOf((camera?.onvifPort ?: initialSuggestedCamera?.onvifPort ?: 80).toString())
    }
    var isMotionEnabled by remember { mutableStateOf(camera?.isMotionDetectionEnabled ?: true) }
    var motionSensitivity by remember { mutableFloatStateOf((camera?.motionSensitivity ?: 65).toFloat()) }

    var isFetchingStreamUri by remember { mutableStateOf(false) }
    var streamFetchStatus by remember { mutableStateOf<String?>(null) }
    var streamFetchIsError by remember { mutableStateOf(false) }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (camera == null && !discoveryState.hasScannedAtLeastOnce && !discoveryState.isScanning) {
            onTriggerDiscoveryScan?.invoke()
        }
    }

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
                            text = if (camera == null) {
                                stringResource(R.string.cam_dialog_add_title)
                            } else {
                                stringResource(R.string.cam_dialog_edit_title)
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = Color.White)
                    }
                }

                // Auto-Discovered Network Cameras Section
                if (onTriggerDiscoveryScan != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurveillanceSurface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("network_discovery_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                ) {
                                    Icon(Icons.Default.Router, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.cam_autofind_title),
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                OutlinedButton(
                                    onClick = onTriggerDiscoveryScan,
                                    enabled = !discoveryState.isScanning,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("scan_network_cameras_btn")
                                ) {
                                    if (discoveryState.isScanning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 2.dp,
                                            color = NeonCyan
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.cam_scanning_short), fontSize = 11.sp, color = NeonCyan, maxLines = 1, softWrap = false)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.cam_scan_lan), fontSize = 11.sp, color = NeonCyan, maxLines = 1, softWrap = false)
                                    }
                                }
                            }

                            if (discoveryState.results.isNotEmpty()) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    discoveryState.results.forEach { found ->
                                        val foundLocationText = stringResource(R.string.cam_network_location, found.ipAddress)
                                        FilterChip(
                                            selected = rtspUrl.contains(found.ipAddress),
                                            onClick = {
                                                name = found.name
                                                rtspUrl = found.suggestedRtspUrl
                                                onvifPortText = found.onvifPort.toString()
                                                location = foundLocationText
                                                if (username.isBlank()) username = "admin"
                                            },
                                            label = {
                                                Text(
                                                    text = "${found.name} • ${found.ipAddress}",
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        )
                                    }
                                }
                            } else if (discoveryState.progressText.isNotBlank()) {
                                Text(
                                    text = discoveryState.progressText,
                                    color = if (discoveryState.errorMessage != null) NeonAmber else Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // Camera Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.cam_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cam_name_input")
                )

                // RTSP Stream URL
                OutlinedTextField(
                    value = rtspUrl,
                    onValueChange = { rtspUrl = it },
                    label = { Text(stringResource(R.string.cam_rtsp_url_label)) },
                    supportingText = { Text(stringResource(R.string.cam_rtsp_url_hint)) },
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
                                text = stringResource(R.string.cam_credentials_title),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Username Field
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.cam_username_label)) },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = NeonCyan) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("cam_username_input")
                        )

                        // Password Field with Show/Hide toggle
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.cam_password_label)) },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = NeonCyan) },
                            trailingIcon = {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (isPasswordVisible) {
                                            stringResource(R.string.cam_hide_password)
                                        } else {
                                            stringResource(R.string.cam_show_password)
                                        },
                                        tint = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            },
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("cam_password_input")
                        )

                        if (onFetchOnvifRtspUrl != null) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        isFetchingStreamUri = true
                                        streamFetchStatus = null
                                        val parsedPort = (onvifPortText.toIntOrNull() ?: 80).coerceIn(1, 65535)
                                        onFetchOnvifRtspUrl(rtspUrl, parsedPort, username, password)
                                            .onSuccess { fetchedUrl ->
                                                rtspUrl = fetchedUrl
                                                streamFetchIsError = false
                                                streamFetchStatus = AppLocaleManager.getString(
                                                    context,
                                                    R.string.cam_onvif_url_found,
                                                    fetchedUrl
                                                )
                                            }
                                            .onFailure { err ->
                                                streamFetchIsError = true
                                                streamFetchStatus = err.message ?: AppLocaleManager.getString(
                                                    context,
                                                    R.string.cam_onvif_url_failed
                                                )
                                            }
                                        isFetchingStreamUri = false
                                    }
                                },
                                enabled = !isFetchingStreamUri,
                                modifier = Modifier.fillMaxWidth().testTag("fetch_onvif_stream_btn")
                            ) {
                                if (isFetchingStreamUri) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = NeonCyan)
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = stringResource(R.string.cam_fetch_onvif_url),
                                    color = NeonCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            streamFetchStatus?.let { msg ->
                                Text(
                                    text = msg,
                                    color = if (streamFetchIsError) NeonAmber else NeonGreen,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // Presets
                Text(stringResource(R.string.cam_presets_label), fontSize = 12.sp, color = NeonCyan)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = rtspUrl.contains("Streaming/Channels"),
                        onClick = {
                            rtspUrl = "rtsp://192.168.1.64:554/Streaming/Channels/101"
                            if (username.isBlank()) username = "admin"
                        },
                        label = { Text("Hikvision", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = rtspUrl.contains("realmonitor"),
                        onClick = {
                            rtspUrl = "rtsp://192.168.1.108:554/cam/realmonitor?channel=1&subtype=0"
                            if (username.isBlank()) username = "admin"
                        },
                        label = { Text("Dahua / Amcrest", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = rtspUrl.contains("h264Preview"),
                        onClick = {
                            rtspUrl = "rtsp://192.168.1.120:554/h264Preview_01_main"
                            if (username.isBlank()) username = "admin"
                        },
                        label = { Text("Reolink", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    )
                }

                // Location Tag
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text(stringResource(R.string.cam_location_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cam_location_input")
                )

                // Transport Protocol (TCP vs UDP)
                Text(stringResource(R.string.cam_transport_label), fontSize = 13.sp, color = Color.White)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = transport == RtspTransport.TCP,
                        onClick = { transport = RtspTransport.TCP },
                        label = { Text(stringResource(R.string.cam_tcp_label), maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = transport == RtspTransport.UDP,
                        onClick = { transport = RtspTransport.UDP },
                        label = { Text(stringResource(R.string.cam_udp_label), maxLines = 1, softWrap = false) }
                    )
                }

                // ONVIF PTZ Port
                OutlinedTextField(
                    value = onvifPortText,
                    onValueChange = { input -> onvifPortText = input.filter { it.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.cam_onvif_port_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cam_onvif_port_input")
                )

                // Motion Detection Switch & Sensitivity
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(stringResource(R.string.cam_motion_alerts_title), color = Color.White, fontSize = 14.sp)
                        Text(stringResource(R.string.cam_motion_alerts_sub), color = Color.Gray, fontSize = 11.sp)
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
                            Text(stringResource(R.string.cam_sensitivity_threshold), fontSize = 12.sp, color = Color.White)
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

                // Actions: Responsive FlowRow so Remove, Cancel, and Save never truncate or wrap mid-label
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (camera != null && onDelete != null) {
                        Button(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonRed.copy(alpha = 0.2f),
                                contentColor = NeonRed
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier.testTag("dialog_delete_camera_btn")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.live_menu_remove_camera), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.common_remove), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }

                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(stringResource(R.string.common_cancel), color = Color.Gray, maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = {
                            val parsedPort = (onvifPortText.toIntOrNull() ?: 80).coerceIn(1, 65535)
                            val entity = camera?.copy(
                                name = name.ifBlank { defaultCamName },
                                rtspUrl = rtspUrl.ifBlank { "rtsp://demo" },
                                username = username.trim(),
                                password = password.trim(),
                                location = location.ifBlank { defaultCamLocation },
                                transport = transport,
                                onvifPort = parsedPort,
                                isMotionDetectionEnabled = isMotionEnabled,
                                motionSensitivity = motionSensitivity.toInt()
                            ) ?: CameraEntity(
                                id = UUID.randomUUID().toString(),
                                name = name.ifBlank { defaultCamName },
                                rtspUrl = rtspUrl.ifBlank { "rtsp://demo" },
                                username = username.trim(),
                                password = password.trim(),
                                location = location.ifBlank { defaultCamLocation },
                                transport = transport,
                                onvifPort = parsedPort,
                                isMotionDetectionEnabled = isMotionEnabled,
                                motionSensitivity = motionSensitivity.toInt()
                            )
                            onSave(entity)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier.testTag("save_camera_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
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
                    Text(stringResource(R.string.live_remove_dialog_title), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = stringResource(R.string.cam_delete_confirm_body, camera.name, camera.location),
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
                    Text(stringResource(R.string.cam_confirm_delete), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel), color = Color.Gray, maxLines = 1, softWrap = false)
                }
            },
            containerColor = SurveillanceCardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
