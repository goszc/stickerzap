package com.stickerzap.app.media

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedWebpMuxerTest {

    @Test
    fun muxesRiffVp8xAnimAnmfAndClampsDuration() {
        val plain = singleFrame("VP8 ", byteArrayOf(1, 2, 3))
        val withAlpha = singleFrameVp8x(
            alpha = byteArrayOf(9, 8, 7),
            vp8 = byteArrayOf(4, 5, 6, 7)
        )
        val out = AnimatedWebpMuxer.mux(
            listOf(
                AnimatedWebpMuxer.FrameInput(plain, 1),
                AnimatedWebpMuxer.FrameInput(withAlpha, 40)
            )
        )

        assertEquals("RIFF", tag(out, 0))
        assertEquals("WEBP", tag(out, 8))
        assertEquals(out.size - 8, u32(out, 4))

        val chunks = walk(out)
        assertEquals(listOf("VP8X", "ANIM", "ANMF", "ANMF"), chunks.map { it.tag })
        chunks.forEach { chunk ->
            assertEquals(0, chunk.offset % 2)
            val next = chunk.offset + 8 + chunk.size + (chunk.size and 1)
            if (chunk != chunks.last()) {
                assertEquals(chunks[chunks.indexOf(chunk) + 1].offset, next)
            }
        }

        val vp8x = chunks[0]
        assertEquals(10, vp8x.size)
        assertEquals(0x12, vp8x.payload[0].toInt() and 0xFF)
        assertEquals(0, vp8x.payload[1].toInt())
        assertEquals(511, u24(vp8x.payload, 4))
        assertEquals(511, u24(vp8x.payload, 7))

        val anim = chunks[1]
        assertEquals(6, anim.size)
        assertTrue(anim.payload.all { it == 0.toByte() })

        val first = chunks[2]
        assertEquals(0, u24(first.payload, 0))
        assertEquals(0, u24(first.payload, 3))
        assertEquals(511, u24(first.payload, 6))
        assertEquals(511, u24(first.payload, 9))
        assertEquals(8, u24(first.payload, 12))
        assertEquals(0b00000010, first.payload[15].toInt() and 0xFF)

        val second = chunks[3]
        assertEquals(40, u24(second.payload, 12))
        assertEquals(0b00000010, second.payload[15].toInt() and 0xFF)
        assertTrue(tag(second.payload, 16) == "ALPH")
    }

    @Test
    fun opaqueFramesDoNotSetAlphaFlag() {
        val out = AnimatedWebpMuxer.mux(
            listOf(AnimatedWebpMuxer.FrameInput(singleFrame("VP8 ", byteArrayOf(1, 2)), 8))
        )
        val vp8x = walk(out).first { it.tag == "VP8X" }
        assertEquals(0x02, vp8x.payload[0].toInt() and 0xFF)
    }

    @Test
    fun vp8lSetsAlphaAndSkipsIccp() {
        val iccp = ByteArray(5) { it.toByte() }
        val webp = riff(
            listOf(
                "VP8X" to vp8xBytes(alpha = false),
                "ICCP" to iccp,
                "VP8L" to byteArrayOf(3, 3, 3)
            )
        )
        val out = AnimatedWebpMuxer.mux(
            listOf(AnimatedWebpMuxer.FrameInput(webp, 7))
        )
        val chunks = walk(out)
        assertEquals(0x12, chunks.first { it.tag == "VP8X" }.payload[0].toInt() and 0xFF)
        val frame = chunks.first { it.tag == "ANMF" }
        assertEquals(8, u24(frame.payload, 12))
        val inner = frame.payload.copyOfRange(16, frame.payload.size)
        assertEquals("VP8L", tag(inner, 0))
        assertTrue(!String(inner, StandardCharsets.US_ASCII).contains("ICCP"))
    }

    @Test
    fun durationOfEightStaysEight() {
        val out = AnimatedWebpMuxer.mux(
            listOf(AnimatedWebpMuxer.FrameInput(singleFrame("VP8 ", byteArrayOf(1)), 8))
        )
        val frame = walk(out).first { it.tag == "ANMF" }
        assertEquals(8, u24(frame.payload, 12))
    }

    private class Chunk(val tag: String, val offset: Int, val size: Int, val payload: ByteArray)

    private fun walk(file: ByteArray): List<Chunk> {
        assertEquals("RIFF", tag(file, 0))
        assertEquals("WEBP", tag(file, 8))
        val chunks = ArrayList<Chunk>()
        var offset = 12
        while (offset + 8 <= file.size) {
            val size = u32(file, offset + 4)
            assertTrue(size >= 0)
            assertTrue(offset + 8 + size <= file.size)
            chunks.add(
                Chunk(
                    tag = tag(file, offset),
                    offset = offset,
                    size = size,
                    payload = file.copyOfRange(offset + 8, offset + 8 + size)
                )
            )
            val next = offset + 8 + size + (size and 1)
            assertTrue(next <= file.size || next == file.size + (size and 1))
            if (next > file.size) break
            offset = next
        }
        return chunks
    }

    private fun singleFrame(tag: String, payload: ByteArray): ByteArray =
        riff(listOf(tag to payload))

    private fun singleFrameVp8x(alpha: ByteArray, vp8: ByteArray): ByteArray =
        riff(
            listOf(
                "VP8X" to vp8xBytes(alpha = true),
                "ALPH" to alpha,
                "VP8 " to vp8
            )
        )

    private fun vp8xBytes(alpha: Boolean): ByteArray {
        val bytes = ByteArray(10)
        bytes[0] = (if (alpha) 0x10 else 0).toByte()
        bytes[4] = 0xFF.toByte()
        bytes[5] = 0x01
        bytes[7] = 0xFF.toByte()
        bytes[8] = 0x01
        return bytes
    }

    private fun riff(chunks: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(StandardCharsets.US_ASCII))
        for ((tag, payload) in chunks) {
            body.write(tag.toByteArray(StandardCharsets.US_ASCII))
            writeU32(body, payload.size)
            body.write(payload)
            if (payload.size and 1 == 1) body.write(0)
        }
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
        writeU32(out, bodyBytes.size)
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun tag(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, StandardCharsets.US_ASCII)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun u24(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}
