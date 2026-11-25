package com.stickerzap.app.media

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

/** Encodes bitmaps with the platform WebP encoder and muxes them into an animation. */
object AnimatedWebpEncoder {

    fun compressFrame(bitmap: Bitmap, quality: Int): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        val baos = ByteArrayOutputStream()
        if (!bitmap.compress(format, quality.coerceIn(0, 100), baos)) {
            throw IllegalStateException("Falha ao compactar um quadro em WebP.")
        }
        return baos.toByteArray()
    }

    fun assemble(frames: List<ByteArray>, frameDurationMs: Int): ByteArray {
        return AnimatedWebpMuxer.mux(
            frames.map { AnimatedWebpMuxer.FrameInput(it, frameDurationMs) }
        )
    }
}
