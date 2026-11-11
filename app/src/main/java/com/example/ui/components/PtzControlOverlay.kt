package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceSurface

@Composable
fun PtzControlOverlay(
    modifier: Modifier = Modifier,
    zoomScale: Float,
    panOffsetX: Float,
    panOffsetY: Float,
    onZoomChange: (Float) -> Unit,
    onPanChange: (Float, Float) -> Unit,
    onReset: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .testTag("ptz_control_panel"),
        color = SurveillanceCardBg.copy(alpha = 0.92f),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PTZ Controls",
                    color = NeonCyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${String.format("%.1f", zoomScale)}x Zoom",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }

            // Directional D-Pad for Pan / Tilt
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(SurveillanceSurface),
                contentAlignment = Alignment.Center
            ) {
                // Up
                FilledIconButton(
                    onClick = { onPanChange(panOffsetX, panOffsetY - 25f) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp)
                        .size(38.dp)
                        .testTag("ptz_up_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SurveillanceCardBg,
                        contentColor = NeonCyan
                    )
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Tilt Up")
                }

                // Down
                FilledIconButton(
                    onClick = { onPanChange(panOffsetX, panOffsetY + 25f) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .size(38.dp)
                        .testTag("ptz_down_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SurveillanceCardBg,
                        contentColor = NeonCyan
                    )
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Tilt Down")
                }

                // Left
                FilledIconButton(
                    onClick = { onPanChange(panOffsetX - 25f, panOffsetY) },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        .size(38.dp)
                        .testTag("ptz_left_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SurveillanceCardBg,
                        contentColor = NeonCyan
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Pan Left")
                }

                // Right
                FilledIconButton(
                    onClick = { onPanChange(panOffsetX + 25f, panOffsetY) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                        .size(38.dp)
                        .testTag("ptz_right_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SurveillanceCardBg,
                        contentColor = NeonCyan
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Pan Right")
                }

                // Center Reset
                FilledIconButton(
                    onClick = onReset,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("ptz_reset_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = NeonCyan,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset PTZ", modifier = Modifier.size(20.dp))
                }
            }

            // Zoom Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = { onZoomChange((zoomScale - 0.5f).coerceAtLeast(1.0f)) },
                    modifier = Modifier.size(38.dp).testTag("ptz_zoom_out_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SurveillanceSurface,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom Out")
                }

                OutlinedButton(
                    onClick = { onZoomChange(1.0f) },
                    modifier = Modifier.testTag("ptz_zoom_1x")
                ) {
                    Text("1.0x", fontSize = 11.sp, color = NeonCyan)
                }

                OutlinedButton(
                    onClick = { onZoomChange(2.5f) },
                    modifier = Modifier.testTag("ptz_zoom_2x")
                ) {
                    Text("2.5x", fontSize = 11.sp, color = NeonCyan)
                }

                FilledIconButton(
                    onClick = { onZoomChange((zoomScale + 0.5f).coerceAtMost(5.0f)) },
                    modifier = Modifier.size(38.dp).testTag("ptz_zoom_in_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SurveillanceSurface,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom In")
                }
            }
        }
    }
}
