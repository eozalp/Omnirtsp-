package com.example.service

import com.example.data.model.CameraEntity
import com.example.ui.components.normalizeRotationDegrees
import com.example.ui.viewmodel.CornerPosition
import com.example.ui.viewmodel.FloatingWindowState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state holder connecting CameraViewModel (in-app UI) and FloatingCameraOverlayService
 * (system-wide "show over other apps" windows), ensuring window sizes, positions, rotations, and
 * closed cameras remain synchronized even when OmniView is backgrounded behind YouTube or other apps.
 */
object FloatingOverlayStateHolder {

    private val _cameras = MutableStateFlow<List<CameraEntity>>(emptyList())
    val cameras: StateFlow<List<CameraEntity>> = _cameras.asStateFlow()

    private val _floatingWindows = MutableStateFlow<List<FloatingWindowState>>(emptyList())
    val floatingWindows: StateFlow<List<FloatingWindowState>> = _floatingWindows.asStateFlow()

    private val _liveRotations = MutableStateFlow<Map<String, Float>>(emptyMap())
    val liveRotations: StateFlow<Map<String, Float>> = _liveRotations.asStateFlow()

    private val _rotationEvents = MutableSharedFlow<Pair<String, Float>>(extraBufferCapacity = 64)
    val rotationEvents: SharedFlow<Pair<String, Float>> = _rotationEvents.asSharedFlow()

    private val _showTechnicalDetails = MutableStateFlow(false)
    val showTechnicalDetails: StateFlow<Boolean> = _showTechnicalDetails.asStateFlow()

    private val _isSystemOverlayActive = MutableStateFlow(false)
    val isSystemOverlayActive: StateFlow<Boolean> = _isSystemOverlayActive.asStateFlow()

    private var nextZIndex = 10f

    fun updateCameras(list: List<CameraEntity>) {
        val validIds = list.map { it.id }.toSet()
        val currentRotations = _liveRotations.value.toMutableMap()
        // Seed rotations from DB if not already in live map; prune deleted cameras
        currentRotations.keys.retainAll(validIds)
        list.forEach { cam ->
            if (!currentRotations.containsKey(cam.id)) {
                currentRotations[cam.id] = normalizeRotationDegrees(cam.rotationDegrees)
            }
        }
        _liveRotations.value = currentRotations

        _cameras.value = list.map { cam ->
            val rot = currentRotations[cam.id] ?: cam.rotationDegrees
            if (cam.rotationDegrees == rot) cam else cam.copy(rotationDegrees = rot)
        }
        _floatingWindows.value = _floatingWindows.value.filter { it.cameraId in validIds }
    }

    fun setTechnicalDetails(enabled: Boolean) {
        _showTechnicalDetails.value = enabled
    }

    fun setSystemOverlayActive(active: Boolean) {
        _isSystemOverlayActive.value = active
    }

    fun setRotation(cameraId: String, degrees: Float) {
        val normalized = normalizeRotationDegrees(degrees)
        _liveRotations.value = _liveRotations.value.toMutableMap().apply {
            this[cameraId] = normalized
        }
        _cameras.value = _cameras.value.map { cam ->
            if (cam.id == cameraId) cam.copy(rotationDegrees = normalized) else cam
        }
        _floatingWindows.value = _floatingWindows.value.map { win ->
            if (win.cameraId == cameraId) win.copy(rotationDegrees = normalized) else win
        }
        _rotationEvents.tryEmit(cameraId to normalized)
    }

    fun openFloatingCamera(cameraId: String, defaultWidthDp: Float = 190f) {
        val current = _floatingWindows.value
        if (current.any { it.cameraId == cameraId }) {
            bringToFront(cameraId)
            return
        }
        val preferredOrder = listOf(
            CornerPosition.TOP_RIGHT,
            CornerPosition.BOTTOM_RIGHT,
            CornerPosition.TOP_LEFT,
            CornerPosition.BOTTOM_LEFT
        )
        val usedCorners = current.mapNotNull { it.cornerSnap }.toSet()
        val chosenCorner = preferredOrder.firstOrNull { it !in usedCorners }
            ?: preferredOrder[current.size % preferredOrder.size]
        val initialRotation = _liveRotations.value[cameraId]
            ?: _cameras.value.find { it.id == cameraId }?.rotationDegrees
            ?: 0f

        _floatingWindows.value = current + FloatingWindowState(
            cameraId = cameraId,
            widthDp = defaultWidthDp.coerceIn(130f, 540f),
            cornerSnap = chosenCorner,
            zIndex = nextZIndex++,
            rotationDegrees = normalizeRotationDegrees(initialRotation)
        )
    }

    fun toggleFloatingCamera(cameraId: String, defaultWidthDp: Float = 190f) {
        val current = _floatingWindows.value
        if (current.any { it.cameraId == cameraId }) {
            closeFloatingCamera(cameraId)
        } else {
            openFloatingCamera(cameraId, defaultWidthDp)
        }
    }

    fun floatAllCameras(cameraIds: List<String>, defaultWidthDp: Float = 180f) {
        if (cameraIds.isEmpty()) return
        val cornerOrder = listOf(
            CornerPosition.TOP_LEFT,
            CornerPosition.TOP_RIGHT,
            CornerPosition.BOTTOM_LEFT,
            CornerPosition.BOTTOM_RIGHT
        )
        val existing = _floatingWindows.value
        val rotations = _liveRotations.value
        val cams = _cameras.value
        _floatingWindows.value = cameraIds.mapIndexed { index, id ->
            val rot = rotations[id] ?: cams.find { it.id == id }?.rotationDegrees ?: 0f
            existing.find { it.cameraId == id } ?: FloatingWindowState(
                cameraId = id,
                widthDp = defaultWidthDp.coerceIn(130f, 540f),
                cornerSnap = cornerOrder[index % cornerOrder.size],
                zIndex = nextZIndex++,
                rotationDegrees = normalizeRotationDegrees(rot)
            )
        }
    }

    fun closeFloatingCamera(cameraId: String) {
        _floatingWindows.value = _floatingWindows.value.filterNot { it.cameraId == cameraId }
    }

    fun closeAll() {
        _floatingWindows.value = emptyList()
    }

    fun bringToFront(cameraId: String) {
        _floatingWindows.value = _floatingWindows.value.map { win ->
            if (win.cameraId == cameraId) win.copy(zIndex = nextZIndex++) else win
        }
    }

    fun updatePosition(cameraId: String, offsetX: Float, offsetY: Float) {
        _floatingWindows.value = _floatingWindows.value.map { win ->
            if (win.cameraId == cameraId) {
                win.copy(offsetX = offsetX, offsetY = offsetY, cornerSnap = null)
            } else win
        }
    }

    fun snapToCorner(cameraId: String, corner: CornerPosition) {
        _floatingWindows.value = _floatingWindows.value.map { win ->
            if (win.cameraId == cameraId) {
                win.copy(
                    cornerSnap = corner,
                    offsetX = null,
                    offsetY = null,
                    zIndex = nextZIndex++
                )
            } else win
        }
    }

    fun resizeWindow(cameraId: String, widthDp: Float, offsetX: Float? = null, offsetY: Float? = null) {
        _floatingWindows.value = _floatingWindows.value.map { win ->
            if (win.cameraId == cameraId) {
                win.copy(
                    widthDp = widthDp.coerceIn(130f, 540f),
                    offsetX = offsetX ?: win.offsetX,
                    offsetY = offsetY ?: win.offsetY,
                    cornerSnap = if (offsetX != null || offsetY != null) null else win.cornerSnap
                )
            } else win
        }
    }
}
