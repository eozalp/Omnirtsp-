package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CloudConfigEntity
import com.example.data.model.CloudProviderType
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceDarkBg
import com.example.ui.theme.SurveillanceSurface
import com.example.ui.viewmodel.CameraViewModel

@Composable
fun CloudStorageScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val cloudConfig by viewModel.cloudConfig.collectAsState()
    val isTesting by viewModel.cloudStorageManager.isTestingConnection.collectAsState()
    val testResult by viewModel.cloudStorageManager.lastConnectionTestResult.collectAsState()

    var selectedProvider by remember(cloudConfig) { mutableStateOf(cloudConfig.provider) }
    var endpointUrl by remember(cloudConfig) { mutableStateOf(cloudConfig.endpointUrl) }
    var bucketOrFolder by remember(cloudConfig) { mutableStateOf(cloudConfig.bucketOrFolderName) }
    var apiKey by remember(cloudConfig) { mutableStateOf(cloudConfig.apiKeyOrToken) }
    var autoSyncRecordings by remember(cloudConfig) { mutableStateOf(cloudConfig.autoSyncRecordings) }
    var autoSyncMotion by remember(cloudConfig) { mutableStateOf(cloudConfig.autoSyncMotionSnapshots) }
    var wifiOnly by remember(cloudConfig) { mutableStateOf(cloudConfig.wifiOnly) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurveillanceDarkBg)
            .testTag("cloud_storage_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Cloud Storage Integration",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = NeonCyan
                    )
                    Text(
                        text = "Offsite backup & remote multi-device sync",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1E3A2B)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(NeonGreen)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("CLOUD READY", color = NeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Provider Selector
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Select Cloud Provider:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedProvider == CloudProviderType.GOOGLE_DRIVE,
                            onClick = {
                                selectedProvider = CloudProviderType.GOOGLE_DRIVE
                                endpointUrl = "https://www.googleapis.com/drive/v3"
                                bucketOrFolder = "OmniView-Surveillance-Backup"
                            },
                            label = { Text("Google Drive", fontSize = 11.sp) }
                        )

                        FilterChip(
                            selected = selectedProvider == CloudProviderType.AWS_S3_COMPATIBLE,
                            onClick = {
                                selectedProvider = CloudProviderType.AWS_S3_COMPATIBLE
                                endpointUrl = "https://s3.us-east-1.amazonaws.com"
                                bucketOrFolder = "cctv-archive-bucket"
                            },
                            label = { Text("AWS S3 / MinIO", fontSize = 11.sp) }
                        )

                        FilterChip(
                            selected = selectedProvider == CloudProviderType.WEBHOOK_REST_API,
                            onClick = {
                                selectedProvider = CloudProviderType.WEBHOOK_REST_API
                                endpointUrl = "https://cloud.surveillance.storage/v1/sync"
                                bucketOrFolder = "surveillance_clips"
                            },
                            label = { Text("Webhook API", fontSize = 11.sp) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedProvider == CloudProviderType.FTP_NAS_STORAGE,
                            onClick = {
                                selectedProvider = CloudProviderType.FTP_NAS_STORAGE
                                endpointUrl = "smb://192.168.1.50/surveillance"
                                bucketOrFolder = "NVR_Archive"
                            },
                            label = { Text("Local NAS / SMB", fontSize = 11.sp) }
                        )

                        FilterChip(
                            selected = selectedProvider == CloudProviderType.WEBDAV,
                            onClick = {
                                selectedProvider = CloudProviderType.WEBDAV
                                endpointUrl = "https://nextcloud.local/remote.php/dav"
                                bucketOrFolder = "cctv_backups"
                            },
                            label = { Text("NextCloud / WebDAV", fontSize = 11.sp) }
                        )
                    }
                }
            }

            // Connection Settings Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Endpoint & Credentials", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = endpointUrl,
                        onValueChange = { endpointUrl = it },
                        label = { Text("Endpoint URL / Host") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("cloud_endpoint_input")
                    )

                    OutlinedTextField(
                        value = bucketOrFolder,
                        onValueChange = { bucketOrFolder = it },
                        label = { Text("Storage Bucket / Target Folder") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("cloud_bucket_input")
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key / Bearer Token") },
                        placeholder = { Text("Optional for local storage or configured secrets") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("cloud_apikey_input")
                    )

                    // Handshake test button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val currentConfig = cloudConfig.copy(
                                    provider = selectedProvider,
                                    endpointUrl = endpointUrl,
                                    bucketOrFolderName = bucketOrFolder,
                                    apiKeyOrToken = apiKey
                                )
                                viewModel.testCloudConnection(currentConfig)
                            },
                            enabled = !isTesting,
                            modifier = Modifier.testTag("test_cloud_connection_btn")
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NeonCyan)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Handshaking...", fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.ElectricalServices, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Handshake", fontSize = 12.sp, color = NeonCyan)
                            }
                        }

                        Button(
                            onClick = {
                                val updated = cloudConfig.copy(
                                    provider = selectedProvider,
                                    endpointUrl = endpointUrl,
                                    bucketOrFolderName = bucketOrFolder,
                                    apiKeyOrToken = apiKey,
                                    autoSyncRecordings = autoSyncRecordings,
                                    autoSyncMotionSnapshots = autoSyncMotion,
                                    wifiOnly = wifiOnly
                                )
                                viewModel.saveCloudConfig(updated)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                            modifier = Modifier.testTag("save_cloud_config_btn")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save Config", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Handshake result output
                    testResult?.let { res ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = NeonGreen.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = res,
                                color = NeonGreen,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            // Sync Rules Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Automatic Sync Rules", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Auto-Upload Recorded Clips", color = Color.White, fontSize = 13.sp)
                            Text("Pushes clips immediately after recording completes", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoSyncRecordings,
                            onCheckedChange = {
                                autoSyncRecordings = it
                                viewModel.saveCloudConfig(cloudConfig.copy(autoSyncRecordings = it))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Auto-Upload Motion Snapshots", color = Color.White, fontSize = 13.sp)
                            Text("Backs up intrusion snapshots to cloud storage", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoSyncMotion,
                            onCheckedChange = {
                                autoSyncMotion = it
                                viewModel.saveCloudConfig(cloudConfig.copy(autoSyncMotionSnapshots = it))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Wi-Fi Only Sync", color = Color.White, fontSize = 13.sp)
                            Text("Preserves mobile data while on 4G/5G networks", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = {
                                wifiOnly = it
                                viewModel.saveCloudConfig(cloudConfig.copy(wifiOnly = it))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                        )
                    }
                }
            }
        }
    }
}
