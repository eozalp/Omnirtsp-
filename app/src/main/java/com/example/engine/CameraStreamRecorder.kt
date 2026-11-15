package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.example.data.model.CameraEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time camera stream video recorder.
 * Captures live decoded frames from the camera's active RTSP TextureView or HeadlessGlFrameGrabber
 * and encodes them into a genuine, playable H.264 / MP4 video file via MediaCodec + MediaMuxer.
 *
 * Uses an EGL14 + OpenGL ES 2.0 WindowSurface attached to MediaCodec's hardware input Surface
 * (with stride-aware YUV420 fallback) on a dedicated single-thread dispatcher per recording so
 * encoded MP4s never suffer CPU Surface.lockCanvas() stride/zebra corruption or UI thread stalls.
 */
class CameraStreamRecorder(
    private val context: Context,
    private val streamManager: RtspStreamManager
) {
    class ActiveRecorder(
        val clipId: String,
        val camera: CameraEntity,
        val outputFile: File,
        val width: Int,
        val height: Int,
        val encoderDispatcher: ExecutorCoroutineDispatcher,
        var job: Job? = null,
        var codec: MediaCodec? = null,
        var muxer: MediaMuxer? = null,
        var colorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        var useInputImage: Boolean = false,
        var inputSurface: Surface? = null,
        var glInputSurface: EncoderGlInputSurface? = null,
        var inputStride: Int = width,
        var inputSliceHeight: Int = height,
        val startTimeNano: Long = System.nanoTime(),
        val isStopping: AtomicBoolean = AtomicBoolean(false),
        var isMuxerStarted: Boolean = false,
        var trackIndex: Int = -1,
        var frameCount: Int = 0,
        var lastPresentationTimeUs: Long = -1L,
        var lastLiveFrame: Bitmap? = null,
        var initFailed: Boolean = false
    )

    private val activeRecorders = ConcurrentHashMap<String, ActiveRecorder>()

    fun isRecording(cameraId: String): Boolean {
        return activeRecorders.containsKey(cameraId)
    }

    fun startRecording(
        clipId: String,
        camera: CameraEntity,
        outputFile: File,
        scope: CoroutineScope
    ): ActiveRecorder {
        val previous = detachRecorder(camera.id)
        if (previous != null) {
            scope.launch {
                runCatching { finalizeRecorder(previous) }
            }
        }

        val (width, height) = streamManager.getAspectPreservingTargetSize(
            cameraId = camera.id,
            maxSide = 640,
            alignTo = 16
        )
        val singleThreadDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "OmniViewRec-${clipId.take(6)}").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        val recorder = ActiveRecorder(
            clipId = clipId,
            camera = camera,
            outputFile = outputFile,
            width = width,
            height = height,
            encoderDispatcher = singleThreadDispatcher
        )

        activeRecorders[camera.id] = recorder

        val job = scope.launch(singleThreadDispatcher) {
            initializeEncoderOnThread(recorder)

            var frameIndex = 0
            while (isActive && !recorder.isStopping.get()) {
                val loopStart = System.currentTimeMillis()
                var frameBitmap: Bitmap? = null
                try {
                    val rawLiveBitmap = try {
                        streamManager.captureFrameForBackgroundWorker(
                            camera = camera,
                            maxSide = maxOf(width, height),
                            alignTo = 16,
                            waitHeadlessFirstFrameMs = if (frameIndex == 0) 280L else 0L
                        )
                    } catch (_: Throwable) {
                        null
                    }

                    val liveBitmap = if (rawLiveBitmap != null && !rawLiveBitmap.isRecycled) {
                        if (rawLiveBitmap.width == width && rawLiveBitmap.height == height &&
                            rawLiveBitmap.config == Bitmap.Config.ARGB_8888
                        ) {
                            rawLiveBitmap
                        } else {
                            try {
                                val scaled = Bitmap.createScaledBitmap(rawLiveBitmap, width, height, true)
                                val argb = if (scaled.config == Bitmap.Config.ARGB_8888) {
                                    scaled
                                } else {
                                    scaled.copy(Bitmap.Config.ARGB_8888, false).also {
                                        if (!scaled.isRecycled) scaled.recycle()
                                    }
                                }
                                if (rawLiveBitmap !== argb && !rawLiveBitmap.isRecycled) {
                                    rawLiveBitmap.recycle()
                                }
                                argb
                            } catch (_: Throwable) {
                                rawLiveBitmap
                            }
                        }
                    } else {
                        null
                    }

                    if (liveBitmap != null && !liveBitmap.isRecycled) {
                        val copyForCache = runCatching {
                            liveBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        if (copyForCache != null) {
                            recorder.lastLiveFrame?.let { old ->
                                if (!old.isRecycled) old.recycle()
                            }
                            recorder.lastLiveFrame = copyForCache
                        }
                    }

                    val cachedFrame = recorder.lastLiveFrame
                    frameBitmap = when {
                        liveBitmap != null && !liveBitmap.isRecycled -> liveBitmap
                        recorder.frameCount > 0 && cachedFrame != null && !cachedFrame.isRecycled ->
                            runCatching { cachedFrame.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                        else -> null
                    }

                    val codec = recorder.codec
                    val muxer = recorder.muxer
                    if (frameBitmap != null && !recorder.initFailed && codec != null && muxer != null && !recorder.isStopping.get()) {
                        if (frameIndex == 0) {
                            runCatching {
                                val syncBundle = Bundle().apply {
                                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                }
                                codec.setParameters(syncBundle)
                            }
                        }
                        val rawPtsUs = (System.nanoTime() - recorder.startTimeNano) / 1000L
                        val ptsUs = maxOf(rawPtsUs, recorder.lastPresentationTimeUs + 33_333L)
                        recorder.lastPresentationTimeUs = ptsUs

                        encodeFrame(
                            codec = codec,
                            muxer = muxer,
                            bitmap = frameBitmap,
                            width = width,
                            height = height,
                            presentationTimeUs = ptsUs,
                            recorder = recorder
                        )
                        frameIndex++
                        recorder.frameCount = frameIndex
                    }
                } catch (t: Throwable) {
                    Log.w("CameraStreamRecorder", "Record loop frame error for ${camera.id}: ${t.message}")
                } finally {
                    frameBitmap?.let { bmp ->
                        if (!bmp.isRecycled && bmp !== recorder.lastLiveFrame) {
                            bmp.recycle()
                        }
                    }
                }

                if (recorder.isStopping.get()) break

                val elapsed = System.currentTimeMillis() - loopStart
                val sleepMs = (100L - elapsed).coerceAtLeast(15L)
                delay(sleepMs)
            }
        }

        recorder.job = job
        return recorder
    }

    private fun initializeEncoderOnThread(recorder: ActiveRecorder) {
        val width = recorder.width
        val height = recorder.height
        val frameRate = 10
        val bitRate = 1_500_000

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null
        var glSurface: EncoderGlInputSurface? = null

        try {
            recorder.outputFile.parentFile?.mkdirs()
            if (recorder.outputFile.exists()) {
                recorder.outputFile.delete()
            }

            muxer = MediaMuxer(recorder.outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
            codec = MediaCodec.createEncoderByType(mimeType)

            val caps = runCatching {
                codec.codecInfo.getCapabilitiesForType(mimeType)
            }.getOrNull()
            val supportedFormats = caps?.colorFormats?.toSet() ?: emptySet()
            var surfaceConfigured = false

            if (supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) {
                try {
                    val surfaceFormat = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                    codec.configure(surfaceFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    inputSurface = codec.createInputSurface()
                    codec.start()
                    glSurface = EncoderGlInputSurface(inputSurface, width, height)
                    recorder.colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    surfaceConfigured = true
                } catch (e: Throwable) {
                    Log.w("CameraStreamRecorder", "GL Surface encoder init failed, falling back to YUV: ${e.message}")
                    runCatching { glSurface?.release() }
                    glSurface = null
                    runCatching { inputSurface?.release() }
                    inputSurface = null
                    runCatching { codec?.reset() }
                    if (codec != null) {
                        runCatching { codec.release() }
                        codec = runCatching { MediaCodec.createEncoderByType(mimeType) }.getOrNull()
                    }
                }
            }

            if (!surfaceConfigured && codec != null) {
                var useImg = false
                val yuvFormat = when {
                    supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) -> {
                        useImg = true
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    }
                    supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) -> {
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    }
                    supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) -> {
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    }
                    else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                }

                val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, yuvFormat)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()

                val inputFmt = runCatching { codec.inputFormat }.getOrNull()
                recorder.inputStride = runCatching {
                    inputFmt?.getInteger(MediaFormat.KEY_STRIDE)
                }.getOrNull()?.takeIf { it >= width } ?: width
                recorder.inputSliceHeight = runCatching {
                    inputFmt?.getInteger(MediaFormat.KEY_SLICE_HEIGHT)
                }.getOrNull()?.takeIf { it >= height } ?: height
                recorder.colorFormat = yuvFormat
                recorder.useInputImage = useImg
            }

            recorder.codec = codec
            recorder.muxer = muxer
            recorder.inputSurface = inputSurface
            recorder.glInputSurface = glSurface
        } catch (t: Throwable) {
            Log.e("CameraStreamRecorder", "Encoder init completely failed for ${recorder.camera.id}: ${t.message}")
            recorder.initFailed = true
            runCatching { glSurface?.release() }
            runCatching { inputSurface?.release() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
            recorder.codec = null
            recorder.muxer = null
            recorder.inputSurface = null
            recorder.glInputSurface = null
        }
    }

    /**
     * Atomically detaches the active recorder for [cameraId] and signals its capture loop to stop.
     */
    fun detachRecorder(cameraId: String, expectedClipId: String? = null): ActiveRecorder? {
        val current = activeRecorders[cameraId] ?: return null
        if (expectedClipId != null && current.clipId != expectedClipId) {
            return null
        }
        val removed = if (activeRecorders.remove(cameraId, current)) current else null
        removed?.isStopping?.set(true)
        return removed
    }

    fun detachAllRecorders(): List<ActiveRecorder> {
        val keys = activeRecorders.keys.toList()
        return keys.mapNotNull { detachRecorder(it) }
    }

    /**
     * Waits for the capture coroutine to exit cleanly, signals EOS to MediaCodec, drains all
     * remaining encoded frames, finalizes the MP4 moov atom via MediaMuxer.stop(), and returns
     * the final file size in bytes. Runs MediaCodec/EGL teardown on the recorder's own thread.
     */
    suspend fun finalizeRecorder(recorder: ActiveRecorder): Long {
        recorder.isStopping.set(true)
        try {
            recorder.job?.join()
        } catch (_: Throwable) {}

        return try {
            withContext(recorder.encoderDispatcher) {
                val codec = recorder.codec
                val muxer = recorder.muxer

                if (!recorder.initFailed && codec != null && muxer != null) {
                    try {
                        val lastReal = recorder.lastLiveFrame
                        if (recorder.frameCount > 0 && lastReal != null && !lastReal.isRecycled) {
                            var safetyFrames = recorder.frameCount
                            while (safetyFrames < 3) {
                                val pts = maxOf(0L, recorder.lastPresentationTimeUs + 100_000L)
                                recorder.lastPresentationTimeUs = pts
                                encodeFrame(codec, muxer, lastReal, recorder.width, recorder.height, pts, recorder)
                                safetyFrames++
                                recorder.frameCount = safetyFrames
                            }
                        }

                        if (recorder.frameCount > 0) {
                            if (recorder.glInputSurface != null && recorder.inputSurface != null) {
                                codec.signalEndOfInputStream()
                            } else {
                                val inputIndex = codec.dequeueInputBuffer(20_000)
                                if (inputIndex >= 0) {
                                    val eosPts = (recorder.lastPresentationTimeUs + 100_000L).coerceAtLeast(0L)
                                    codec.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        eosPts,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                }
                            }
                            drainCodec(codec, muxer, recorder, endOfStream = true)
                        }

                        if (recorder.isMuxerStarted) {
                            muxer.stop()
                        }
                    } catch (e: Throwable) {
                        Log.w("CameraStreamRecorder", "finalizeRecorder error: ${e.message}")
                    } finally {
                        runCatching { codec.stop() }
                        runCatching { codec.release() }
                        runCatching { recorder.glInputSurface?.release() }
                        runCatching { recorder.inputSurface?.release() }
                        runCatching { muxer.release() }
                        recorder.codec = null
                        recorder.muxer = null
                        recorder.glInputSurface = null
                        recorder.inputSurface = null
                    }
                }

                recorder.lastLiveFrame?.let { bmp ->
                    if (!bmp.isRecycled) bmp.recycle()
                }
                recorder.lastLiveFrame = null

                if (recorder.frameCount > 0 &&
                    recorder.isMuxerStarted &&
                    StorageTimelineEngine.isPlayableClipFile(recorder.outputFile)
                ) {
                    recorder.outputFile.length()
                } else {
                    runCatching {
                        if (recorder.outputFile.exists()) recorder.outputFile.delete()
                    }
                    0L
                }
            }
        } finally {
            runCatching { recorder.encoderDispatcher.close() }
        }
    }

    suspend fun stopRecording(cameraId: String): Long {
        val recorder = detachRecorder(cameraId) ?: return 0L
        return finalizeRecorder(recorder)
    }

    private fun encodeFrame(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bitmap: Bitmap,
        width: Int,
        height: Int,
        presentationTimeUs: Long,
        recorder: ActiveRecorder
    ) {
        val glSurface = recorder.glInputSurface
        if (glSurface != null && recorder.inputSurface != null) {
            glSurface.drawBitmap(bitmap, presentationTimeUs)
            drainCodec(codec, muxer, recorder, endOfStream = false)
            return
        }

        val inputIndex = codec.dequeueInputBuffer(15_000)
        if (inputIndex >= 0) {
            var writtenSize = 0
            if (recorder.useInputImage) {
                val image = runCatching { codec.getInputImage(inputIndex) }.getOrNull()
                if (image != null) {
                    encodeArgbToImage(bitmap, image, width, height)
                    writtenSize = width * height * 3 / 2
                }
            }
            if (writtenSize == 0) {
                val inputBuffer = runCatching { codec.getInputBuffer(inputIndex) }.getOrNull()
                if (inputBuffer != null) {
                    val argb = IntArray(width * height)
                    bitmap.getPixels(argb, 0, width, 0, 0, width, height)
                    writtenSize = writeArgbToYuv420Buffer(
                        argb = argb,
                        width = width,
                        height = height,
                        stride = recorder.inputStride,
                        sliceHeight = recorder.inputSliceHeight,
                        output = inputBuffer,
                        isPlanar = recorder.colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    )
                }
            }
            if (writtenSize > 0) {
                codec.queueInputBuffer(inputIndex, 0, writtenSize, presentationTimeUs, 0)
            } else {
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            }
        }

        drainCodec(codec, muxer, recorder, endOfStream = false)
    }

    private fun drainCodec(
        codec: MediaCodec,
        muxer: MediaMuxer,
        recorder: ActiveRecorder,
        endOfStream: Boolean
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var eosEmptyTries = 0

        while (true) {
            val timeoutUs = if (endOfStream) 10_000L else 0L
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!recorder.isMuxerStarted) {
                        val newFormat = codec.outputFormat
                        recorder.trackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        recorder.isMuxerStarted = true
                    }
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++eosEmptyTries >= 80) {
                        break
                    }
                }
                outIndex >= 0 -> {
                    eosEmptyTries = 0
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && recorder.isMuxerStarted && recorder.trackIndex >= 0) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(recorder.trackIndex, outBuffer, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
                else -> break
            }
        }
    }

    private fun encodeArgbToImage(bitmap: Bitmap, image: Image, width: Int, height: Int) {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        for (j in 0 until height) {
            val yRowOffset = j * yRowStride
            val uvRow = j shr 1
            val uRowOffset = uvRow * uRowStride
            val vRowOffset = uvRow * vRowStride

            for (i in 0 until width) {
                val c = argb[j * width + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                yBuf.put(yRowOffset + i * yPixelStride, y.toByte())

                if ((j and 1) == 0 && (i and 1) == 0) {
                    val uvCol = i shr 1
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    uBuf.put(uRowOffset + uvCol * uPixelStride, u.toByte())
                    vBuf.put(vRowOffset + uvCol * vPixelStride, v.toByte())
                }
            }
        }
    }


    /**
     * Dedicated OpenGL ES 2.0 + EGL14 renderer bound to MediaCodec's hardware input Surface.
     * Avoids CPU Surface.lockCanvas() stride/format mismatches that produce striped ("zebra") MP4s.
     */
    class EncoderGlInputSurface(
        surface: Surface,
        private val width: Int,
        private val height: Int
    ) {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var programId: Int = 0
        private var textureId: Int = 0
        private var aPositionLoc: Int = -1
        private var aTexCoordLoc: Int = -1
        private var uTextureLoc: Int = -1

        // Quad with y=+1 (top of video) -> v=0 (top row of Bitmap uploaded by GLUtils.texImage2D)
        private val quadCoords: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(
                    floatArrayOf(
                        -1f, -1f, 0f, 1f,
                        1f, -1f, 1f, 1f,
                        -1f, 1f, 0f, 0f,
                        1f, 1f, 1f, 0f
                    )
                )
                position(0)
            }

        init {
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
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(
                EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                    numConfigs[0] > 0
            ) { "eglChooseConfig failed" }

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0]!!, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0]!!, surface, surfaceAttribs, 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            textureId = tex[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            programId = createGlProgram(VS_SOURCE, FS_SOURCE)
            aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
            uTextureLoc = GLES20.glGetUniformLocation(programId, "uTexture")
        }

        fun drawBitmap(bitmap: Bitmap, presentationTimeUs: Long) {
            if (bitmap.isRecycled || eglDisplay == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(programId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glUniform1i(uTextureLoc, 0)

            quadCoords.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, quadCoords)
            GLES20.glEnableVertexAttribArray(aPositionLoc)

            quadCoords.position(2)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, quadCoords)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeUs * 1000L)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        fun release() {
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
                if (textureId != 0) {
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
                    textureId = 0
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                runCatching { EGL14.eglTerminate(eglDisplay) }
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VS_SOURCE = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FS_SOURCE = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val msg = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("Shader compile failed: $msg")
            }
            return shader
        }

        private fun createGlProgram(vs: String, fs: String): Int {
            val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
            val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, v)
            GLES20.glAttachShader(prog, f)
            GLES20.glLinkProgram(prog)
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val msg = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                error("Program link failed: $msg")
            }
            return prog
        }

        /**
         * Writes ARGB pixels into an encoder YUV420 ByteBuffer respecting hardware encoder
         * [stride] and [sliceHeight] padding so frames never exhibit diagonal zebra stripes.
         */
        fun writeArgbToYuv420Buffer(
            argb: IntArray,
            width: Int,
            height: Int,
            stride: Int,
            sliceHeight: Int,
            output: ByteBuffer,
            isPlanar: Boolean
        ): Int {
            val safeStride = stride.coerceAtLeast(width)
            val safeSliceHeight = sliceHeight.coerceAtLeast(height)
            val yPlaneSize = safeStride * safeSliceHeight
            val chromaStride = if (isPlanar) safeStride / 2 else safeStride
            val chromaRows = safeSliceHeight / 2
            val uvPlaneSize = if (isPlanar) {
                2 * chromaStride * chromaRows
            } else {
                safeStride * chromaRows
            }
            val totalRequired = yPlaneSize + uvPlaneSize
            if (output.capacity() < totalRequired || argb.size < width * height) {
                return 0
            }

            val yuvBytes = ByteArray(totalRequired)
            val uBase = yPlaneSize
            val vBase = yPlaneSize + chromaStride * chromaRows

            for (j in 0 until height) {
                val yRowOffset = j * safeStride
                val uvRow = j shr 1
                for (i in 0 until width) {
                    val c = argb[j * width + i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF

                    val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                    yuvBytes[yRowOffset + i] = y.toByte()

                    if ((j and 1) == 0 && (i and 1) == 0) {
                        val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                        val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                        val uvCol = i shr 1
                        if (isPlanar) {
                            val uIdx = uBase + uvRow * chromaStride + uvCol
                            val vIdx = vBase + uvRow * chromaStride + uvCol
                            if (uIdx < yuvBytes.size) yuvBytes[uIdx] = u
                            if (vIdx < yuvBytes.size) yuvBytes[vIdx] = v
                        } else {
                            val uvIdx = uBase + uvRow * safeStride + i
                            if (uvIdx + 1 < yuvBytes.size) {
                                yuvBytes[uvIdx] = u
                                yuvBytes[uvIdx + 1] = v
                            }
                        }
                    }
                }
            }

            output.clear()
            output.put(yuvBytes)
            output.flip()
            return totalRequired
        }
    }
}
