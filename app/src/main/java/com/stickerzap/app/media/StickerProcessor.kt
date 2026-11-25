package com.stickerzap.app.media

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object StickerProcessor {

    enum class MaskType { ORIGINAL, CIRCLE, ROUNDED, WHITE_BORDER }

    fun createStickerBitmap(
        context: Context,
        imageUri: Uri,
        mask: MaskType,
        topText: String,
        bottomText: String
    ): Bitmap? {
        val raw = context.contentResolver.openInputStream(imageUri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null

        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val scale = minOf(472f / raw.width, 472f / raw.height)
        val w = raw.width * scale
        val h = raw.height * scale
        val left = (512f - w) / 2f
        val top = (512f - h) / 2f
        val destRect = RectF(left, top, left + w, top + h)

        val saveCount = canvas.save()
        when (mask) {
            MaskType.CIRCLE -> {
                val path = Path().apply { addCircle(256f, 256f, 236f, Path.Direction.CW) }
                canvas.clipPath(path)
            }
            MaskType.ROUNDED -> {
                val path = Path().apply {
                    addRoundRect(RectF(20f, 20f, 492f, 492f), 72f, 72f, Path.Direction.CW)
                }
                canvas.clipPath(path)
            }
            else -> {}
        }

        canvas.drawBitmap(raw, null, destRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restoreToCount(saveCount)

        if (mask == MaskType.CIRCLE || mask == MaskType.WHITE_BORDER) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 10f
                color = Color.WHITE
            }
            if (mask == MaskType.CIRCLE) {
                canvas.drawCircle(256f, 256f, 236f, borderPaint)
            } else {
                canvas.drawRoundRect(RectF(20f, 20f, 492f, 492f), 40f, 40f, borderPaint)
            }
        }

        drawMemeText(canvas, topText, 68f)
        drawMemeText(canvas, bottomText, 472f)
        return out
    }

    /**
     * Draws [source] into a 512×512 sticker. A non-square crop is letterboxed
     * when [fitWithPadding] is true; a square crop fills the canvas.
     */
    fun renderSquareFrame(
        source: Bitmap,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        fitWithPadding: Boolean,
        topText: String,
        bottomText: String
    ): Bitmap {
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val sw = source.width.toFloat().coerceAtLeast(1f)
        val sh = source.height.toFloat().coerceAtLeast(1f)
        val left = (cropLeft * sw).coerceIn(0f, sw - 1f)
        val top = (cropTop * sh).coerceIn(0f, sh - 1f)
        val right = (cropRight * sw).coerceIn(left + 1f, sw)
        val bottom = (cropBottom * sh).coerceIn(top + 1f, sh)
        val srcRect = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (fitWithPadding) {
            val cropW = (right - left).coerceAtLeast(1f)
            val cropH = (bottom - top).coerceAtLeast(1f)
            val scale = minOf(512f / cropW, 512f / cropH)
            val dw = cropW * scale
            val dh = cropH * scale
            val dx = (512f - dw) / 2f
            val dy = (512f - dh) / 2f
            canvas.drawBitmap(source, srcRect, RectF(dx, dy, dx + dw, dy + dh), paint)
        } else {
            canvas.drawBitmap(source, srcRect, RectF(0f, 0f, 512f, 512f), paint)
        }
        drawMemeText(canvas, topText, 68f)
        drawMemeText(canvas, bottomText, 472f)
        return out
    }

    fun drawMemeText(canvas: Canvas, text: String, y: Float) {
        val clean = text.trim().uppercase()
        if (clean.isEmpty()) return

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 50f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 50f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawText(clean, 256f, y, strokePaint)
        canvas.drawText(clean, 256f, y, fillPaint)
    }

    fun saveWebpUnder100Kb(bitmap: Bitmap, destFile: File) {
        require(bitmap.width == 512 && bitmap.height == 512) {
            "Sticker bitmap must be 512×512, was ${bitmap.width}×${bitmap.height}"
        }
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        var quality = 90
        var bytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            if (!bitmap.compress(format, quality, baos)) {
                throw IllegalStateException("Falha ao compactar a figurinha em WebP.")
            }
            bytes = baos.toByteArray()
            quality -= if (quality > 30) 10 else 5
        } while (bytes.size > 100 * 1024 && quality >= 0)

        if (bytes.size > 100 * 1024) {
            throw IllegalStateException("A figurinha passou de 100KB mesmo na qualidade mínima.")
        }
        FileOutputStream(destFile).use { it.write(bytes) }
    }

    fun encodeTrayPng(bitmap: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
        try {
            val candidates = ArrayList<Bitmap>(2)
            candidates.add(scaled)
            val opaque = scaled.copy(Bitmap.Config.RGB_565, false)
            if (opaque != null && opaque != scaled) candidates.add(opaque)
            var last = ByteArray(0)
            for (candidate in candidates) {
                val baos = ByteArrayOutputStream()
                if (!candidate.compress(Bitmap.CompressFormat.PNG, 100, baos)) continue
                last = baos.toByteArray()
                if (last.size <= 50 * 1024) {
                    candidates.forEach { if (it != scaled && it != bitmap && !it.isRecycled) it.recycle() }
                    return last
                }
            }
            candidates.forEach { if (it != scaled && it != bitmap && !it.isRecycled) it.recycle() }
            if (last.size > 50 * 1024) {
                throw IllegalStateException("O ícone da bandeja passou de 50KB.")
            }
            throw IllegalStateException("Falha ao salvar o ícone da bandeja.")
        } finally {
            if (scaled != bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }

    fun saveTrayIcon96(bitmap: Bitmap, destFile: File) {
        val bytes = encodeTrayPng(bitmap)
        FileOutputStream(destFile).use { it.write(bytes) }
    }
}
