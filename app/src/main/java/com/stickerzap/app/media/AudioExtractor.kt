package com.stickerzap.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Copies an AAC track into an .m4a with MediaExtractor/MediaMuxer.
 * If the codec cannot be muxed into MP4, falls back to a Media3 audio-only transcode.
 */
object AudioExtractor {

    enum class Status { SUCCESS, NO_AUDIO, NEED_TRANSCODE }

    fun passthrough(input: File, output: File, startMs: Long, endMs: Long): Status {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(input.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) return Status.NO_AUDIO
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime != "audio/mp4a-latm") return Status.NEED_TRANSCODE

            extractor.selectTrack(track)
            val startUs = startMs.coerceAtLeast(0L) * 1000L
            val endUs = endMs.coerceAtLeast(startMs) * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            output.parentFile?.mkdirs()
            if (output.exists()) output.delete()
            val localMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = localMuxer
            val outTrack = localMuxer.addTrack(format)
            localMuxer.start()
            started = true

            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var wrote = false
            var firstPts = -1L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (pts < 0L) break
                if (pts >= endUs) break
                if (pts >= startUs) {
                    if (firstPts < 0L) firstPts = pts
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = (pts - firstPts).coerceAtLeast(0L)
                    info.flags = extractor.sampleFlags
                    localMuxer.writeSampleData(outTrack, buffer, info)
                    wrote = true
                }
                if (!extractor.advance()) break
            }
            if (!wrote) {
                started = false
                output.delete()
                return Status.NEED_TRANSCODE
            }
            localMuxer.stop()
            started = false
            return Status.SUCCESS
        } catch (_: Exception) {
            output.delete()
            return Status.NEED_TRANSCODE
        } finally {
            if (started) {
                try {
                    muxer?.stop()
                } catch (_: Exception) {
                }
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }

    suspend fun extract(
        context: Context,
        input: File,
        output: File,
        startMs: Long,
        endMs: Long
    ): File? {
        val status = withContext(Dispatchers.IO) {
            passthrough(input, output, startMs, endMs)
        }
        return when (status) {
            Status.SUCCESS -> output
            Status.NO_AUDIO -> null
            Status.NEED_TRANSCODE -> {
                if (!hasAudio(input)) return null
                withContext(Dispatchers.Main) {
                    transcodeAudio(context, input, output, startMs, endMs)
                }
                if (output.exists() && output.length() > 0L) output else null
            }
        }
    }

    private fun hasAudio(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { index ->
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                mime?.startsWith("audio/") == true
            }
        } catch (_: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun transcodeAudio(
        context: Context,
        input: File,
        output: File,
        startMs: Long,
        endMs: Long
    ) {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        suspendCancellableCoroutine { cont ->
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(input))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs.coerceAtLeast(0L))
                        .setEndPositionMs(endMs.coerceAtLeast(startMs + 1L))
                        .build()
                )
                .build()
            val edited = EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .build()
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: androidx.media3.transformer.ExportResult
                    ) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: androidx.media3.transformer.ExportResult,
                        exportException: ExportException
                    ) {
                        output.delete()
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation {
                transformer.cancel()
                output.delete()
            }
            try {
                transformer.start(edited, output.absolutePath)
            } catch (t: Throwable) {
                output.delete()
                if (cont.isActive) cont.resumeWithException(t)
            }
        }
    }
}
