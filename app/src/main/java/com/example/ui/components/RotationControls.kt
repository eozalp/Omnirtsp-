package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SurveillanceCardBg
import com.example.ui.theme.SurveillanceSurface
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

fun normalizeRotationDegrees(degrees: Float): Float {
    val mod = ((degrees % 360f) + 360f) % 360f
    return if (mod >= 359.95f || mod < 0.05f) 0f else mod
}

fun formatRotationLabel(degrees: Float): String {
    val norm = normalizeRotationDegrees(degrees)
    val roundedInt = norm.roundToInt()
    return if (kotlin.math.abs(norm - roundedInt) < 0.1f) {
        "${roundedInt % 360}°"
    } else {
        "${String.format("%.1f", norm)}°"
    }
}

fun parseResolutionAspectRatio(resolution: String, fallback: Float = 16f / 9f): Float {
    val parts = resolution.trim().lowercase().split("x")
    if (parts.size != 2) return fallback
    val w = parts[0].trim().toFloatOrNull() ?: return fallback
    val h = parts[1].trim().toFloatOrNull() ?: return fallback
    if (w <= 0f || h <= 0f) return fallback
    return (w / h).coerceIn(0.4f, 3.0f)
}

/**
 * Computes the natural container aspect ratio after rotating a source video frame:
 * e.g. 16:9 at 0°/180° becomes 9:16 at 90°/270° so camera cards and floating windows
 * adapt their frame shape instead of leaving black sidebars.
 */
fun computeEffectiveContainerAspectRatio(
    sourceAspect: Float = 16f / 9f,
    rotationDegrees: Float
): Float {
    val safeSrc = sourceAspect.coerceIn(0.4f, 3.0f)
    val rad = Math.toRadians(normalizeRotationDegrees(rotationDegrees).toDouble())
    val absCos = kotlin.math.abs(cos(rad)).toFloat()
    val absSin = kotlin.math.abs(sin(rad)).toFloat()
    val effW = safeSrc * absCos + absSin
    val effH = safeSrc * absSin + absCos
    return if (effH > 0.001f) (effW / effH).coerceIn(0.4f, 2.5f) else safeSrc
}

/**
 * Computes unrotated child frame dimensions (uW, uH) matching [sourceAspect] so that once
 * centered in ([viewportW], [viewportH]) and rotated by [rotationDegrees], the frame
 * completely covers the viewport with zero black bars.
 */
fun computeCoverFrameSizeForRotation(
    viewportW: Float,
    viewportH: Float,
    sourceAspect: Float = 16f / 9f,
    rotationDegrees: Float
): Pair<Float, Float> {
    if (viewportW <= 0f || viewportH <= 0f) return 0f to 0f
    val safeSrc = sourceAspect.coerceIn(0.4f, 3.0f)
    val rad = Math.toRadians(normalizeRotationDegrees(rotationDegrees).toDouble())
    val absCos = kotlin.math.abs(cos(rad)).toFloat()
    val absSin = kotlin.math.abs(sin(rad)).toFloat()
    val reqW = viewportW * absCos + viewportH * absSin
    val reqH = viewportW * absSin + viewportH * absCos
    val coverW = maxOf(reqW, safeSrc * reqH)
    val coverH = coverW / safeSrc
    return coverW to coverH
}

fun resolveFloatingWindowSizeDp(
    baseWidthDp: Float,
    effectiveAspect: Float,
    maxWidthDp: Float,
    maxHeightDp: Float,
    minWidthDp: Float = 96f
): Pair<Float, Float> {
    val safeAspect = effectiveAspect.coerceIn(0.4f, 2.5f)
    val safeBase = baseWidthDp.coerceIn(130f, 540f)
    val baseArea = safeBase * (safeBase * 9f / 16f)
    var targetW = kotlin.math.sqrt(baseArea * safeAspect)
    var targetH = targetW / safeAspect

    val safeMaxW = maxWidthDp.coerceAtLeast(minWidthDp)
    val safeMaxH = maxHeightDp.coerceAtLeast(minWidthDp)

    if (targetW < minWidthDp) {
        targetW = minWidthDp
        targetH = targetW / safeAspect
    }
    if (targetW > safeMaxW) {
        targetW = safeMaxW
        targetH = targetW / safeAspect
    }
    if (targetH > safeMaxH) {
        targetH = safeMaxH
        targetW = targetH * safeAspect
    }
    return targetW to targetH
}

