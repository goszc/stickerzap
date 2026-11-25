package com.stickerzap.app.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

data class VideoMetadata(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val sizeBytes: Long,
    val hasAudio: Boolean
)

class NoVideoTrackException : Exception()

object VideoProbe {

    fun read(file: File): VideoMetadata {
        var hasVideo = false
        var hasAudio = false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
            }
        } catch (_: Exception) {
            throw NoVideoTrackException()
        } finally {
            extractor.release()
        }
        if (!hasVideo) throw NoVideoTrackException()

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val encodedW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val encodedH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (duration <= 0L || encodedW <= 0 || encodedH <= 0) throw NoVideoTrackException()
            val swapped = rotation % 180 != 0
            return VideoMetadata(
                durationMs = duration,
                width = if (swapped) encodedH else encodedW,
                height = if (swapped) encodedW else encodedH,
                rotation = rotation,
                encodedWidth = encodedW,
                encodedHeight = encodedH,
                sizeBytes = file.length(),
                hasAudio = hasAudio
            )
        } catch (e: NoVideoTrackException) {
            throw e
        } catch (_: Exception) {
            throw NoVideoTrackException()
        } finally {
            retriever.release()
        }
    }
}
