package com.stickerzap.app.media

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin animated WebP muxer (RIFF / VP8X / ANIM / ANMF).
 * No android.* types, so unit tests can run on the JVM.
 *
 * Spec: https://developers.google.com/speed/webp/docs/riff_container
 * ANMF flags follow libwebp: bit 0 = dispose to background, bit 1 = do not blend.
 */
object AnimatedWebpMuxer {

    const val MIN_FRAME_DURATION_MS = 8

    class FrameInput(val webpBytes: ByteArray, val durationMs: Int)

    data class WebpAnimation(
        val canvasWidth: Int,
        val canvasHeight: Int,
        val animated: Boolean,
        val hasAlpha: Boolean,
        val loopCount: Int,
        val frameDurationsMs: List<Int>
    )

    fun mux(
        frames: List<FrameInput>,
        canvasWidth: Int = 512,
        canvasHeight: Int = 512,
        minFrameDurationMs: Int = MIN_FRAME_DURATION_MS
    ): ByteArray {
        require(frames.isNotEmpty()) { "Nenhum quadro para montar." }
        require(canvasWidth in 1..0xFFFFFF && canvasHeight in 1..0xFFFFFF) {
            "Canvas inválido: ${canvasWidth}×${canvasHeight}"
        }
        val extracted = frames.map { extractImageChunks(it.webpBytes) }
        val hasAlpha = extracted.any { it.hasAlpha }
        val body = ByteArrayOutputStream()
        body.write(fourcc("WEBP"))
        writeChunk(body, "VP8X", vp8xPayload(canvasWidth, canvasHeight, hasAlpha))
        writeChunk(body, "ANIM", animPayload())
        for (i in frames.indices) {
            val duration = frames[i].durationMs
                .coerceAtLeast(minFrameDurationMs)
                .coerceAtMost(0xFFFFFF)
            writeChunk(
                body,
                "ANMF",
                anmfPayload(extracted[i].bytes, duration, canvasWidth, canvasHeight)
            )
        }
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream(bodyBytes.size + 8)
        out.write(fourcc("RIFF"))
        writeU32(out, bodyBytes.size)
        out.write(bodyBytes)
        return out.toByteArray()
    }

    fun inspect(bytes: ByteArray): WebpAnimation {
        require(bytes.size >= 12 && tagAt(bytes, 0) == "RIFF" && tagAt(bytes, 8) == "WEBP") {
            "Não é um WebP."
        }
        var offset = 12
        var canvasW = 0
        var canvasH = 0
        var animatedFlag = false
        var hasAlpha = false
        var loop = 0
        var sawAnim = false
        val durations = ArrayList<Int>()
        while (offset + 8 <= bytes.size) {
            val tag = tagAt(bytes, offset)
            val size = readU32(bytes, offset + 4)
            if (size < 0 || offset + 8L + size > bytes.size) break
            val start = offset + 8
            when (tag) {
                "VP8X" -> if (size >= 10) {
                    val flags = bytes[start].toInt() and 0xFF
                    animatedFlag = flags and 0x02 != 0
                    hasAlpha = flags and 0x10 != 0
                    canvasW = readU24(bytes, start + 4) + 1
                    canvasH = readU24(bytes, start + 7) + 1
                }
                "ANIM" -> {
                    sawAnim = true
                    if (size >= 6) {
                        loop = (bytes[start + 4].toInt() and 0xFF) or
                            ((bytes[start + 5].toInt() and 0xFF) shl 8)
                    }
                }
                "ANMF" -> if (size >= 16) {
                    durations.add(readU24(bytes, start + 12))
                }
            }
            val next = offset + 8 + size + (size and 1)
            offset = if (next > bytes.size) bytes.size else next
        }
        return WebpAnimation(
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            animated = animatedFlag && sawAnim && durations.isNotEmpty(),
            hasAlpha = hasAlpha,
            loopCount = loop,
            frameDurationsMs = durations
        )
    }

    private class ImageChunks(val bytes: ByteArray, val hasAlpha: Boolean)

    private fun extractImageChunks(webp: ByteArray): ImageChunks {
        require(webp.size >= 12 && tagAt(webp, 0) == "RIFF" && tagAt(webp, 8) == "WEBP") {
            "Quadro WebP inválido."
        }
        var offset = 12
        var hasAlpha = false
        var alphaPayload: ByteArray? = null
        var bitstream: ByteArray? = null
        var bitstreamTag: String? = null
        while (offset + 8 <= webp.size) {
            val tag = tagAt(webp, offset)
            val size = readU32(webp, offset + 4)
            if (size < 0 || offset + 8L + size > webp.size) break
            val start = offset + 8
            val payload = webp.copyOfRange(start, start + size)
            when (tag) {
                "VP8X" -> if (payload.isNotEmpty() && (payload[0].toInt() and 0x10) != 0) {
                    hasAlpha = true
                }
                "ALPH" -> {
                    alphaPayload = payload
                    hasAlpha = true
                }
                "VP8 " -> {
                    bitstream = payload
                    bitstreamTag = "VP8 "
                }
                "VP8L" -> {
                    bitstream = payload
                    bitstreamTag = "VP8L"
                    hasAlpha = true
                }
            }
            val next = offset + 8 + size + (size and 1)
            offset = if (next > webp.size) webp.size else next
        }
        val tag = bitstreamTag ?: throw IllegalArgumentException("WebP sem quadro VP8/VP8L.")
        val data = bitstream ?: throw IllegalArgumentException("WebP sem quadro VP8/VP8L.")
        val out = ByteArrayOutputStream()
        if (alphaPayload != null) writeChunk(out, "ALPH", alphaPayload)
        writeChunk(out, tag, data)
        return ImageChunks(out.toByteArray(), hasAlpha)
    }

    private fun vp8xPayload(width: Int, height: Int, hasAlpha: Boolean): ByteArray {
        val payload = ByteArray(10)
        payload[0] = (0x02 or if (hasAlpha) 0x10 else 0).toByte()
        writeU24(payload, 4, width - 1)
        writeU24(payload, 7, height - 1)
        return payload
    }

    /** Background BGRA 0 and loop count 0 (infinite). */
    private fun animPayload(): ByteArray = ByteArray(6)

    private fun anmfPayload(
        imageChunks: ByteArray,
        durationMs: Int,
        width: Int,
        height: Int
    ): ByteArray {
        val out = ByteArrayOutputStream(16 + imageChunks.size)
        writeU24(out, 0)
        writeU24(out, 0)
        writeU24(out, width - 1)
        writeU24(out, height - 1)
        writeU24(out, durationMs)
        out.write(0b00000010)
        out.write(imageChunks)
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, tag: String, payload: ByteArray) {
        out.write(fourcc(tag))
        writeU32(out, payload.size)
        out.write(payload)
        if (payload.size and 1 == 1) out.write(0)
    }

    private fun fourcc(tag: String): ByteArray {
        require(tag.length == 4) { "FourCC inválido: $tag" }
        return tag.toByteArray(StandardCharsets.US_ASCII)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun writeU24(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
    }

    private fun writeU24(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = (value and 0xFF).toByte()
        dest[offset + 1] = ((value shr 8) and 0xFF).toByte()
        dest[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }

    private fun tagAt(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, StandardCharsets.US_ASCII)

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readU24(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}
