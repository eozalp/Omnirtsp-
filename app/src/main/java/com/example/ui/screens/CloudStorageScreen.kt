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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CloudConfigEntity
import com.example.data.model.CloudProviderType
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
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

    // S3 credentials
    var s3Endpoint by remember(cloudConfig) { mutableStateOf(cloudConfig.s3Endpoint) }
    var s3Bucket by remember(cloudConfig) { mutableStateOf(cloudConfig.s3Bucket) }
    var s3Region by remember(cloudConfig) { mutableStateOf(cloudConfig.s3Region) }
    var s3AccessKeyId by remember(cloudConfig) { mutableStateOf(cloudConfig.s3AccessKeyId) }
    var s3SecretAccessKey by remember(cloudConfig) { mutableStateOf(cloudConfig.s3SecretAccessKey) }
    var isS3SecretVisible by remember { mutableStateOf(false) }

    // WebDAV credentials
    var webdavUrl by remember(cloudConfig) { mutableStateOf(cloudConfig.endpointUrl) }
    var webdavUsername by remember(cloudConfig) { mutableStateOf(cloudConfig.webdavUsername) }
    var webdavPassword by remember(cloudConfig) { mutableStateOf(cloudConfig.webdavPassword) }
    var isWebdavPasswordVisible by remember { mutableStateOf(false) }

    // Google Drive credentials
    var gdriveFolder by remember(cloudConfig) { mutableStateOf(cloudConfig.bucketOrFolderName) }
    var gdriveToken by remember(cloudConfig) { mutableStateOf(cloudConfig.apiKeyOrToken) }
    var isGdriveTokenVisible by remember { mutableStateOf(false) }

    // NAS credentials
    var nasUrl by remember(cloudConfig) { mutableStateOf(cloudConfig.endpointUrl) }
    var nasUsername by remember(cloudConfig) { mutableStateOf(cloudConfig.nasUsername) }
    var nasPassword by remember(cloudConfig) { mutableStateOf(cloudConfig.nasPassword) }
    var isNasPasswordVisible by remember { mutableStateOf(false) }

    // Webhook credentials
    var webhookUrl by remember(cloudConfig) { mutableStateOf(cloudConfig.endpointUrl) }
    var webhookHeaderKey by remember(cloudConfig) { mutableStateOf(cloudConfig.customHeaderName) }
    var webhookHeaderValue by remember(cloudConfig) { mutableStateOf(cloudConfig.customHeaderValue) }
    var isWebhookSecretVisible by remember { mutableStateOf(false) }

    // Sync rules
    var autoSyncRecordings by remember(cloudConfig) { mutableStateOf(cloudConfig.autoSyncRecordings) }
    var autoSyncMotion by remember(cloudConfig) { mutableStateOf(cloudConfig.autoSyncMotionSnapshots) }
    var wifiOnly by remember(cloudConfig) { mutableStateOf(cloudConfig.wifiOnly) }

    var saveConfirmationMessage by remember { mutableStateOf<String?>(null) }

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
                        text = "Real offsite backup & encrypted remote sync",
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
                        Text("PRODUCTION", color = NeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Provider Selector
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Storage Protocol / Target:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedProvider == CloudProviderType.AWS_S3_COMPATIBLE,
                            onClick = { selectedProvider = CloudProviderType.AWS_S3_COMPATIBLE },
                            label = { Text("AWS S3 / MinIO", fontSize = 11.sp) },
                            modifier = Modifier.testTag("chip_provider_s3")
                        )

                        FilterChip(
                            selected = selectedProvider == CloudProviderType.WEBDAV,
                            onClick = { selectedProvider = CloudProviderType.WEBDAV },
                            label = { Text("NextCloud / WebDAV", fontSize = 11.sp) },
                            modifier = Modifier.testTag("chip_provider_webdav")
                        )

                        FilterChip(
                            selected = selectedProvider == CloudProviderType.GOOGLE_DRIVE,
                            onClick = { selectedProvider = CloudProviderType.GOOGLE_DRIVE },
                            label = { Text("Google Drive", fontSize = 11.sp) },
                            modifier = Modifier.testTag("chip_provider_gdrive")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedProvider == CloudProviderType.FTP_NAS_STORAGE,
                            onClick = { selectedProvider = CloudProviderType.FTP_NAS_STORAGE },
                            label = { Text("Local NAS / SMB Gateway", fontSize = 11.sp) },
                            modifier = Modifier.testTag("chip_provider_nas")
                        )

                        FilterChip(
                            selected = selectedProvider == CloudProviderType.WEBHOOK_REST_API,
                            onClick = { selectedProvider = CloudProviderType.WEBHOOK_REST_API },
                            label = { Text("Custom Webhook / REST", fontSize = 11.sp) },
                            modifier = Modifier.testTag("chip_provider_webhook")
                        )
                    }
                }
            }

            // Real Provider-Specific Credentials Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (selectedProvider) {
                                CloudProviderType.AWS_S3_COMPATIBLE -> "AWS S3 & MinIO Real Credentials"
                                CloudProviderType.WEBDAV -> "WebDAV / NextCloud Authentication"
                                CloudProviderType.GOOGLE_DRIVE -> "Google Drive Storage Credentials"
                                CloudProviderType.FTP_NAS_STORAGE -> "Local NAS Gateway Credentials"
                                CloudProviderType.WEBHOOK_REST_API -> "Webhook URL & API Key / Secret"
                            },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    when (selectedProvider) {
                        CloudProviderType.AWS_S3_COMPATIBLE -> {
                            OutlinedTextField(
                                value = s3Endpoint,
                                onValueChange = { s3Endpoint = it },
                                label = { Text("S3 Endpoint (e.g. https://s3.amazonaws.com or MinIO IP)") },
                                placeholder = { Text("https://s3.us-east-1.amazonaws.com") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("s3_endpoint_input")
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = s3Bucket,
                                    onValueChange = { s3Bucket = it },
                                    label = { Text("Bucket Name") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).testTag("s3_bucket_input")
                                )
                                OutlinedTextField(
                                    value = s3Region,
                                    onValueChange = { s3Region = it },
                                    label = { Text("Region") },
                                    placeholder = { Text("us-east-1") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).testTag("s3_region_input")
                                )
                            }

                            OutlinedTextField(
                                value = s3AccessKeyId,
                                onValueChange = { s3AccessKeyId = it },
                                label = { Text("AWS Access Key ID / User") },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = NeonCyan) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("s3_access_key_input")
                            )

                            OutlinedTextField(
                                value = s3SecretAccessKey,
                                onValueChange = { s3SecretAccessKey = it },
                                label = { Text("AWS Secret Access Key") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = NeonCyan) },
                                trailingIcon = {
                                    IconButton(onClick = { isS3SecretVisible = !isS3SecretVisible }) {
                                        Icon(
                                            imageVector = if (isS3SecretVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (isS3SecretVisible) "Hide Secret" else "Show Secret",
                                            tint = Color.White.copy(alpha = 0.7f)
                                        )
                                    }
                                },
                                visualTransformation = if (isS3SecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("s3_secret_key_input")
                            )
                        }

                        CloudProviderType.WEBDAV -> {
                            OutlinedTextField(
                                value = webdavUrl,
                                onValueChange = { webdavUrl = it },
                                label = { Text("WebDAV Target URL") },
                                placeholder = { Text("https://nextcloud.example.com/remote.php/dav/files/user/cctv") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("webdav_url_input")
                            )

                            OutlinedTextField(
                                value = webdavUsername,
                                onValueChange = { webdavUsername = it },
                                label = { Text("WebDAV Username") },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = NeonCyan) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("webdav_username_input")
                            )

                            OutlinedTextField(
                                value = webdavPassword,
                                onValueChange = { webdavPassword = it },
                                label = { Text("WebDAV Password / App Token") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = NeonCyan) },
                                trailingIcon = {
                                    IconButton(onClick = { isWebdavPasswordVisible = !isWebdavPasswordVisible }) {
                                        Icon(
                                            imageVector = if (isWebdavPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = "Toggle password visibility",
                                            tint = Color.White.copy(alpha = 0.7f)
                                        )
                                    }
                                },
                                visualTransformation = if (isWebdavPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("webdav_password_input")
                            )
                        }

                        CloudProviderType.GOOGLE_DRIVE -> {
                            OutlinedTextField(
                                value = gdriveFolder,
                                onValueChange = { gdriveFolder = it },
                                label = { Text("Google Drive Folder ID / Name") },
                                placeholder = { Text("OmniView-Surveillance-Backup") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("gdrive_folder_input")
                            )

                            OutlinedTextField(
                                value = gdriveToken,
                                onValueChange = { gdriveToken = it },
                                label = { Text("OAuth 2.0 Access Token / API Key") },
                                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = NeonCyan) },
                                trailingIcon = {
                                    IconButton(onClick = { isGdriveTokenVisible = !isGdriveTokenVisible }) {
                                        Icon(
                                            imageVector = if (isGdriveTokenVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = "Toggle token visibility",
                                            tint = Color.White.copy(alpha = 0.7f)
                                        )
                                    }
                                },
                                visualTransformation = if (isGdriveTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("gdrive_token_input")
                            )
                        }

                        CloudProviderType.FTP_NAS_STORAGE -> {
                            OutlinedTextField(
                                value = nasUrl,
                                onValueChange = { nasUrl = it },
                                label = { Text("NAS Storage REST Gateway URL") },
                                placeholder = { Text("http://192.168.1.50:5000/api/upload") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("nas_url_input")
                            )

                            OutlinedTextField(
                                value = nasUsername,
                                onValueChange = { nasUsername = it },
                                label = { Text("NAS Username") },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = NeonCyan) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("nas_username_input")
                            )

                            OutlinedTextField(
                                value = nasPassword,
                                onValueChange = { nasPassword = it },
                                label = { Text("NAS Password") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = NeonCyan) },
                                trailingIcon = {
                                    IconButton(onClick = { isNasPasswordVisible = !isNasPasswordVisible }) {
                                        Icon(
                                            imageVector = if (isNasPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = "Toggle password visibility",
                                            tint = Color.White.copy(alpha = 0.7f)
                                        )
                                    }
                                },
                                visualTransformation = if (isNasPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("nas_password_input")
                            )
                        }

                        CloudProviderType.WEBHOOK_REST_API -> {
                            OutlinedTextField(
                                value = webhookUrl,
                                onValueChange = { webhookUrl = it },
                                label = { Text("Webhook URL") },
                                placeholder = { Text("https://api.mycloud.com/surveillance/webhook") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("webhook_url_input")
                            )

                            OutlinedTextField(
                                value = webhookHeaderKey,
                                onValueChange = { webhookHeaderKey = it },
                                label = { Text("Header Key (e.g. Authorization or X-API-Key)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("webhook_header_key_input")
                            )

                            OutlinedTextField(
                                value = webhookHeaderValue,
                                onValueChange = { webhookHeaderValue = it },
                                label = { Text("Header Value / Token / Secret") },
                                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = NeonCyan) },
                                trailingIcon = {
                                    IconButton(onClick = { isWebhookSecretVisible = !isWebhookSecretVisible }) {
                                        Icon(
                                            imageVector = if (isWebhookSecretVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = "Toggle secret visibility",
                                            tint = Color.White.copy(alpha = 0.7f)
                                        )
                                    }
                                },
                                visualTransformation = if (isWebhookSecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("webhook_secret_input")
                            )
                        }
                    }

                    // Handshake test & Save buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                saveConfirmationMessage = null
                                val testConfig = cloudConfig.copy(
                                    provider = selectedProvider,
                                    endpointUrl = when (selectedProvider) {
                                        CloudProviderType.AWS_S3_COMPATIBLE -> s3Endpoint
                                        CloudProviderType.WEBDAV -> webdavUrl
                                        CloudProviderType.GOOGLE_DRIVE -> "https://www.googleapis.com/drive/v3"
                                        CloudProviderType.FTP_NAS_STORAGE -> nasUrl
                                        CloudProviderType.WEBHOOK_REST_API -> webhookUrl
                                    },
                                    bucketOrFolderName = when (selectedProvider) {
                                        CloudProviderType.AWS_S3_COMPATIBLE -> s3Bucket
                                        CloudProviderType.GOOGLE_DRIVE -> gdriveFolder
                                        else -> "surveillance"
                                    },
                                    s3Endpoint = s3Endpoint,
                                    s3Bucket = s3Bucket,
                                    s3Region = s3Region,
                                    s3AccessKeyId = s3AccessKeyId,
                                    s3SecretAccessKey = s3SecretAccessKey,
                                    webdavUsername = webdavUsername,
                                    webdavPassword = webdavPassword,
                                    nasUsername = nasUsername,
                                    nasPassword = nasPassword,
                                    customHeaderName = webhookHeaderKey,
                                    customHeaderValue = webhookHeaderValue,
                                    apiKeyOrToken = when (selectedProvider) {
                                        CloudProviderType.GOOGLE_DRIVE -> gdriveToken
                                        CloudProviderType.WEBHOOK_REST_API -> webhookHeaderValue
                                        else -> ""
                                    }
                                )
                                viewModel.testCloudConnection(testConfig)
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
                                    endpointUrl = when (selectedProvider) {
                                        CloudProviderType.AWS_S3_COMPATIBLE -> s3Endpoint
                                        CloudProviderType.WEBDAV -> webdavUrl
                                        CloudProviderType.GOOGLE_DRIVE -> "https://www.googleapis.com/drive/v3"
                                        CloudProviderType.FTP_NAS_STORAGE -> nasUrl
                                        CloudProviderType.WEBHOOK_REST_API -> webhookUrl
                                    },
                                    bucketOrFolderName = when (selectedProvider) {
                                        CloudProviderType.AWS_S3_COMPATIBLE -> s3Bucket
                                        CloudProviderType.GOOGLE_DRIVE -> gdriveFolder
                                        else -> "surveillance"
                                    },
                                    s3Endpoint = s3Endpoint,
                                    s3Bucket = s3Bucket,
                                    s3Region = s3Region,
                                    s3AccessKeyId = s3AccessKeyId,
                                    s3SecretAccessKey = s3SecretAccessKey,
                                    webdavUsername = webdavUsername,
                                    webdavPassword = webdavPassword,
                                    nasUsername = nasUsername,
                                    nasPassword = nasPassword,
                                    customHeaderName = webhookHeaderKey,
                                    customHeaderValue = webhookHeaderValue,
                                    apiKeyOrToken = when (selectedProvider) {
                                        CloudProviderType.GOOGLE_DRIVE -> gdriveToken
                                        CloudProviderType.WEBHOOK_REST_API -> webhookHeaderValue
                                        else -> ""
                                    },
                                    autoSyncRecordings = autoSyncRecordings,
                                    autoSyncMotionSnapshots = autoSyncMotion,
                                    wifiOnly = wifiOnly
                                )
                                viewModel.saveCloudConfig(updated)
                                saveConfirmationMessage = "Configuration saved for ${selectedProvider.name}!"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                            modifier = Modifier.testTag("save_cloud_config_btn")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save Credentials", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Save confirmation badge
                    saveConfirmationMessage?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = NeonGreen.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen),
                            modifier = Modifier.fillMaxWidth().testTag("save_confirmation_badge")
                        ) {
                            Text(
                                text = "✓ $msg",
                                color = NeonGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // Handshake result output
                    testResult?.let { res ->
                        val isSuccess = res.startsWith("Handshake OK")
                        val statusColor = if (isSuccess) NeonGreen else NeonRed
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusColor.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, statusColor),
                            modifier = Modifier.fillMaxWidth().testTag("handshake_result_banner")
                        ) {
                            Text(
                                text = res,
                                color = statusColor,
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
                    Text("Offsite Backup Policies", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Auto-Upload Recorded Clips", color = Color.White, fontSize = 13.sp)
                            Text("Streams MP4 clips to cloud storage on completion", color = Color.Gray, fontSize = 11.sp)
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
                            Text("Auto-Upload Motion Intrusion Snapshots", color = Color.White, fontSize = 13.sp)
                            Text("Instantly syncs JPEG intrusion evidence to cloud", color = Color.Gray, fontSize = 11.sp)
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
                            Text("Pauses cloud uploads when operating on cellular data", color = Color.Gray, fontSize = 11.sp)
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
