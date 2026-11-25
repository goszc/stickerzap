package com.stickerzap.app.media

import kotlin.math.abs
import kotlin.math.min

/** Normalized crop rectangle (0..1, origin top-left) on the rotation-corrected frame. */
object CropMath {

    data class NormRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        fun asList(): List<Float> = listOf(left, top, right, bottom)

        fun closeTo(other: NormRect, epsilon: Float = 0.02f): Boolean {
            return abs(left - other.left) < epsilon &&
                abs(top - other.top) < epsilon &&
                abs(right - other.right) < epsilon &&
                abs(bottom - other.bottom) < epsilon
        }

        companion object {
            fun fromList(values: List<Float>?): NormRect? {
                if (values == null || values.size < 4) return null
                return NormRect(values[0], values[1], values[2], values[3])
            }
        }
    }

    fun full(): NormRect = NormRect(0f, 0f, 1f, 1f)

    /** Largest centered square inside the frame (cover). */
    fun cover(width: Int, height: Int): NormRect {
        if (width <= 0 || height <= 0 || width == height) return full()
        return if (width > height) {
            val normW = height.toFloat() / width.toFloat()
            val left = (1f - normW) / 2f
            NormRect(left, 0f, left + normW, 1f)
        } else {
            val normH = width.toFloat() / height.toFloat()
            val top = (1f - normH) / 2f
            NormRect(0f, top, 1f, top + normH)
        }
    }

    fun center(rect: NormRect): NormRect {
        val w = (rect.right - rect.left).coerceIn(0.01f, 1f)
        val h = (rect.bottom - rect.top).coerceIn(0.01f, 1f)
        val left = ((1f - w) / 2f).coerceIn(0f, 1f - w)
        val top = ((1f - h) / 2f).coerceIn(0f, 1f - h)
        return NormRect(left, top, left + w, top + h)
    }

    fun pan(rect: NormRect, dxNorm: Float, dyNorm: Float): NormRect {
        val w = rect.right - rect.left
        val h = rect.bottom - rect.top
        val left = (rect.left + dxNorm).coerceIn(0f, (1f - w).coerceAtLeast(0f))
        val top = (rect.top + dyNorm).coerceIn(0f, (1f - h).coerceAtLeast(0f))
        return NormRect(left, top, left + w, top + h)
    }

    /**
     * Pinch-zoom a pixel-square crop. [factor] > 1 shrinks the window (zoom in).
     */
    fun zoomSquare(rect: NormRect, videoW: Int, videoH: Int, factor: Float): NormRect {
        if (videoW <= 0 || videoH <= 0) return rect
        val safe = if (factor.isFinite() && factor > 0.05f) factor else 1f
        val currentSide = (rect.right - rect.left) * videoW
        val maxSide = min(videoW, videoH).toFloat()
        val side = (currentSide / safe).coerceIn(48f, maxSide)
        val normW = side / videoW
        val normH = side / videoH
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        var left = cx - normW / 2f
        var top = cy - normH / 2f
        if (left < 0f) left = 0f
        if (top < 0f) top = 0f
        if (left + normW > 1f) left = 1f - normW
        if (top + normH > 1f) top = 1f - normH
        return NormRect(left, top, left + normW, top + normH)
    }

    fun isPixelSquare(rect: NormRect, videoW: Int, videoH: Int): Boolean {
        if (videoW <= 0 || videoH <= 0) return true
        val pw = (rect.right - rect.left) * videoW
        val ph = (rect.bottom - rect.top) * videoH
        if (pw < 1f || ph < 1f) return true
        val diff = abs(pw - ph)
        return diff / maxOf(pw, ph) < 0.02f
    }

    /** NDC edges for Media3 Crop: left, right, bottom, top. */
    fun toNdc(rect: NormRect): FloatArray {
        val left = (rect.left * 2f - 1f).coerceIn(-1f, 1f)
        val right = (rect.right * 2f - 1f).coerceIn(-1f, 1f)
        val top = (1f - rect.top * 2f).coerceIn(-1f, 1f)
        val bottom = (1f - rect.bottom * 2f).coerceIn(-1f, 1f)
        val l = minOf(left, right - 0.002f)
        val r = maxOf(right, l + 0.002f)
        val b = minOf(bottom, top - 0.002f)
        val t = maxOf(top, b + 0.002f)
        return floatArrayOf(
            l.coerceIn(-1f, 1f),
            r.coerceIn(-1f, 1f),
            b.coerceIn(-1f, 1f),
            t.coerceIn(-1f, 1f)
        )
    }
}
