package com.example.engine

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offscreen EGL14 + OpenGL ES 2.0 OES frame grabber.
 *
 * Binds a SurfaceTexture to an ExoPlayer video output surface when no visible UI TextureView
 * is attached (e.g., app backgrounded, screen off, or another tab active). Converts hardware
 * YUV decoder frames via GL_TEXTURE_EXTERNAL_OES + SurfaceTexture.getTransformMatrix() into clean,
 * upright ARGB_8888 Bitmaps without YUV stride corruption.
 */
class HeadlessGlFrameGrabber(
    private val cameraId: String,
    val width: Int,
    val height: Int
) {
    private val glThread = HandlerThread("GlFrameGrabber-$cameraId").apply { start() }
    private val glHandler = Handler(glThread.looper)

    private val released = AtomicBoolean(false)
    private val bitmapLock = Any()

    @Volatile
    private var latestBitmap: Bitmap? = null

    @Volatile
    private var lastCaptureAtMs: Long = 0L

    @Volatile
    private var lastConsumerRequestAtMs: Long = System.currentTimeMillis()

    @Volatile
    private var hasQueuedNewFrame: Boolean = false

    @Volatile
    private var isInitialized: Boolean = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTextureId: Int = 0
    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTexMatrixLoc: Int = -1
    private var uTextureLoc: Int = -1

    private var surfaceTexture: SurfaceTexture? = null
    @Volatile
    var surface: Surface? = null
        private set

    private val texMatrix = FloatArray(16)
    private val quadCoords: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            // x, y, u, v (triangle strip)
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
                )
            )
            position(0)
        }
    private var readBuffer: ByteBuffer? = null
    private var flippedRowBuffer: ByteArray? = null
    private var initError: Throwable? = null

    private val firstFrameLock = Object()
    @Volatile
    private var hasReceivedFirstFrame = false

    init {
        val initLatch = CountDownLatch(1)
        glHandler.post {
            try {
                initEglAndGlLocked()
                isInitialized = true
            } catch (t: Throwable) {
                initError = t
            } finally {
                initLatch.countDown()
            }
        }
        runCatching { initLatch.await(1500, TimeUnit.MILLISECONDS) }
    }

    val isReady: Boolean
        get() = !released.get() && isInitialized && surface?.isValid == true && initError == null

    /**
     * Returns an independent copy of the latest decoded RGBA frame scaled to [targetWidth] x [targetHeight],
     * waiting up to [waitIfEmptyMs] for the first decoded frame if none has arrived yet.
     * Caller owns the returned Bitmap and may safely recycle it.
     */
    fun captureBitmap(
        targetWidth: Int,
        targetHeight: Int,
        waitIfEmptyMs: Long = 0L
    ): Bitmap? {
        if (released.get() || !isReady) return null
        val now = System.currentTimeMillis()
        lastConsumerRequestAtMs = now

        if (!hasReceivedFirstFrame && waitIfEmptyMs > 0L) {
            synchronized(firstFrameLock) {
                val deadline = System.currentTimeMillis() + waitIfEmptyMs
                while (!hasReceivedFirstFrame && !released.get()) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) break
                    runCatching { firstFrameLock.wait(remaining) }
                }
            }
        }

        // If a fresh frame arrived since our last glReadPixels (e.g. consumer was idle), draw it now
        if (!released.get() && hasReceivedFirstFrame && (hasQueuedNewFrame || now - lastCaptureAtMs > 120L)) {
            val drawLatch = CountDownLatch(1)
            glHandler.post {
                try {
                    if (!released.get() && hasQueuedNewFrame && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                        surfaceTexture?.updateTexImage()
                        hasQueuedNewFrame = false
                        renderAndReadPixelsLocked()
                    }
                } catch (_: Throwable) {
                } finally {
                    drawLatch.countDown()
                }
            }
            runCatching { drawLatch.await(160, TimeUnit.MILLISECONDS) }
        }

        val snapshotCopy = synchronized(bitmapLock) {
            val cached = latestBitmap ?: return null
            if (cached.isRecycled) return null
            runCatching { cached.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        } ?: return null

        if (targetWidth <= 0 || targetHeight <= 0 ||
            (snapshotCopy.width == targetWidth && snapshotCopy.height == targetHeight)
        ) {
            return snapshotCopy
        }

        return try {
            val scaled = Bitmap.createScaledBitmap(snapshotCopy, targetWidth, targetHeight, true)
            if (scaled !== snapshotCopy && !snapshotCopy.isRecycled) {
                snapshotCopy.recycle()
            }
            scaled
        } catch (_: Throwable) {
            snapshotCopy
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        synchronized(firstFrameLock) {
            firstFrameLock.notifyAll()
        }
        val releaseLatch = CountDownLatch(1)
        glHandler.post {
            try {
                releaseGlResourcesLocked()
            } catch (_: Exception) {
            } finally {
                releaseLatch.countDown()
            }
        }
        runCatching { releaseLatch.await(800, TimeUnit.MILLISECONDS) }
        synchronized(bitmapLock) {
            latestBitmap?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
            latestBitmap = null
        }
        runCatching { glThread.quitSafely() }
    }

    private fun initEglAndGlLocked() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            "eglChooseConfig failed"
        }
        val eglConfig = configs[0]!!

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "uTexture")

        readBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        flippedRowBuffer = ByteArray(width * height * 4)

        val st = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({
                if (released.get()) return@setOnFrameAvailableListener
                onFrameAvailableOnGlThread()
            }, glHandler)
        }
        surfaceTexture = st
        surface = Surface(st)
    }

    private fun onFrameAvailableOnGlThread() {
        val st = surfaceTexture ?: return
        if (released.get() || eglDisplay == EGL14.EGL_NO_DISPLAY) return
        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            st.updateTexImage()
            val now = System.currentTimeMillis()
            val hasFirst = hasReceivedFirstFrame
            val consumerRecentlyActive = (now - lastConsumerRequestAtMs) <= CONSUMER_ACTIVE_WINDOW_MS
            val intervalElapsed = (now - lastCaptureAtMs) >= MIN_CAPTURE_INTERVAL_MS

            if (!hasFirst || (consumerRecentlyActive && intervalElapsed)) {
                renderAndReadPixelsLocked()
                hasQueuedNewFrame = false
                if (!hasFirst) {
                    hasReceivedFirstFrame = true
                    synchronized(firstFrameLock) {
                        firstFrameLock.notifyAll()
                    }
                }
            } else {
                hasQueuedNewFrame = true
            }
        } catch (_: Throwable) {
        }
    }

    private fun renderAndReadPixelsLocked() {
        val st = surfaceTexture ?: return
        val buf = readBuffer ?: return
        val dstBytes = flippedRowBuffer ?: return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        st.getTransformMatrix(texMatrix)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        quadCoords.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, quadCoords)
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        quadCoords.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, quadCoords)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        buf.clear()
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()

        // glReadPixels returns rows bottom-to-top; flip vertically for upright Bitmap
        val rowStride = width * 4
        var srcOffset = (height - 1) * rowStride
        var dstOffset = 0
        for (y in 0 until height) {
            buf.position(srcOffset)
            buf.get(dstBytes, dstOffset, rowStride)
            srcOffset -= rowStride
            dstOffset += rowStride
        }

        val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        frameBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(dstBytes))

        synchronized(bitmapLock) {
            val prev = latestBitmap
            latestBitmap = frameBitmap
            lastCaptureAtMs = System.currentTimeMillis()
            if (prev != null && prev !== frameBitmap && !prev.isRecycled) {
                prev.recycle()
            }
        }
    }

    private fun releaseGlResourcesLocked() {
        runCatching { surface?.release() }
        surface = null
        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
            if (programId != 0) {
                runCatching { GLES20.glDeleteProgram(programId) }
                programId = 0
            }
            if (oesTextureId != 0) {
                val tex = intArrayOf(oesTextureId)
                runCatching { GLES20.glDeleteTextures(1, tex, 0) }
                oesTextureId = 0
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            runCatching { EGL14.eglReleaseThread() }
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    companion object {
        private const val CONSUMER_ACTIVE_WINDOW_MS = 6_000L
        private const val MIN_CAPTURE_INTERVAL_MS = 85L

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val info = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("Shader compile failed: $info")
            }
            return shader
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vShader)
            GLES20.glAttachShader(program, fShader)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vShader)
            GLES20.glDeleteShader(fShader)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val info = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                error("Program link failed: $info")
            }
            return program
        }
    }
}