fun baseWidthFromActualWidthDp(
    actualWidthDp: Float,
    effectiveAspect: Float
): Float {
    val safeAspect = effectiveAspect.coerceIn(0.4f, 2.5f)
    return (actualWidthDp * kotlin.math.sqrt((16f / 9f) / safeAspect)).coerceIn(130f, 540f)
}

/**
 * Interactive 360° rotary wheel for smooth free rotation with 15° ticks and cardinal markers.
 */
@Composable
fun CircularFreeRotationDial(
    degrees: Float,
    onDegreesChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    dialSize: Dp = 144.dp
) {
    val currentOnChange by rememberUpdatedState(onDegreesChange)
    var centerOffset by remember { mutableStateOf(Offset.Zero) }
    val normalized = normalizeRotationDegrees(degrees)

    fun angleFromOffset(pos: Offset): Float {
        val dx = pos.x - centerOffset.x
        val dy = pos.y - centerOffset.y
        val rawRad = atan2(dy.toDouble(), dx.toDouble())
        val degFromTop = (rawRad * 180.0 / PI).toFloat() + 90f
        return normalizeRotationDegrees(degFromTop)
    }

    Box(
        modifier = modifier
            .size(dialSize)
            .onSizeChanged { size: IntSize ->
                centerOffset = Offset(size.width / 2f, size.height / 2f)
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    currentOnChange(angleFromOffset(pos))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    currentOnChange(angleFromOffset(change.position))
                }
            }
            .testTag("free_rotation_dial"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(dialSize)) {
            val r = (size.minDimension / 2f) - 10.dp.toPx()
            val c = Offset(size.width / 2f, size.height / 2f)

            // Outer ring
            drawCircle(
                color = SurveillanceSurface,
                radius = r,
                center = c
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.2f),
                radius = r,
                center = c,
                style = Stroke(width = 2.dp.toPx())
            )

            // 15° tick marks
            for (deg in 0 until 360 step 15) {
                val rad = Math.toRadians((deg - 90).toDouble())
                val isCardinal = deg % 90 == 0
                val innerR = r - if (isCardinal) 12.dp.toPx() else 6.dp.toPx()
                val start = Offset(
                    c.x + (innerR * cos(rad)).toFloat(),
                    c.y + (innerR * sin(rad)).toFloat()
                )
                val end = Offset(
                    c.x + (r * cos(rad)).toFloat(),
                    c.y + (r * sin(rad)).toFloat()
                )
                drawLine(
                    color = if (isCardinal) NeonCyan else Color.White.copy(alpha = 0.45f),
                    start = start,
                    end = end,
                    strokeWidth = if (isCardinal) 2.5.dp.toPx() else 1.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Active angle arm + knob
            val activeRad = Math.toRadians((normalized - 90f).toDouble())
            val knobDist = r - 18.dp.toPx()
            val knobPos = Offset(
                c.x + (knobDist * cos(activeRad)).toFloat(),
                c.y + (knobDist * sin(activeRad)).toFloat()
            )
            drawLine(
                color = NeonCyan.copy(alpha = 0.7f),
                start = c,
                end = knobPos,
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = NeonCyan,
                radius = 9.dp.toPx(),
                center = knobPos
            )
            drawCircle(
                color = Color.Black,
                radius = 3.5.dp.toPx(),
                center = knobPos
            )
        }

        Surface(
            shape = CircleShape,
            color = SurveillanceCardBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))
        ) {
            Text(
                text = formatRotationLabel(normalized),
                color = NeonCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Full-featured video rotation panel supporting both by-degree steps/presets and continuous free rotation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoRotationPanel(
    cameraName: String,
    rotationDegrees: Float,
    onRotationChange: (Float) -> Unit,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val normalized = normalizeRotationDegrees(rotationDegrees)

    Surface(
        modifier = modifier.testTag("video_rotation_panel"),
        shape = RoundedCornerShape(16.dp),
        color = SurveillanceCardBg.copy(alpha = 0.96f),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.rotation_title),
                        color = NeonCyan,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = cameraName,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { onRotationChange(0f) },
                        modifier = Modifier.size(36.dp).testTag("rotation_reset_btn")
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.rotation_reset_zero), tint = NeonCyan)
                    }
                    if (onClose != null) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(36.dp).testTag("rotation_close_btn")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = Color.White)
                        }
                    }
                }
            }

            // Circular Free Rotation Dial
            CircularFreeRotationDial(
                degrees = normalized,
                onDegreesChange = onRotationChange,
                dialSize = 148.dp
            )

            // Free Rotation Linear Slider (0° to 360°)
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.rotation_free_label), color = Color.Gray, fontSize = 11.sp)
                    Text(
                        text = formatRotationLabel(normalized),
                        color = NeonCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = normalized,
                    onValueChange = { onRotationChange(normalizeRotationDegrees(it)) },
                    valueRange = 0f..359.9f,
                    colors = SliderDefaults.colors(
                        thumbColor = NeonCyan,
                        activeTrackColor = NeonCyan
                    ),
                    modifier = Modifier.testTag("free_rotation_slider")
                )
            }

            // Preset Degree Chips (0°, 90°, 180°, 270°)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(0, 90, 180, 270).forEach { preset ->
                    val selected = kotlin.math.abs(normalized - preset.toFloat()) < 0.5f ||
                        (preset == 0 && normalized > 359.5f)
                    FilterChip(
                        selected = selected,
                        onClick = { onRotationChange(preset.toFloat()) },
                        label = { Text("${preset}°", fontSize = 11.sp, maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("rotate_preset_${preset}")
                    )
                }
            }

            // By-Degree Fine Steppers (-15°, -1°, +1°, +15°)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DegreeStepButton("-15°") { onRotationChange(normalizeRotationDegrees(normalized - 15f)) }
                DegreeStepButton("-1°") { onRotationChange(normalizeRotationDegrees(normalized - 1f)) }
                DegreeStepButton("+1°") { onRotationChange(normalizeRotationDegrees(normalized + 1f)) }
                DegreeStepButton("+15°") { onRotationChange(normalizeRotationDegrees(normalized + 15f)) }
            }
        }
    }
}

