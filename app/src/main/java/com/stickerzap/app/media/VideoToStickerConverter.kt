package com.stickerzap.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class StickerEncodeException(val code: String) : Exception(code)

data class ConvertResult(
    val frameCount: Int,
    val fps: Int,
    val quality: Int,
    val actualStartMs: Long,
    val actualEndMs: Long,
    val shortened: Boolean,
    val hasAudio: Boolean,
    val animationDurationMs: Int
)

/**
 * Samples a clip, renders 512×512 frames and muxes an animated WebP that fits in 500KB.
 * Bitmaps are recycled immediately. Only the encoded frame bytes are kept for an attempt.
 */
class VideoToStickerConverter(private val context: Context) {

    suspend fun convert(
        video: File,
        metadata: VideoMetadata,
        startMs: Long,
        endMs: Long,
        crop: CropMath.NormRect,
        topText: String,
        bottomText: String,
        useCurrentAsFirst: Boolean,
        currentMs: Long,
        outputWebp: File,
        outputTray: File?,
        outputAudio: File?,
        replacementAudio: File?,
        onProgress: (Float) -> Unit
    ): ConvertResult = withContext(Dispatchers.Default) {
        val start = startMs.coerceIn(0L, (metadata.durationMs - 1).coerceAtLeast(0L))
        var end = endMs.coerceIn(start + 1L, metadata.durationMs)
        if (end - start > 10_000L) end = start + 10_000L
        val fitPadding = !CropMath.isPixelSquare(crop, metadata.width, metadata.height)
        val main = Handler(Looper.getMainLooper())
        fun report(fraction: Float) {
            val value = fraction.coerceIn(0f, 0.99f)
            main.post { onProgress(value) }
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            var shortened = false
            var sawAnyFrame = false
            val qualities = intArrayOf(75, 60, 45, 30, 20)
            val fpsOptions = intArrayOf(15, 12, 10, 8)
            var step = 0
            val steps = (((end - start) / 500L) + 2).toInt() * fpsOptions.size * qualities.size
            while (end - start >= 400L) {
                for (fps in fpsOptions) {
                    val times = sampleTimes(start, end, fps, useCurrentAsFirst, currentMs)
                    val frameDuration = max(8, (1000.0 / fps).roundToInt())
                    for (quality in qualities) {
                        coroutineContext.ensureActive()
                        step++
                        report(step.toFloat() / steps.coerceAtLeast(1))
                        val encoded = ArrayList<ByteArray>(times.size)
                        var trayBytes: ByteArray? = null
                        var bytesSum = 0
                        var tooBig = false
                        for (index in times.indices) {
                            coroutineContext.ensureActive()
                            yield()
                            val raw = frameAt(retriever, times[index], metadata) ?: continue
                            sawAnyFrame = true
                            val rendered = try {
                                StickerProcessor.renderSquareFrame(
                                    raw,
                                    crop.left,
                                    crop.top,
                                    crop.right,
                                    crop.bottom,
                                    fitPadding,
                                    topText,
                                    bottomText
                                )
                            } finally {
                                if (!raw.isRecycled) raw.recycle()
                            }
                            try {
                                if (trayBytes == null) {
                                    trayBytes = StickerProcessor.encodeTrayPng(rendered)
                                }
                                val frameBytes = AnimatedWebpEncoder.compressFrame(rendered, quality)
                                encoded.add(frameBytes)
                                bytesSum += frameBytes.size
                                if (bytesSum > TARGET_BYTES) {
                                    tooBig = true
                                    break
                                }
                            } finally {
                                if (!rendered.isRecycled) rendered.recycle()
                            }
                        }
                        if (!tooBig && encoded.isNotEmpty()) {
                            val webp = AnimatedWebpEncoder.assemble(encoded, frameDuration)
                            if (webp.size <= TARGET_BYTES && webp.size <= 500 * 1024) {
                                outputWebp.parentFile?.mkdirs()
                                outputWebp.writeBytes(webp)
                                if (outputTray != null && trayBytes != null) {
                                    outputTray.parentFile?.mkdirs()
                                    outputTray.writeBytes(trayBytes)
                                }
                                val hasAudio = writeAudio(
                                    video, metadata, start, end, outputAudio, replacementAudio
                                )
                                main.post { onProgress(1f) }
                                return@withContext ConvertResult(
                                    frameCount = encoded.size,
                                    fps = fps,
                                    quality = quality,
                                    actualStartMs = start,
                                    actualEndMs = end,
                                    shortened = shortened,
                                    hasAudio = hasAudio,
                                    animationDurationMs = encoded.size * frameDuration
                                )
                            }
                        }
                    }
                }
                val next = end - 500L
                if (next - start < 400L) break
                end = next
                shortened = true
            }
            if (!sawAnyFrame) throw StickerEncodeException("NO_FRAMES")
            throw StickerEncodeException("TOO_BIG")
        } catch (c: CancellationException) {
            if (outputWebp.exists()) outputWebp.delete()
            throw c
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun writeAudio(
        video: File,
        metadata: VideoMetadata,
        start: Long,
        end: Long,
        outputAudio: File?,
        replacementAudio: File?
    ): Boolean {
        if (outputAudio == null) return false
        if (outputAudio.exists()) outputAudio.delete()
        val replacement = replacementAudio?.takeIf { it.exists() && it.length() > 0L }
        return try {
            val produced = when {
                replacement != null -> AudioExtractor.extract(
                    context, replacement, outputAudio, 0L, (end - start).coerceAtLeast(1L)
                )
                metadata.hasAudio -> AudioExtractor.extract(context, video, outputAudio, start, end)
                else -> null
            }
            produced != null && produced.exists() && produced.length() > 0L
        } catch (_: Exception) {
            outputAudio.delete()
            false
        }
    }

    companion object {
        const val TARGET_BYTES = 495_000

        fun sampleTimes(
            startMs: Long,
            endMs: Long,
            fps: Int,
            useCurrentAsFirst: Boolean,
            currentMs: Long
        ): LongArray {
            val span = (endMs - startMs).coerceAtLeast(1L)
            val frameDuration = max(8, (1000.0 / fps).roundToInt())
            var count = max(1, (span * fps / 1000.0).roundToInt())
            val maxFrames = (10_000 / frameDuration).coerceAtLeast(1)
            if (count > maxFrames) count = maxFrames
            val times = LongArray(count) { index ->
                if (count == 1) startMs else startMs + span * index / count
            }
            if (!useCurrentAsFirst || count <= 1) return times
            val target = currentMs.coerceIn(startMs, endMs)
            var best = 0
            var bestDist = Long.MAX_VALUE
            for (i in times.indices) {
                val dist = abs(times[i] - target)
                if (dist < bestDist) {
                    bestDist = dist
                    best = i
                }
            }
            if (best == 0) return times
            val rotated = LongArray(count)
            rotated[0] = times[best]
            var cursor = 1
            for (i in times.indices) {
                if (i == best) continue
                rotated[cursor++] = times[i]
            }
            return rotated
        }

        fun frameAt(
            retriever: MediaMetadataRetriever,
            timeMs: Long,
            metadata: VideoMetadata
        ): Bitmap? {
            val timeUs = timeMs * 1000L
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    1280,
                    1280
                ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { frame ->
                    downscale(frame, 1280)
                }
            } ?: return null
            return orient(raw, metadata)
        }

        private fun downscale(frame: Bitmap, maxSide: Int): Bitmap {
            if (frame.width <= maxSide && frame.height <= maxSide) return frame
            val scale = maxSide.toFloat() / max(frame.width, frame.height)
            val scaled = Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled != frame) frame.recycle()
            return scaled
        }

        fun orient(frame: Bitmap, metadata: VideoMetadata): Bitmap {
            val rotation = ((metadata.rotation % 360) + 360) % 360
            if (rotation == 0) return frame
            val swapped = rotation == 90 || rotation == 270
            if (!swapped) return frame
            val displayLandscape = metadata.width >= metadata.height
            val frameLandscape = frame.width >= frame.height
            val matchesEncoded = frame.width == metadata.encodedWidth &&
                frame.height == metadata.encodedHeight
            val needsRotate = matchesEncoded || frameLandscape != displayLandscape
            if (!needsRotate) return frame
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
            if (rotated != frame) frame.recycle()
            return rotated
        }
    }
}
