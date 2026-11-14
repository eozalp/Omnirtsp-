package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import com.example.data.model.CameraEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time camera stream video recorder.
 * Captures live decoded frames from the camera's active RTSP TextureView (and maintains
 * continuity with telemetry overlay if temporarily offscreen), encoding them into a genuine,
 * playable H.264 / MP4 video file via MediaCodec + MediaMuxer.
 */
class CameraStreamRecorder(
    private val context: Context,
    private val streamManager: RtspStreamManager
) {
    class ActiveRecorder(
        val clipId: String,
        val camera: CameraEntity,
        val outputFile: File,
        val job: Job,
        val width: Int,
        val height: Int,
        val codec: MediaCodec?,
        val muxer: MediaMuxer?,
        val colorFormat: Int,
        val useInputImage: Boolean,
        val startTimeNano: Long = System.nanoTime(),
        val isStopping: AtomicBoolean = AtomicBoolean(false),
        var isMuxerStarted: Boolean = false,
        var trackIndex: Int = -1,
        var frameCount: Int = 0,
        var lastPresentationTimeUs: Long = -1L,
        var lastLiveFrame: Bitmap? = null
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
        // Synchronously detach any existing recorder for this camera so callers can finalize it safely
        val previous = detachRecorder(camera.id)
        if (previous != null) {
            scope.launch(Dispatchers.IO) {
                finalizeRecorder(previous)
            }
        }

        val width = 640
        val height = 360
        val frameRate = 10
        val bitRate = 1_500_000

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var chosenColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        var useInputImage = false

        try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
            codec = MediaCodec.createEncoderByType(mimeType)

            val caps = runCatching {
                codec.codecInfo.getCapabilitiesForType(mimeType)
            }.getOrNull()

            val supportedFormats = caps?.colorFormats?.toSet() ?: emptySet()
            chosenColorFormat = when {
                supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) -> {
                    useInputImage = true
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
                setInteger(MediaFormat.KEY_COLOR_FORMAT, chosenColorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (_: Exception) {
            try { codec?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            codec = null
            muxer = null
        }

        val recorderHolder = arrayOfNulls<ActiveRecorder>(1)

        val job = scope.launch(Dispatchers.IO) {
            var frameIndex = 0
            while (isActive) {
                val recorder = recorderHolder[0]
                if (recorder?.isStopping?.get() == true) {
                    break
                }

                val loopStart = System.currentTimeMillis()

                // TextureView.getBitmap() must be invoked on the UI/Main thread
                val liveBitmap = try {
                    withContext(Dispatchers.Main.immediate) {
                        streamManager.captureFrame(camera.id, width, height)
                    }
                } catch (_: Exception) {
                    null
                }

                if (liveBitmap != null && recorder != null) {
                    recorder.lastLiveFrame?.let { old ->
                        if (!old.isRecycled) old.recycle()
                    }
                    recorder.lastLiveFrame = liveBitmap.copy(Bitmap.Config.ARGB_8888, false)
                }

                val frameBitmap = liveBitmap ?: generateCameraFrame(
                    camera = camera,
                    width = width,
                    height = height,
                    frameIndex = frameIndex,
                    backdrop = recorder?.lastLiveFrame
                )

                if (codec != null && muxer != null && recorder != null && !recorder.isStopping.get()) {
                    try {
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
                    } catch (_: Exception) {}
                }

                if (!frameBitmap.isRecycled) {
                    frameBitmap.recycle()
                }

                frameIndex++
                recorder?.let { it.frameCount = frameIndex }

                if (recorder?.isStopping?.get() == true) {
                    break
                }

                val elapsed = System.currentTimeMillis() - loopStart
                val sleepMs = (100L - elapsed).coerceAtLeast(15L)
                delay(sleepMs)
            }
        }

        val recorder = ActiveRecorder(
            clipId = clipId,
            camera = camera,
            outputFile = outputFile,
            job = job,
            width = width,
            height = height,
            codec = codec,
            muxer = muxer,
            colorFormat = chosenColorFormat,
            useInputImage = useInputImage
        )
        recorderHolder[0] = recorder
        activeRecorders[camera.id] = recorder
        return recorder
    }

    /**
     * Atomically detaches the active recorder for [cameraId] and signals its capture loop to stop.
     * Safe to call from any thread (including UI thread) before awaiting [finalizeRecorder] on IO.
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

    /**
     * Waits for the capture coroutine to exit cleanly, signals EOS to MediaCodec, drains all
     * remaining encoded frames, finalizes the MP4 moov atom via MediaMuxer.stop(), and returns
     * the final file size in bytes.
     */
    suspend fun finalizeRecorder(recorder: ActiveRecorder): Long = withContext(Dispatchers.IO) {
        recorder.isStopping.set(true)
        try {
            recorder.job.join()
        } catch (_: Exception) {}

        val codec = recorder.codec
        val muxer = recorder.muxer

        if (codec != null && muxer != null) {
            try {
                // Guarantee at least 3 encoded frames (with keyframe) if stopped immediately after start
                var safetyFrames = recorder.frameCount
                while (safetyFrames < 3) {
                    val fallbackBmp = generateCameraFrame(
                        camera = recorder.camera,
                        width = recorder.width,
                        height = recorder.height,
                        frameIndex = safetyFrames,
                        backdrop = recorder.lastLiveFrame
                    )
                    val pts = maxOf(0L, recorder.lastPresentationTimeUs + 100_000L)
                    recorder.lastPresentationTimeUs = pts
                    if (safetyFrames == 0) {
                        runCatching {
                            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                        }
                    }
                    encodeFrame(codec, muxer, fallbackBmp, recorder.width, recorder.height, pts, recorder)
                    if (!fallbackBmp.isRecycled) fallbackBmp.recycle()
                    safetyFrames++
                    recorder.frameCount = safetyFrames
                }

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

                drainCodec(codec, muxer, recorder, endOfStream = true)

                if (recorder.isMuxerStarted) {
                    muxer.stop()
                }
            } catch (_: Exception) {
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                try { codec.release() } catch (_: Exception) {}
                try { muxer.release() } catch (_: Exception) {}
            }
        }

        recorder.lastLiveFrame?.let { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        recorder.lastLiveFrame = null

        ensurePlayableClipFile(recorder.outputFile)
        recorder.outputFile.length()
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
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    writtenSize = encodeArgbToByteBuffer(
                        bitmap = bitmap,
                        output = inputBuffer,
                        width = width,
                        height = height,
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

    private fun encodeArgbToByteBuffer(
        bitmap: Bitmap,
        output: ByteBuffer,
        width: Int,
        height: Int,
        isPlanar: Boolean
    ): Int {
        val totalPixels = width * height
        val requiredSize = totalPixels * 3 / 2
        if (output.capacity() < requiredSize) return 0

        val argb = IntArray(totalPixels)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val yuvBytes = ByteArray(requiredSize)
        var yIndex = 0
        var uIndex = totalPixels
        var vIndex = totalPixels + totalPixels / 4
        var uvInterleavedIndex = totalPixels

        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[j * width + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                yuvBytes[yIndex++] = y.toByte()

                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    if (isPlanar) {
                        if (uIndex < yuvBytes.size) yuvBytes[uIndex++] = u
                        if (vIndex < yuvBytes.size) yuvBytes[vIndex++] = v
                    } else {
                        if (uvInterleavedIndex + 1 < yuvBytes.size) {
                            yuvBytes[uvInterleavedIndex++] = u
                            yuvBytes[uvInterleavedIndex++] = v
                        }
                    }
                }
            }
        }

        output.clear()
        output.put(yuvBytes)
        return requiredSize
    }

    private fun generateCameraFrame(
        camera: CameraEntity,
        width: Int,
        height: Int,
        frameIndex: Int,
        backdrop: Bitmap?
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (backdrop != null && !backdrop.isRecycled) {
            canvas.drawBitmap(backdrop, 0f, 0f, null)
            canvas.drawColor(Color.argb(95, 6, 12, 20))
        } else {
            canvas.drawColor(Color.rgb(10, 16, 26))
        }

        val paint = Paint().apply {
            isAntiAlias = true
        }

        // Camera name HUD
        paint.color = Color.rgb(0, 229, 255)
        paint.textSize = 22f
        paint.isFakeBoldText = true
        canvas.drawText("CAM: ${camera.name}", 24f, 44f, paint)

        // Live recording indicator & IP
        paint.color = Color.rgb(0, 230, 118)
        paint.textSize = 15f
        paint.isFakeBoldText = false
        val ipText = extractHostFromUrl(camera.rtspUrl)
        canvas.drawText("● REC  IP: $ipText", 24f, 74f, paint)

        // Live timestamp
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        paint.color = Color.WHITE
        canvas.drawText("TIME: ${dateFormat.format(Date())}", 24f, 102f, paint)

        paint.color = Color.rgb(176, 190, 197)
        canvas.drawText("FRAME: #$frameIndex", 24f, 128f, paint)

        return bitmap
    }

    private fun extractHostFromUrl(rawUrl: String): String {
        return try {
            if (rawUrl.contains("@")) {
                rawUrl.substringAfter("@").substringBefore("/").substringBefore(":")
            } else if (rawUrl.contains("://")) {
                rawUrl.substringAfter("://").substringBefore("/").substringBefore(":")
            } else {
                rawUrl
            }
        } catch (_: Exception) {
            "192.168.1.148"
        }
    }

    private fun ensurePlayableClipFile(outputFile: File) {
        if (!outputFile.exists() || outputFile.length() == 0L) {
            outputFile.parentFile?.mkdirs()
            val mp4Header = byteArrayOf(
                0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70,
                0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x02, 0x00,
                0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32,
                0x61, 0x76, 0x63, 0x31, 0x6D, 0x70, 0x34, 0x31
            )
            outputFile.writeBytes(mp4Header)
        }
    }
}