@Composable
private fun DegreeStepButton(
    label: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 36.dp)
    ) {
        Text(label, fontSize = 11.sp, color = NeonCyan, maxLines = 1, softWrap = false)
    }
}

@Composable
fun VideoRotationDialog(
    cameraName: String,
    rotationDegrees: Float,
    onRotationChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        VideoRotationPanel(
            cameraName = cameraName,
            rotationDegrees = rotationDegrees,
            onRotationChange = onRotationChange,
            onClose = onDismiss,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Compact free + by-degree rotation overlay bar designed for floating windows
 * (both in-app and system-wide "show over other apps").
 */
@Composable
fun CompactFloatingRotationBar(
    rotationDegrees: Float,
    onRotationChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val normalized = normalizeRotationDegrees(rotationDegrees)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .border(1.dp, NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Header + Free rotation slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = formatRotationLabel(normalized),
                color = NeonCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false
            )

            Slider(
                value = normalized,
                onValueChange = { onRotationChange(normalizeRotationDegrees(it)) },
                valueRange = 0f..359.9f,
                colors = SliderDefaults.colors(
                    thumbColor = NeonCyan,
                    activeTrackColor = NeonCyan
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(22.dp)
            )

            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(NeonCyan)
                    .clickable { onDone() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.rotation_done), tint = Color.Black, modifier = Modifier.size(14.dp))
            }
        }

        // Scrollable degree step & 90° snap chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingDegChip("-90°") { onRotationChange(normalizeRotationDegrees(normalized - 90f)) }
            FloatingDegChip("-5°") { onRotationChange(normalizeRotationDegrees(normalized - 5f)) }
            FloatingDegChip("-1°") { onRotationChange(normalizeRotationDegrees(normalized - 1f)) }
            FloatingDegChip("+1°") { onRotationChange(normalizeRotationDegrees(normalized + 1f)) }
            FloatingDegChip("+5°") { onRotationChange(normalizeRotationDegrees(normalized + 5f)) }
            FloatingDegChip("+90°") { onRotationChange(normalizeRotationDegrees(normalized + 90f)) }
            FloatingDegChip("0°", highlight = normalized == 0f) { onRotationChange(0f) }
        }
    }
}

@Composable
private fun FloatingDegChip(
    label: String,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (highlight) NeonCyan else SurveillanceSurface)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (highlight) Color.Black else Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}
