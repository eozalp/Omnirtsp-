package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.NeonCyan

enum class ResizeCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

/**
 * Reusable Compose Modifier that enables corner-drag resizing (from all 4 corners)
 * and body-drag repositioning for camera player windows while preserving aspect ratio.
 *
 * When dragging a top or left corner, [onResizeDelta] supplies compensating X/Y anchor deltas
 * so the opposite corner stays pinned on screen.
 */
@Composable
fun Modifier.resizableCameraWindow(
    key: Any,
    cornerHitZone: Dp = 32.dp,
    aspectRatio: Float = 16f / 9f,
    onInteractionStart: () -> Unit = {},
    onDragMove: (deltaX: Float, deltaY: Float) -> Unit,
    onCornerResize: (corner: ResizeCorner, widthDeltaDp: Float, anchorShiftX: Float, anchorShiftY: Float) -> Unit
): Modifier {
    val density = LocalDensity.current
    val hitZonePx = with(density) { cornerHitZone.toPx() }
    val currentOnInteractionStart by rememberUpdatedState(onInteractionStart)
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnCornerResize by rememberUpdatedState(onCornerResize)

    return this.pointerInput(key, hitZonePx, aspectRatio) {
        var activeCorner: ResizeCorner? = null

        detectDragGestures(
            onDragStart = { startOffset ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val nearLeft = startOffset.x <= hitZonePx
                val nearRight = startOffset.x >= (w - hitZonePx)
                val nearTop = startOffset.y <= hitZonePx
                val nearBottom = startOffset.y >= (h - hitZonePx)

                activeCorner = when {
                    nearTop && nearLeft -> ResizeCorner.TOP_LEFT
                    nearTop && nearRight -> ResizeCorner.TOP_RIGHT
                    nearBottom && nearLeft -> ResizeCorner.BOTTOM_LEFT
                    nearBottom && nearRight -> ResizeCorner.BOTTOM_RIGHT
                    else -> null
                }
                currentOnInteractionStart()
            },
            onDragEnd = { activeCorner = null },
            onDragCancel = { activeCorner = null },
            onDrag = { change, dragAmount ->
                change.consume()
                val corner = activeCorner
                if (corner == null) {
                    currentOnDragMove(dragAmount.x, dragAmount.y)
                } else {
                    val widthDeltaPx = when (corner) {
                        ResizeCorner.BOTTOM_RIGHT -> dragAmount.x
                        ResizeCorner.TOP_RIGHT -> dragAmount.x
                        ResizeCorner.BOTTOM_LEFT -> -dragAmount.x
                        ResizeCorner.TOP_LEFT -> -dragAmount.x
                    }
                    val heightDeltaPx = widthDeltaPx / aspectRatio
                    val anchorShiftX = when (corner) {
                        ResizeCorner.BOTTOM_LEFT, ResizeCorner.TOP_LEFT -> -widthDeltaPx
                        ResizeCorner.BOTTOM_RIGHT, ResizeCorner.TOP_RIGHT -> 0f
                    }
                    val anchorShiftY = when (corner) {
                        ResizeCorner.TOP_LEFT, ResizeCorner.TOP_RIGHT -> -heightDeltaPx
                        ResizeCorner.BOTTOM_LEFT, ResizeCorner.BOTTOM_RIGHT -> 0f
                    }
                    val widthDeltaDp = widthDeltaPx / density.density
                    currentOnCornerResize(corner, widthDeltaDp, anchorShiftX, anchorShiftY)
                }
            }
        )
    }
}

/**
 * Draws crisp L-shaped resize grips in all four corners of a camera player window
 * so users immediately recognize that any corner can be dragged to resize.
 */
@Composable
fun CornerResizeGripsOverlay(
    isHighlighted: Boolean,
    modifier: Modifier = Modifier,
    color: Color = NeonCyan
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val gripLen = 12.dp.toPx().coerceAtMost(size.minDimension * 0.22f)
        val inset = 4.dp.toPx()
        val stroke = if (isHighlighted) 2.5.dp.toPx() else 1.8.dp.toPx()
        val drawColor = if (isHighlighted) color else Color.White.copy(alpha = 0.65f)

        val w = size.width
        val h = size.height

        // Top-Left corner bracket
        drawLine(drawColor, Offset(inset, inset), Offset(inset + gripLen, inset), stroke, StrokeCap.Round)
        drawLine(drawColor, Offset(inset, inset), Offset(inset, inset + gripLen), stroke, StrokeCap.Round)

        // Top-Right corner bracket
        drawLine(drawColor, Offset(w - inset - gripLen, inset), Offset(w - inset, inset), stroke, StrokeCap.Round)
        drawLine(drawColor, Offset(w - inset, inset), Offset(w - inset, inset + gripLen), stroke, StrokeCap.Round)

        // Bottom-Left corner bracket
        drawLine(drawColor, Offset(inset, h - inset), Offset(inset + gripLen, h - inset), stroke, StrokeCap.Round)
        drawLine(drawColor, Offset(inset, h - inset - gripLen), Offset(inset, h - inset), stroke, StrokeCap.Round)

        // Bottom-Right corner bracket
        drawLine(drawColor, Offset(w - inset - gripLen, h - inset), Offset(w - inset, h - inset), stroke, StrokeCap.Round)
        drawLine(drawColor, Offset(w - inset, h - inset - gripLen), Offset(w - inset, h - inset), stroke, StrokeCap.Round)
    }
}
