package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.R
import com.example.engine.EndpointKind
import com.example.engine.HubAccessScope
import com.example.engine.HubConnectionProfile
import com.example.engine.PasswordStrength
import com.example.engine.RemoteHubServer
import com.example.engine.RemoteHubState
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceSurface

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteHubCard(
    hubState: RemoteHubState,
    oneTimeGeneratedPassword: String?,
    oneTimeApiKey: String?,
    selfTestResult: String?,
    copyNotice: String?,
    onToggleHub: (Boolean) -> Unit,
    onRefreshEndpoints: () -> Unit,
    onRunSelfTest: () -> Unit,
    onCopyToClipboard: (label: String, value: String) -> Unit,
    onApplySecurityAndConnection: (
        port: Int,
        username: String,
        newPassword: String,
        allowRemoteControl: Boolean,
        accessScope: HubAccessScope,
        connectionProfile: HubConnectionProfile,
        externalUrl: String
    ) -> Unit,
    onToggleRemoteControl: (Boolean) -> Unit,
    onToggleApiKeyEnabled: (Boolean) -> Unit,
    onRegenerateApiKey: () -> Unit,
    onRevokeAllSessions: () -> Unit,
    onDismissOneTimeSecrets: () -> Unit,
    modifier: Modifier = Modifier
) {
    var hubPortText by remember(hubState.port) { mutableStateOf(hubState.port.toString()) }
    var hubUsernameText by remember(hubState.username) { mutableStateOf(hubState.username) }
    var hubPasswordText by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var selectedProfile by remember(hubState.connectionProfile) { mutableStateOf(hubState.connectionProfile) }
    var selectedScope by remember(hubState.accessScope) { mutableStateOf(hubState.accessScope) }
    var externalUrlText by remember(hubState.externalUrl) { mutableStateOf(hubState.externalUrl) }
    var showApiKeySection by remember(hubState.apiKeyEnabled, oneTimeApiKey) {
        mutableStateOf(hubState.apiKeyEnabled || !oneTimeApiKey.isNullOrBlank())
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("remote_hub_server_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Card Header + Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.hub_title),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.hub_subtitle),
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (hubState.isRunning) NeonGreen.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f)
                ) {
                    Text(
                        text = if (hubState.isRunning) {
                            stringResource(R.string.hub_status_online, hubState.port, hubState.activeConnections)
                        } else {
                            stringResource(R.string.hub_status_offline)
                        },
                        color = if (hubState.isRunning) NeonGreen else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Master Hub Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.hub_switch_title), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.hub_switch_sub), color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = hubState.isEnabled,
                    onCheckedChange = onToggleHub,
                    colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan),
                    modifier = Modifier.testTag("hub_server_switch")
                )
            }

            // Eco-Hub Power Telemetry + Refresh + Local Reachability Self-Test
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SurveillanceSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = null,
                            tint = if (hubState.isPluggedIn) NeonGreen else NeonAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                R.string.hub_power_telemetry,
                                hubState.batteryPercent,
                                if (hubState.isPluggedIn) {
                                    stringResource(R.string.hub_power_plugged)
                                } else {
                                    stringResource(R.string.hub_power_battery)
                                },
                                hubState.freeStorageMb
                            ),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = onRunSelfTest,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("hub_selftest_btn")
                        ) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeonCyan)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.hub_selftest_btn), fontSize = 10.sp, maxLines = 1, softWrap = false)
                        }
                        IconButton(
                            onClick = onRefreshEndpoints,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("hub_refresh_ips_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.hub_refresh_endpoints_desc),
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            selfTestResult?.let { msg ->
                val isOk = msg.contains("200 OK") || msg.contains("OK")
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = (if (isOk) NeonGreen else NeonAmber).copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, (if (isOk) NeonGreen else NeonAmber).copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().testTag("hub_selftest_banner")
                ) {
                    Text(
                        text = msg,
                        color = if (isOk) NeonGreen else NeonAmber,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Multi-Transport Connection Choices
            Text(
                text = stringResource(R.string.hub_connection_choices_title),
                color = NeonCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val profiles = listOf(
                    HubConnectionProfile.AUTO_BEST to stringResource(R.string.hub_profile_auto),
                    HubConnectionProfile.TAILSCALE to stringResource(R.string.hub_profile_tailscale),
                    HubConnectionProfile.WIREGUARD_VPN to stringResource(R.string.hub_profile_wireguard),
                    HubConnectionProfile.LOCAL_LAN to stringResource(R.string.hub_profile_lan),
                    HubConnectionProfile.HOTSPOT_TETHER to stringResource(R.string.hub_profile_hotspot),
                    HubConnectionProfile.REVERSE_PROXY_TUNNEL to stringResource(R.string.hub_profile_tunnel)
                )
                profiles.forEach { (profile, label) ->
                    FilterChip(
                        selected = selectedProfile == profile,
                        onClick = { selectedProfile = profile },
                        label = { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("hub_profile_chip_${profile.name}")
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurveillanceSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when (selectedProfile) {
                        HubConnectionProfile.AUTO_BEST -> stringResource(R.string.hub_profile_guide_auto)
                        HubConnectionProfile.TAILSCALE -> stringResource(R.string.hub_profile_guide_tailscale)
                        HubConnectionProfile.WIREGUARD_VPN -> stringResource(R.string.hub_profile_guide_wireguard)
                        HubConnectionProfile.LOCAL_LAN -> stringResource(R.string.hub_profile_guide_lan)
                        HubConnectionProfile.HOTSPOT_TETHER -> stringResource(R.string.hub_profile_guide_hotspot)
                        HubConnectionProfile.REVERSE_PROXY_TUNNEL -> stringResource(R.string.hub_profile_guide_tunnel)
                    },
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }

            if (selectedProfile == HubConnectionProfile.REVERSE_PROXY_TUNNEL || externalUrlText.isNotBlank()) {
                OutlinedTextField(
                    value = externalUrlText,
                    onValueChange = { externalUrlText = it.take(180) },
                    label = { Text(stringResource(R.string.hub_external_url_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    placeholder = { Text(stringResource(R.string.hub_external_url_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, tint = NeonCyan) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hub_external_url_input")
                )
            }

            // Network Access Scope Filter
            Text(
                text = stringResource(R.string.hub_access_scope_title),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    HubAccessScope.ALL_INTERFACES to stringResource(R.string.hub_scope_all),
                    HubAccessScope.VPN_ONLY to stringResource(R.string.hub_scope_vpn_only),
                    HubAccessScope.LAN_AND_VPN to stringResource(R.string.hub_scope_lan_vpn)
                ).forEach { (scope, label) ->
                    FilterChip(
                        selected = selectedScope == scope,
                        onClick = { selectedScope = scope },
                        label = { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("hub_scope_chip_${scope.name}")
                    )
                }
            }

            // Detected Endpoints List
            Text(
                text = stringResource(R.string.hub_endpoints_title),
                color = NeonCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            if (hubState.endpoints.isEmpty()) {
                Text(
                    text = stringResource(R.string.hub_no_ip_detected),
                    color = NeonAmber,
                    fontSize = 11.sp
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    hubState.endpoints.forEachIndexed { idx, ep ->
                        val badgeColor = when (ep.kind) {
                            EndpointKind.EXTERNAL_TUNNEL -> NeonCyan
                            EndpointKind.TAILSCALE -> NeonCyan
                            EndpointKind.WIREGUARD, EndpointKind.ZEROTIER, EndpointKind.VPN_TUNNEL -> Color(0xFF7B61FF)
                            EndpointKind.WIFI_LAN -> NeonGreen
                            EndpointKind.HOTSPOT_USB -> NeonAmber
                            EndpointKind.OTHER -> Color.LightGray
                        }
                        val kindLabel = when (ep.kind) {
                            EndpointKind.TAILSCALE -> stringResource(R.string.hub_kind_tailscale)
                            EndpointKind.WIREGUARD -> stringResource(R.string.hub_kind_wireguard)
                            EndpointKind.ZEROTIER -> stringResource(R.string.hub_kind_zerotier)
                            EndpointKind.VPN_TUNNEL -> stringResource(R.string.hub_kind_vpn)
                            EndpointKind.WIFI_LAN -> stringResource(R.string.hub_kind_lan)
                            EndpointKind.HOTSPOT_USB -> stringResource(R.string.hub_kind_hotspot)
                            EndpointKind.EXTERNAL_TUNNEL -> stringResource(R.string.hub_kind_external)
                            EndpointKind.OTHER -> stringResource(R.string.hub_kind_other)
                        }
                        val iconVec = when (ep.kind) {
                            EndpointKind.TAILSCALE, EndpointKind.WIREGUARD, EndpointKind.ZEROTIER, EndpointKind.VPN_TUNNEL -> Icons.Default.VpnLock
                            EndpointKind.WIFI_LAN -> Icons.Default.Wifi
                            EndpointKind.HOTSPOT_USB -> Icons.Default.Usb
                            EndpointKind.EXTERNAL_TUNNEL -> Icons.Default.Public
                            EndpointKind.OTHER -> Icons.Default.Dns
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = SurveillanceSurface,
                            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = iconVec,
                                        contentDescription = null,
                                        tint = badgeColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "$kindLabel (${ep.interfaceName})",
                                            color = badgeColor,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = ep.url,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = { onCopyToClipboard("OmniView Hub URL", ep.url) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("copy_hub_url_${ep.kind.name}_$idx")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.hub_copy_url), fontSize = 11.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // One-time Initial Password Reveal Banner (never saved in plaintext)
            oneTimeGeneratedPassword?.let { tempPass ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = NeonAmber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, NeonAmber),
                    modifier = Modifier.fillMaxWidth().testTag("hub_onetime_password_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.hub_onetime_password_title, tempPass),
                            color = NeonAmber,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onCopyToClipboard("OmniView Hub Password", tempPass) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.hub_onetime_password_copy), fontSize = 11.sp)
                            }
                            TextButton(onClick = onDismissOneTimeSecrets) {
                                Text(stringResource(R.string.hub_dismiss_secret_btn), color = Color.LightGray, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Secure Username + PBKDF2 Password Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.hub_security_title),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = NeonGreen.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = stringResource(R.string.hub_security_pbkdf2_badge),
                        color = NeonGreen,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = hubPortText,
                    onValueChange = { hubPortText = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.hub_port_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.85f).testTag("hub_port_input")
                )

                OutlinedTextField(
                    value = hubUsernameText,
                    onValueChange = { hubUsernameText = it.take(32) },
                    label = { Text(stringResource(R.string.hub_username_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier.weight(1.25f).testTag("hub_username_input")
                )
            }

            OutlinedTextField(
                value = hubPasswordText,
                onValueChange = { hubPasswordText = it.take(64) },
                label = { Text(stringResource(R.string.hub_password_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val generated = RemoteHubServer.generateStrongPassword(14)
                                hubPasswordText = generated
                                isPasswordVisible = true
                            },
                            modifier = Modifier.testTag("hub_generate_password_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoFixHigh,
                                contentDescription = stringResource(R.string.hub_generate_password_btn),
                                tint = NeonCyan
                            )
                        }
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (isPasswordVisible) {
                                    stringResource(R.string.cloud_hide_secret)
                                } else {
                                    stringResource(R.string.cloud_show_secret)
                                },
                                tint = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("hub_password_input")
            )

            // Password Strength Indicator
            if (hubPasswordText.isNotEmpty()) {
                val strength = RemoteHubServer.evaluatePasswordStrength(hubPasswordText)
                val (strengthText, strengthColor) = when (strength) {
                    PasswordStrength.WEAK -> stringResource(R.string.hub_password_strength_weak) to NeonRed
                    PasswordStrength.FAIR -> stringResource(R.string.hub_password_strength_fair) to NeonAmber
                    PasswordStrength.STRONG -> stringResource(R.string.hub_password_strength_strong) to NeonGreen
                }
                Text(
                    text = strengthText,
                    color = strengthColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("hub_password_strength_label")
                )
            } else {
                Text(
                    text = stringResource(R.string.hub_password_keep_hint),
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }

            // Apply Hub Security & Sign Out All Sessions
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = {
                        val portVal = hubPortText.toIntOrNull() ?: RemoteHubServer.DEFAULT_PORT
                        val safePort = RemoteHubServer.normalizePort(portVal)
                        hubPortText = safePort.toString()
                        onApplySecurityAndConnection(
                            safePort,
                            hubUsernameText,
                            hubPasswordText,
                            hubState.allowRemoteControl,
                            selectedScope,
                            selectedProfile,
                            externalUrlText
                        )
                        hubPasswordText = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("hub_apply_config_btn")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.hub_save_security_btn), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                OutlinedButton(
                    onClick = onRevokeAllSessions,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("hub_signout_all_btn")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeonAmber)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.hub_signout_all_btn), fontSize = 11.sp, maxLines = 1, softWrap = false)
                }
            }

            Text(
                text = stringResource(R.string.hub_sessions_active, hubState.activeSessionCount),
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp
            )

            // Remote PTZ & Recording Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.hub_remote_control_title), color = Color.White, fontSize = 13.sp)
                    Text(stringResource(R.string.hub_remote_control_sub), color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = hubState.allowRemoteControl,
                    onCheckedChange = onToggleRemoteControl,
                    colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan),
                    modifier = Modifier.testTag("hub_allow_control_switch")
                )
            }

            // Optional Automation API Key Section (collapsible)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SurveillanceSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(stringResource(R.string.hub_apikey_section_title), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.hub_apikey_enable_sub), color = Color.Gray, fontSize = 10.sp)
                            }
                        }
                        Switch(
                            checked = hubState.apiKeyEnabled,
                            onCheckedChange = {
                                showApiKeySection = true
                                onToggleApiKeyEnabled(it)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan),
                            modifier = Modifier.testTag("hub_apikey_switch")
                        )
                    }

                    if (showApiKeySection && hubState.apiKeyEnabled) {
                        OutlinedButton(
                            onClick = onRegenerateApiKey,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("hub_regen_apikey_btn")
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.hub_apikey_regen_btn), fontSize = 11.sp)
                        }
                    }

                    oneTimeApiKey?.let { keyVal ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = NeonCyan.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth().testTag("hub_onetime_apikey_banner")
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.hub_apikey_onetime_label, keyVal),
                                    color = NeonCyan,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { onCopyToClipboard("OmniView API Key", keyVal) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.hub_copy_token), fontSize = 11.sp)
                                    }
                                    TextButton(onClick = onDismissOneTimeSecrets) {
                                        Text(stringResource(R.string.hub_dismiss_secret_btn), color = Color.LightGray, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (hubState.lastRequestSummary.isNotBlank()) {
                Text(
                    text = stringResource(R.string.hub_last_client, hubState.lastRequestSummary),
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            copyNotice?.let { msg ->
                Text(text = msg, color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }

            hubState.lastError?.let { err ->
                Text(
                    text = stringResource(R.string.hub_error_prefix, err),
                    color = NeonRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
