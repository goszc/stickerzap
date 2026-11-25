package com.stickerzap.app.provider

import com.stickerzap.app.data.StickerItem
import com.stickerzap.app.data.StickerPack
import com.stickerzap.app.media.AnimatedWebpMuxer
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackValidatorTest {

    @Test
    fun mixedPackRejected() {
        val pack = StickerPack(
            identifier = "pack_mix",
            name = "Mix",
            publisher = "Eu",
            animatedStickerPack = true,
            stickers = listOf(
                sticker("a", animated = true),
                sticker("b", animated = false)
            )
        )
        val error = PackValidator.validatePackTypes(pack)
        assertNotNull(error)
        assertTrue(error!!.contains("mistura"))
    }

    @Test
    fun staticPackRejectsAnimatedSticker() {
        val pack = StickerPack(
            identifier = "pack_static",
            name = "Fotos",
            publisher = "Eu",
            animatedStickerPack = false,
            stickers = listOf(sticker("a", animated = true))
        )
        val error = PackValidator.validatePackTypes(pack)
        assertNotNull(error)
        assertTrue(error!!.contains("estáticas"))
    }

    @Test
    fun uniformAnimatedPackIsAccepted() {
        val pack = StickerPack(
            identifier = "pack_anim",
            name = "Vídeos",
            publisher = "Eu",
            animatedStickerPack = true,
            stickers = listOf(sticker("a", animated = true), sticker("b", animated = true))
        )
        assertNull(PackValidator.validatePackTypes(pack))
    }

    @Test
    fun animatedOver500KbRejected() {
        val big = ByteArray(StickerPackContract.ANIMATED_STICKER_MAX_BYTES + 1)
        val error = PackValidator.validateAnimatedBytes(big, "Grande")
        assertNotNull(error)
        assertTrue(error!!.contains("500KB"))
    }

    @Test
    fun totalOver10SecondsRejected() {
        val webp = animated(frameDurationMs = 6_000, frames = 2, minFrame = 8)
        val error = PackValidator.validateAnimatedBytes(webp, "Longa")
        assertNotNull(error)
        assertTrue(error!!.contains("10"))
    }

    @Test
    fun frameShorterThan8MsRejected() {
        val webp = animated(frameDurationMs = 4, frames = 2, minFrame = 0)
        val error = PackValidator.validateAnimatedBytes(webp, "Curta")
        assertNotNull(error)
        assertTrue(error!!.contains("8"))
    }

    @Test
    fun validAnimatedWebpPasses() {
        val webp = animated(frameDurationMs = 100, frames = 3, minFrame = 8)
        assertTrue(webp.size < StickerPackContract.ANIMATED_STICKER_MAX_BYTES)
        assertNull(PackValidator.validateAnimatedBytes(webp, "Ok"))
    }

    @Test
    fun accessibilityLimits() {
        assertNotNull(PackValidator.validateAccessibility("a".repeat(126), animated = false))
        assertNull(PackValidator.validateAccessibility("a".repeat(125), animated = false))
        assertNotNull(PackValidator.validateAccessibility("a".repeat(256), animated = true))
        assertNull(PackValidator.validateAccessibility("a".repeat(255), animated = true))
    }

    private fun sticker(id: String, animated: Boolean) = StickerItem(
        id = id,
        name = id,
        imageFileName = "$id.webp",
        isAnimated = animated
    )

    private fun animated(frameDurationMs: Int, frames: Int, minFrame: Int): ByteArray {
        val frame = singleVp8()
        return AnimatedWebpMuxer.mux(
            List(frames) { AnimatedWebpMuxer.FrameInput(frame, frameDurationMs) },
            minFrameDurationMs = minFrame
        )
    }

    private fun singleVp8(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(StandardCharsets.US_ASCII))
        body.write("VP8 ".toByteArray(StandardCharsets.US_ASCII))
        val payload = byteArrayOf(1, 2, 3, 4)
        body.write(payload.size)
        body.write(0)
        body.write(0)
        body.write(0)
        body.write(payload)
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
        val size = bodyBytes.size
        out.write(size and 0xFF)
        out.write((size shr 8) and 0xFF)
        out.write((size shr 16) and 0xFF)
        out.write((size shr 24) and 0xFF)
        out.write(bodyBytes)
        return out.toByteArray()
    }
}
