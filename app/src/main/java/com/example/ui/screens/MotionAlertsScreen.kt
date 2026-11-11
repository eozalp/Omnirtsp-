package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.data.model.MotionAlertEntity
import com.example.data.model.MotionSeverity
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceDarkBg
import com.example.ui.theme.SurveillanceSurface
import com.example.ui.viewmodel.CameraViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MotionAlertsScreen(
    viewModel: CameraViewModel,
    onViewCamera: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val alerts by viewModel.alerts.collectAsState()
    val cameras by viewModel.cameras.collectAsState()
    var selectedSeverityFilter by remember { mutableStateOf<MotionSeverity?>(null) }

    val filteredAlerts = alerts.filter { alert ->
        selectedSeverityFilter == null || alert.severity == selectedSeverityFilter
    }

    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy • HH:mm:ss", Locale.US) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurveillanceDarkBg)
            .testTag("motion_alerts_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Motion Detection Alerts",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = NeonCyan
                    )
                    Text(
                        text = "${alerts.size} Total Events • ${alerts.count { !it.isRead }} Unread",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { viewModel.markAllAlertsRead() },
                        modifier = Modifier.testTag("mark_all_read_btn")
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Mark All Read", tint = NeonCyan)
                    }
                    IconButton(
                        onClick = { viewModel.clearAllAlerts() },
                        modifier = Modifier.testTag("clear_all_alerts_btn")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Alerts", tint = Color.Gray)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedSeverityFilter == null,
                    onClick = { selectedSeverityFilter = null },
                    label = { Text("All (${alerts.size})") }
                )
                FilterChip(
                    selected = selectedSeverityFilter == MotionSeverity.HIGH,
                    onClick = { selectedSeverityFilter = MotionSeverity.HIGH },
                    label = { Text("High") }
                )
                FilterChip(
                    selected = selectedSeverityFilter == MotionSeverity.MEDIUM,
                    onClick = { selectedSeverityFilter = MotionSeverity.MEDIUM },
                    label = { Text("Medium") }
                )
                FilterChip(
                    selected = selectedSeverityFilter == MotionSeverity.LOW,
                    onClick = { selectedSeverityFilter = MotionSeverity.LOW },
                    label = { Text("Low") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Alerts List
            if (filteredAlerts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.MotionPhotosOn,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No motion events recorded",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(
                            onClick = {
                                cameras.firstOrNull()?.let { viewModel.triggerMotionSimulation(it) }
                            }
                        ) {
                            Text("Simulate Test Motion Event", color = NeonCyan)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredAlerts, key = { it.id }) { alert ->
                        val severityColor = when (alert.severity) {
                            MotionSeverity.HIGH -> NeonRed
                            MotionSeverity.MEDIUM -> NeonAmber
                            MotionSeverity.LOW -> NeonGreen
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("motion_alert_card_${alert.id}")
                                .clickable {
                                    viewModel.markAlertRead(alert.id)
                                    onViewCamera(alert.cameraId)
                                },
                            colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                            shape = RoundedCornerShape(12.dp),
                            border = if (!alert.isRead) androidx.compose.foundation.BorderStroke(1.5.dp, severityColor.copy(alpha = 0.7f)) else null
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(if (!alert.isRead) severityColor else Color.Gray)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = alert.cameraName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = Color.White
                                        )
                                    }

                                    // Severity Badge
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = severityColor.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = "${alert.severity.name} • ${alert.motionLevel}%",
                                            color = severityColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = alert.description,
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.85f)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Motion Level Progress Bar
                                LinearProgressIndicator(
                                    progress = { alert.motionLevel / 100f },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = severityColor,
                                    trackColor = SurveillanceSurface
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = dateFormatter.format(Date(alert.timestamp)),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Gray
                                    )
                                    Text(
                                        text = "Tap to view feed →",
                                        fontSize = 10.sp,
                                        color = NeonCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
