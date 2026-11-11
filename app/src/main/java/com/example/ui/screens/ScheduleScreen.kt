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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.data.model.RecordingMode
import com.example.data.model.RecordingScheduleEntity
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceDarkBg
import com.example.ui.theme.SurveillanceSurface
import com.example.ui.viewmodel.CameraViewModel
import java.util.UUID

@Composable
fun ScheduleScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val schedules by viewModel.schedules.collectAsState()
    val activeSchedules by viewModel.recordingSchedulerEngine.activeSchedulesState.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var scheduleToEdit by remember { mutableStateOf<RecordingScheduleEntity?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurveillanceDarkBg)
            .testTag("schedule_screen")
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
                        text = "Recording Schedules",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = NeonCyan
                    )
                    Text(
                        text = "Automated continuous & motion recording plans",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (activeSchedules.isNotEmpty()) Color(0xFF1E3A2B) else SurveillanceSurface
                ) {
                    Text(
                        text = if (activeSchedules.isNotEmpty()) "ACTIVE NOW (${activeSchedules.size})" else "STANDBY",
                        color = if (activeSchedules.isNotEmpty()) NeonGreen else Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Schedules List
            if (schedules.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No schedules created yet",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(schedules, key = { it.id }) { schedule ->
                        val isCurrentActive = activeSchedules.any { it.id == schedule.id }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("schedule_card_${schedule.id}"),
                            colors = CardDefaults.cardColors(containerColor = SurveillanceCardBg),
                            shape = RoundedCornerShape(14.dp),
                            border = if (isCurrentActive) androidx.compose.foundation.BorderStroke(1.5.dp, NeonGreen) else null
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = schedule.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "${String.format("%02d:%02d", schedule.startHour, schedule.startMinute)} — ${String.format("%02d:%02d", schedule.endHour, schedule.endMinute)}",
                                            fontSize = 13.sp,
                                            color = NeonCyan,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    Switch(
                                        checked = schedule.isEnabled,
                                        onCheckedChange = { isChecked ->
                                            viewModel.toggleSchedule(schedule.id, isChecked)
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Days pill and Mode Pill
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = SurveillanceSurface
                                        ) {
                                            Text(
                                                text = schedule.daysOfWeek,
                                                color = Color.White.copy(alpha = 0.8f),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (schedule.mode == RecordingMode.CONTINUOUS) Color(0xFF1E293B) else Color(0xFF332A15)
                                        ) {
                                            Text(
                                                text = schedule.mode.name.replace("_", " "),
                                                color = if (schedule.mode == RecordingMode.CONTINUOUS) NeonCyan else NeonAmber,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (schedule.cloudBackupOnFinish) {
                                            Icon(
                                                Icons.Default.CloudUpload,
                                                contentDescription = "Cloud Backup",
                                                tint = NeonCyan,
                                                modifier = Modifier.size(16.dp).padding(end = 4.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { viewModel.deleteSchedule(schedule.id) },
                                            modifier = Modifier.size(28.dp).testTag("delete_schedule_${schedule.id}")
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(16.dp)
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

        // Add Schedule FAB
        FloatingActionButton(
            onClick = {
                scheduleToEdit = null
                showAddDialog = true
            },
            containerColor = NeonCyan,
            contentColor = Color.Black,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("add_schedule_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Schedule")
        }

        if (showAddDialog) {
            AddScheduleDialog(
                schedule = scheduleToEdit,
                onDismiss = { showAddDialog = false },
                onSave = { schedule ->
                    viewModel.saveSchedule(schedule)
                    showAddDialog = false
                }
            )
        }
    }
}

@Composable
fun AddScheduleDialog(
    schedule: RecordingScheduleEntity?,
    onDismiss: () -> Unit,
    onSave: (RecordingScheduleEntity) -> Unit
) {
    var name by remember { mutableStateOf(schedule?.name ?: "Custom Schedule") }
    var startHour by remember { mutableIntStateOf(schedule?.startHour ?: 9) }
    var startMinute by remember { mutableIntStateOf(schedule?.startMinute ?: 0) }
    var endHour by remember { mutableIntStateOf(schedule?.endHour ?: 17) }
    var endMinute by remember { mutableIntStateOf(schedule?.endMinute ?: 0) }
    var mode by remember { mutableStateOf(schedule?.mode ?: RecordingMode.MOTION_ONLY) }
    var cloudBackup by remember { mutableStateOf(schedule?.cloudBackupOnFinish ?: true) }

    val daysList = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    var selectedDays by remember {
        mutableStateOf(schedule?.daysOfWeek?.split(",")?.toSet() ?: setOf("MON", "TUE", "WED", "THU", "FRI"))
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .testTag("add_schedule_dialog"),
            color = SurveillanceCardBg,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (schedule == null) "New Recording Schedule" else "Edit Schedule",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Schedule Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Days selector
                Text("Days of Week:", fontSize = 12.sp, color = Color.White)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    daysList.forEach { day ->
                        val isSelected = selectedDays.contains(day)
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    selectedDays = if (isSelected) {
                                        if (selectedDays.size > 1) selectedDays - day else selectedDays
                                    } else {
                                        selectedDays + day
                                    }
                                },
                            color = if (isSelected) NeonCyan else SurveillanceSurface
                        ) {
                            Text(
                                text = day.take(2),
                                color = if (isSelected) Color.Black else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Time window
                Text(
                    text = "Time Window: ${String.format("%02d:%02d", startHour, startMinute)} to ${String.format("%02d:%02d", endHour, endMinute)}",
                    fontSize = 13.sp,
                    color = NeonCyan,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = startHour == 0 && endHour == 24,
                        onClick = {
                            startHour = 0; startMinute = 0; endHour = 23; endMinute = 59
                        },
                        label = { Text("24/7 All Day", fontSize = 10.sp) }
                    )
                    FilterChip(
                        selected = startHour == 22 && endHour == 6,
                        onClick = {
                            startHour = 22; startMinute = 0; endHour = 6; endMinute = 0
                        },
                        label = { Text("Night Sentry", fontSize = 10.sp) }
                    )
                    FilterChip(
                        selected = startHour == 8 && endHour == 18,
                        onClick = {
                            startHour = 8; startMinute = 30; endHour = 18; endMinute = 0
                        },
                        label = { Text("Business Hours", fontSize = 10.sp) }
                    )
                }

                // Mode
                Text("Recording Mode:", fontSize = 12.sp, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == RecordingMode.MOTION_ONLY,
                        onClick = { mode = RecordingMode.MOTION_ONLY },
                        label = { Text("Motion Only") }
                    )
                    FilterChip(
                        selected = mode == RecordingMode.CONTINUOUS,
                        onClick = { mode = RecordingMode.CONTINUOUS },
                        label = { Text("Continuous 24/7") }
                    )
                }

                // Auto Cloud Upload
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-backup clips to Cloud", color = Color.White, fontSize = 13.sp)
                    Switch(
                        checked = cloudBackup,
                        onCheckedChange = { cloudBackup = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val newSchedule = RecordingScheduleEntity(
                                id = schedule?.id ?: UUID.randomUUID().toString(),
                                name = name.ifBlank { "Schedule" },
                                daysOfWeek = selectedDays.joinToString(","),
                                startHour = startHour,
                                startMinute = startMinute,
                                endHour = endHour,
                                endMinute = endMinute,
                                mode = mode,
                                isEnabled = true,
                                targetCameraIds = "ALL",
                                cloudBackupOnFinish = cloudBackup
                            )
                            onSave(newSchedule)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                        modifier = Modifier.testTag("save_schedule_button")
                    ) {
                        Text("Save Plan")
                    }
                }
            }
        }
    }
}
